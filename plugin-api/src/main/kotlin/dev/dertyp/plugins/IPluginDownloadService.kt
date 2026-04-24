package dev.dertyp.plugins

import dev.dertyp.services.download.DownloadQueueEntry

interface IPluginDownloadService {
    suspend fun addToQueue(vararg downloadEntries: DownloadQueueEntry)
}
