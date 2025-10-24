package dev.dertyp.core

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun ApplicationCall.getUsername(): String = principal<JWTPrincipal>()?.get("username")!!