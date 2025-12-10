package dev.dertyp.services

import io.ktor.util.logging.*

open class Service {
    val maxBatchSize = 30000

    val logger = KtorSimpleLogger(this::class.simpleName!!)
}