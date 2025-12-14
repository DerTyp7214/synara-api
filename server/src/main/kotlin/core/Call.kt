package dev.dertyp.core

import dev.dertyp.data.User
import dev.dertyp.services.UserService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.koin.ktor.ext.get

fun ApplicationCall.getUsername(): String = principal<JWTPrincipal>()?.get("username")!!
suspend fun ApplicationCall.getUser(): User? = get<UserService>().findUserByUsername(getUsername())