package dev.dertyp.listenbackup

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID
import javax.sql.DataSource

fun main(args: Array<String>) {
    EngineMain.main(args)
}

private val logger = LoggerFactory.getLogger("ListenBackup")

fun createDataSource(config: ApplicationConfig): DataSource {
    val driver = config.property("storage.driverClassName").getString()
    val hikari = HikariConfig().apply {
        jdbcUrl = config.property("storage.jdbcURL").getString()
        driverClassName = driver
        if (driver == "org.sqlite.JDBC") {
            maximumPoolSize = 1
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("busy_timeout", "5000")
        } else {
            maximumPoolSize = 10
            username = config.property("storage.user").getString()
            password = config.property("storage.password").getString()
        }
    }
    return HikariDataSource(hikari)
}

fun Application.module() {
    val store = ListenBackupStore(createDataSource(environment.config))
    val key = environment.config.propertyOrNull("backup.key")?.getString()?.ifBlank { null }
    if (key == null) logger.warn("BACKUP_KEY is not set; accepting unauthenticated uploads")
    backupModule(store, key)
}

fun Application.backupModule(store: ListenBackupStore, backupKey: String?) {
    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.cause?.message ?: cause.message ?: "Bad request")))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Internal error")))
        }
    }

    routing {
        get(ListenBackupProtocol.HEALTH_PATH) {
            call.respond(ListenBackupHealth(listenCount = store.count()))
        }

        get(ListenBackupProtocol.STATUS_PATH) {
            if (!call.authorized(backupKey)) return@get
            val serverId = call.request.queryParameters[ListenBackupProtocol.SERVER_ID_PARAM]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            call.respond(store.status(serverId))
        }

        post(ListenBackupProtocol.LISTENS_PATH) {
            if (!call.authorized(backupKey)) return@post
            val batch = call.receive<ListenBackupBatch>()
            val received = store.upsert(batch)
            logger.info("Received {} listen(s) from server {}", received, batch.serverId)
            call.respond(ListenBackupBatchResult(received))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.authorized(backupKey: String?): Boolean {
    if (backupKey.isNullOrEmpty()) return true
    val provided = request.headers[ListenBackupProtocol.KEY_HEADER]
    if (provided == backupKey) return true
    logger.warn("Rejected request: invalid or missing API key")
    respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing API key"))
    return false
}
