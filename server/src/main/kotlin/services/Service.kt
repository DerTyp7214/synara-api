package dev.dertyp.services

import io.ktor.util.logging.*
import org.koin.core.component.KoinComponent

open class Service: KoinComponent {
    val maxBatchSize = 30000

    val logger = KtorSimpleLogger(this::class.simpleName!!)

    open suspend fun startService() {}
    open suspend fun stopService() {}
}