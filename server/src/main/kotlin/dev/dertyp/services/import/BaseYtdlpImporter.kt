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

    private val ytdlpCookiesPath: String?
        get() = environment.config.propertyOrNull("ytdlp.cookies")?.getString()

    protected fun ytdlp(vararg args: String): MutableList<String> {
        val cmd = mutableListOf("yt-dlp")
        ytdlpCookiesPath?.let {
            if (Path(it).exists()) {
                cmd.add("--cookies")
                cmd.add(it)
            }
        }
        cmd.addAll(args)
        return cmd
    }
}
