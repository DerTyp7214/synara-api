package dev.dertyp

import dev.dertyp.services.JwtService
import dev.dertyp.services.RefreshTokenService
import dev.dertyp.services.UserService
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import org.jetbrains.exposed.sql.Database

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val database = getDatabase(environment)

    val userService = UserService(database, environment)
    val refreshTokenService = RefreshTokenService(database)
    val jwtService = JwtService(environment, userService, refreshTokenService)

    install(CallLogging)

    configureHTTP(jwtService)
    configureRouting(jwtService)
    configureDatabases(database, jwtService)
}

fun getDatabase(environment: ApplicationEnvironment): Database {
    val dbDriver = environment.config.property("storage.driverClassName").getString()
    val dbUrl = environment.config.property("storage.jdbcURL").getString()
    val dbUser = environment.config.property("storage.user").getString()
    val dbPassword = environment.config.property("storage.password").getString()

    return when (dbDriver) {
        "org.sqlite.JDBC" -> Database.connect(dbUrl, dbDriver)
        else -> Database.connect(
            url = dbUrl,
            driver = dbDriver,
            user = dbUser,
            password = dbPassword
        )
    }
}