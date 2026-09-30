package dev.dertyp.services.import

import dev.dertyp.config.ServerConfig
import dev.dertyp.core.process.ExternalTool
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import org.koin.core.component.inject
import kotlin.io.path.Path
import kotlin.io.path.exists

abstract class BaseYtdlpImporter(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : BaseImporter(indexer, storageService) {
    private val serverConfig by inject<ServerConfig>()

    override val tool = ExternalTool("yt-dlp", invalidCommandMessage = "Invalid command")

    private val ytdlpConfigPath: String?
        get() = serverConfig.importers.ytdlp.configPath

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
