package dev.dertyp.services.soundcloud

import dev.dertyp.plugins.BaseIndexer
import dev.dertyp.plugins.PluginContext
import java.io.File
import java.nio.file.Path

class SoundcloudIndexer(context: PluginContext) : BaseIndexer(context) {
    override val id: String = "soundcloud"
    override val name: String = "SoundCloud Indexer"

    override fun canHandle(path: Path): Boolean {
        if (!super.canHandle(path)) return false
        val tracksPath = context.storageService.tracksPath ?: return true
        return path.toAbsolutePath().toString().startsWith(File(tracksPath).absolutePath)
    }
}
