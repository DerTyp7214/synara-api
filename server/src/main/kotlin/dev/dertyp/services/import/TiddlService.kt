package dev.dertyp.services.import

import dev.dertyp.core.process.ExternalTool
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.utils.parsers.ParserFactory
import java.io.File
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
open class TiddlService(
    indexer: IPluginIndexer,
    storageService: IServerStorageService
) : TidalBaseImporter(indexer, storageService) {
    override val id: String = ID
    override val tool = ExternalTool("tiddl", pythonWrapped = true)
    override val installed: Boolean get() = tool.installed
    override val enabled: Boolean get() = installed && tokenFileExists()

    override val loginCommand: MutableList<String> = mutableListOf("tiddl", "auth", "login", "--no-browser")
    override val importCommand: MutableList<String> = mutableListOf("tiddl", "download", "url")
    override val favImportCommand: MutableList<String> = mutableListOf("tiddl", "download", "fav", "--types")

    companion object {
        val ID = ImportBackend.Tiddl.id
    }

    override fun authorizedCheck(result: ProcessExecutionResult) = result.fullOutput.contains("Already logged in.")

    override fun canHandle(url: String): Boolean {
        return ParserFactory.getParserForProvider("tidal")?.canHandle(url) ?: false
    }

    override fun parseFavType(favType: ImportFavType): String = when (favType) {
        ImportFavType.tracks -> "track"
        ImportFavType.artists -> "artist"
        ImportFavType.albums -> "album"
        ImportFavType.videos -> "video"
    }

    override val credentialName: String = CredentialNames.IMPORTER_TIDDL

    override fun credentialTargets(): Map<String, File> = mapOf(CredentialFileRoles.TIDDL_AUTH to authFile())

    private fun authFile(): File = File(System.getProperty("user.home"), ".tiddl/auth.json")

    override fun tokenFileExists(): Boolean = credentialPresent(authFile())
}
