package dev.dertyp.plugins

import dev.dertyp.services.import.ImportQueueEntry

interface IPluginImportService {
    suspend fun addToQueue(vararg importEntries: ImportQueueEntry)
}
