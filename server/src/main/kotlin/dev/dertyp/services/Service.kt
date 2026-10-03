package dev.dertyp.services

import io.ktor.util.logging.*
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent

open class Service : KoinComponent {
    val maxBatchSize = 30000

    val logger = KtorSimpleLogger(this::class.simpleName!!)

    protected open val scopeDispatcher: CoroutineDispatcher get() = Dispatchers.IO

    private val scopeDelegate = lazy {
        ServiceLifecycle.register(this)
        CoroutineScope(SupervisorJob() + scopeDispatcher + CoroutineExceptionHandler { _, e ->
            logger.error("Uncaught exception in ${this::class.simpleName}", e)
        })
    }

    protected val scope: CoroutineScope by scopeDelegate

    open suspend fun startService() {}

    open suspend fun stopService() {
        if (scopeDelegate.isInitialized()) scope.cancel()
    }
}
