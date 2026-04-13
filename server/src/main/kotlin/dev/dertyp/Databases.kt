package dev.dertyp

import dev.dertyp.services.tdn.DownloadService
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.ktor.ext.getKoin

fun Application.configureDatabases() {
    CoroutineScope(Dispatchers.IO).launch {
        val downloadService = getKoin().get<DownloadService>()
        downloadService.startService()
    }
}
