package dev.dertyp

import dev.dertyp.services.JwtService
import dev.dertyp.services.UserService
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import org.jetbrains.exposed.sql.Database

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val dbPath = environment.config.propertyOrNull("sqlite.path")?.getString() ?: "./data.db"
    val database = Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")

    val userService = UserService(database, environment)
    val jwtService = JwtService(environment, userService)

    install(CallLogging)

    configureHTTP(jwtService)
    configureRouting(jwtService)
    configureDatabases(database, jwtService)
}