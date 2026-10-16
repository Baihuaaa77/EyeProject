package com.zkyjss.eyeproject.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** 连接池 + 迁移 + 事务。SQL 全部参数化，不拼接用户输入。 */
class Database(config: ServerConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = 10
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })

    init {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    }

    fun <T> tx(block: Connection.() -> T): T = dataSource.connection.use { connection ->
        try {
            connection.block().also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    /** JDBC 是阻塞调用，统一切到 IO 线程池。 */
    suspend fun <T> transaction(block: Connection.() -> T): T = withContext(Dispatchers.IO) { tx(block) }

    override fun close() = dataSource.close()
}

private fun PreparedStatement.bind(args: Array<out Any?>) = args.forEachIndexed { index, value ->
    when (value) {
        null -> setObject(index + 1, null)
        is ByteArray -> setBytes(index + 1, value)
        else -> setObject(index + 1, value)
    }
}

fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { it.bind(args); it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(args)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }

fun <T> Connection.single(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()

fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
