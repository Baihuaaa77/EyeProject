package com.zkyjss.eyeproject.server

import com.zkyjss.eyeproject.core.sync.ErrorCodes
import io.ktor.http.HttpStatusCode

/** 业务错误：code 为稳定代码（见 ErrorCodes），message 为面向用户的中文说明。 */
class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = ApiException(HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST, message)
        /** 越权与不存在一律 404，不泄露别人的资源是否存在。 */
        fun notFound(what: String) = ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "${what}不存在")
        fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, ErrorCodes.INVALID_TOKEN, "登录已失效，请重新登录")
        fun consentRequired(message: String) = ApiException(HttpStatusCode.Forbidden, ErrorCodes.CONSENT_REQUIRED, message)
    }
}
