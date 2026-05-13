package dev.dertyp.services.import

import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import io.ktor.server.application.ApplicationEnvironment
import org.koin.core.component.inject
import kotlin.io.path.Path
import kotlin.io.path.exists

abstract class BaseYtdlpImporter(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : BaseImporter(indexer, storageService) {
    private val environment by inject<ApplicationEnvironment>()

    private val ytdlpConfigPath: String?
        get() = environment.config.propertyOrNull("ytdlp.config")?.getString()

    protected fun ytdlp(vararg args: String): MutableList<String> {
        val cmd = mutableListOf("yt-dlp")
        ytdlpConfigPath?.let {
            if (Path(it).exists()) {
                cmd.add("--config-location")
                cmd.add(it)
            }
        }
        cmd.addAll(args)
        return cmd
    }
}
