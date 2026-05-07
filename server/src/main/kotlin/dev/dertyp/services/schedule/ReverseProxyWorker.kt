package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.services.ReverseProxyService
import kotlinx.coroutines.launch
import org.koin.core.component.inject

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

        return if (!reverseProxyService.isConnected) {
            logger.info("Reverse proxy not connected, restarting connection loop...")
            onProgress(0.0, "Restarting reverse proxy service...")
            reverseProxyService.restartService()
            onProgress(1.0, "Reverse proxy service restart signal sent")
            mapOf("restarted" to 1)
        } else {
            mapOf("restarted" to 0)
        }
    }
}
