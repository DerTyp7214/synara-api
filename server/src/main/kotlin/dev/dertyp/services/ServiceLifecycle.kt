package dev.dertyp.services

import dev.dertyp.core.ApplicationScope
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

open class ServiceRegistry {
    private val logger = KtorSimpleLogger("ServiceLifecycle")
    private val entries = mutableListOf<WeakReference<Any>>()

    fun register(service: Service) = add(service)

    fun register(resource: AutoCloseable) = add(resource)

    private fun add(target: Any) {
        synchronized(entries) {
            entries.removeAll { it.get() == null }
            if (entries.none { it.get() === target }) entries += WeakReference(target)
        }
    }

    fun start(service: Service): Job {
        register(service)
        return ApplicationScope.scope.launch(Dispatchers.IO) {
            try {
                service.startService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                service.logger.error("Failed to start ${service::class.simpleName}", e)
            }
        }
    }

    fun registered(): List<Service> = registeredTargets().filterIsInstance<Service>()

    private fun registeredTargets(): List<Any> = synchronized(entries) { entries.mapNotNull { it.get() } }

    suspend fun stopAll() {
        registeredTargets().asReversed().forEach { target ->
            val name = target::class.simpleName
            try {
                val stopped = withTimeoutOrNull(STOP_TIMEOUT) {
                    stop(target)
                    true
                }
                if (stopped == null) logger.warn("Stopping $name timed out after $STOP_TIMEOUT, continuing with the remaining services")
            } catch (e: Exception) {
                logger.error("Failed to stop $name", e)
            }
        }
    }

    private suspend fun stop(target: Any) {
        when (target) {
            is Service -> target.stopService()
            is AutoCloseable -> runInterruptible(Dispatchers.IO) { target.close() }
        }
    }

    companion object {
        val STOP_TIMEOUT = 10.seconds
    }
}

object ServiceLifecycle : ServiceRegistry()
