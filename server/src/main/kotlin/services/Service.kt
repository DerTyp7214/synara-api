package dev.dertyp.services

import io.ktor.util.logging.*

open class Service {
    val logger = KtorSimpleLogger(this::class.simpleName!!)
}