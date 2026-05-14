package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.services.ReverseProxyService
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

class ReverseProxyWorker : Worker("ReverseProxyWorker") {
    private val reverseProxyService by inject<ReverseProxyService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        if (!reverseProxyService.isConfigured) {
            return mapOf("restarted" to 0, "configured" to 0)
        }

        if (!reverseProxyService.isRunning) {
            logger.info("Reverse proxy service is configured but not running, starting it...")
            onProgress(0.0, "Starting reverse proxy service...")
            ApplicationScope.scope.launch {
                reverseProxyService.startService()
            }
            return mapOf("restarted" to 0, "started" to 1)
        }

        if (reverseProxyService.isConnected) {
            val lastInteraction = reverseProxyService.lastInteraction
            if (lastInteraction != null) {
                val elapsed = lastInteraction.elapsedNow()
                if (elapsed > 30.seconds) {
                    logger.warn("Reverse proxy connection appears hung (no interaction for $elapsed). Restarting...")
                    onProgress(0.0, "Restarting hung reverse proxy connection...")
                    reverseProxyService.restartService()
                    return mapOf("restarted" to 1, "reason" to 1)
                }
            }
            return mapOf("restarted" to 0)
        } else {
            logger.info("Reverse proxy not connected, restarting connection loop...")
            onProgress(0.0, "Restarting reverse proxy service...")
            reverseProxyService.restartService()
            onProgress(1.0, "Reverse proxy service restart signal sent")
            return mapOf("restarted" to 1, "reason" to 0)
        }
    }
}
