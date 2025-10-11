package dev.dertyp.services

import io.ktor.util.logging.*

open class Service {
    protected val logger = KtorSimpleLogger(this::class.qualifiedName!!)
}