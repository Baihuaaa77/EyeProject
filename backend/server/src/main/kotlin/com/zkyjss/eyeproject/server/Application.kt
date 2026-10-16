package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ConsentInput
import com.zkyjss.eyeproject.core.sync.ErrorBody
import com.zkyjss.eyeproject.core.sync.ErrorCodes
import com.zkyjss.eyeproject.core.sync.LoginRequest
import com.zkyjss.eyeproject.core.sync.RefreshRequest
import com.zkyjss.eyeproject.core.sync.RegisterRequest
import com.zkyjss.eyeproject.core.sync.RecordUpload
import com.zkyjss.eyeproject.core.sync.SubjectInput
import com.zkyjss.eyeproject.core.sync.SyncContract
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(Netty, port = config.port) { eyeProjectModule(config) }.start(wait = true)
}

/** 已认证账号。 */
data class AccountPrincipal(val accountId: String)

/** 服务端全部组件；测试可注入时钟。 */
class Services(val config: ServerConfig, clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    val db = Database(config)
    val audit = AuditLog(IpHasher(config.auditSalt), clock)
    val auth = AuthService(db, config, audit, clock)
    val records = RecordService(db, PayloadCipher(config.dataKeys, config.activeKeyId), audit, clock)
    val profile = ProfileService(db, config, audit, records, clock)
    val retention = RetentionJob(db, config, audit, clock)
    override fun close() = db.close()
}

fun Application.eyeProjectModule(config: ServerConfig, services: Services = Services(config), runJobs: Boolean = true) {
    val auth = RateLimitName("auth")
    if (config.trustProxy) install(XForwardedHeaders)
    install(CallLogging) {
        level = Level.INFO
        // 只记方法、路径和状态；不记请求体、令牌、邮箱。
        format { call -> "${call.request.local.method.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(ContentNegotiation) { json(SyncContract.json) }
    install(RateLimit) {
        register(auth) {
            rateLimiter(limit = config.authRatePerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorBody(e.code, e.message)) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorCodes.INVALID_REQUEST, "请求格式不正确"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("未处理的错误", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal_error", "服务暂时不可用"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorBody(ErrorCodes.RATE_LIMITED, "请求过于频繁，请稍后再试"))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respond(status, ErrorBody(ErrorCodes.INVALID_TOKEN, "登录已失效，请重新登录"))
        }
    }
    install(createApplicationPlugin("HttpsOnly") {
        onCall { call ->
            if (config.requireHttps && call.request.origin.scheme != "https" && call.request.path() != "/health")
                call.respond(HttpStatusCode.Forbidden, ErrorBody(ErrorCodes.HTTPS_REQUIRED, "必须使用 HTTPS"))
        }
    })
    install(createApplicationPlugin("BodyLimit") {
        onCall { call ->
            val length = call.request.contentLength()
            if (length != null && length > SyncContract.MAX_PAYLOAD_BYTES * 2L)
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody(ErrorCodes.PAYLOAD_TOO_LARGE, "请求超过大小上限"))
        }
    })
    install(Authentication) {
        bearer("api") {
            authenticate { credential -> services.auth.authenticate(credential.token)?.let(::AccountPrincipal) }
        }
    }

    if (runJobs) {
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        services.retention.start(jobs)
        monitor.subscribe(ApplicationStopped) { jobs.cancel(); services.close() }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        route(SyncContract.API_PREFIX) {
            rateLimit(auth) { authRoutes(services) }
            authenticate("api") {
                meRoutes(services)
                subjectRoutes(services)
                recordRoutes(services)
            }
        }
    }
}

private val ApplicationCall.accountId: String get() = principal<AccountPrincipal>()!!.accountId
private val ApplicationCall.ip: String get() = request.origin.remoteHost

private fun Route.authRoutes(s: Services) = route("/auth") {
    post("/register") { call.respond(HttpStatusCode.Created, s.auth.register(call.receive<RegisterRequest>(), call.ip)) }
    post("/login") { call.respond(s.auth.login(call.receive<LoginRequest>(), call.ip)) }
    post("/refresh") { call.respond(s.auth.refresh(call.receive<RefreshRequest>().refreshToken, call.ip)) }
    authenticate("api") {
        post("/logout") {
            s.auth.logout(call.receive<RefreshRequest>().refreshToken, call.accountId, call.ip)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.meRoutes(s: Services) = route("/me") {
    get { call.respond(s.profile.account(call.accountId)) }
    delete {
        s.profile.deleteAccount(call.accountId, call.ip)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/export") {
        call.response.header(HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "eyeproject-export.json").toString())
        call.respond(s.profile.export(call.accountId, call.ip))
    }
    get("/consents") { call.respond(s.profile.consents(call.accountId)) }
    post("/consents") { call.respond(s.profile.recordConsent(call.accountId, call.receive<ConsentInput>(), call.ip)) }
}

private fun Route.subjectRoutes(s: Services) = route("/subjects") {
    get { call.respond(s.profile.subjects(call.accountId)) }
    post { call.respond(HttpStatusCode.Created, s.profile.createSubject(call.accountId, call.receive<SubjectInput>(), call.ip)) }
    patch("/{id}") {
        call.respond(s.profile.updateSubject(call.accountId, call.parameters["id"]!!, call.receive<SubjectInput>(), call.ip))
    }
    delete("/{id}") {
        s.profile.deleteSubject(call.accountId, call.parameters["id"]!!, call.ip)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/{id}/history") {
        val kind = call.request.queryParameters["kind"] ?: throw ApiException.badRequest("缺少 kind")
        call.respond(s.records.history(call.accountId, call.parameters["id"]!!, kind))
    }
}

private fun Route.recordRoutes(s: Services) = route("/records") {
    get {
        val q = call.request.queryParameters
        call.respond(s.records.list(call.accountId, q["subjectId"], q["kind"], q["before"]?.toLongOrNull(), q["beforeId"],
            q["limit"]?.toIntOrNull() ?: 50))
    }
    put("/{id}") {
        val (created, summary) = s.records.upload(call.accountId, call.parameters["id"]!!, call.receive<RecordUpload>(), call.ip)
        call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, summary)
    }
    get("/{id}") { call.respond(s.records.detail(call.accountId, call.parameters["id"]!!, call.ip)) }
    delete("/{id}") {
        s.records.delete(call.accountId, call.parameters["id"]!!, call.ip)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/{id}/compare") { call.respond(s.records.compare(call.accountId, call.parameters["id"]!!)) }
}
