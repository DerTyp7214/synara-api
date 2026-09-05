package dev.dertyp.services.intake

import dev.dertyp.data.UserCapability
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.IntakeOffer
import dev.dertyp.plugins.IntakeReceipt
import dev.dertyp.plugins.IntakeResolver
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.UiAccess
import dev.dertyp.services.UserService
import dev.dertyp.services.import.ImportBackend
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.import.Type
import dev.dertyp.services.import.UrlImportQueueEntry
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiIntakeCodeKind
import dev.dertyp.ui.UiPortals
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.flow.asFlow

private sealed interface Target {
    data class Url(val url: String) : Target
    data class Ids(val ids: List<String>, val type: Type) : Target
}

class ImporterResolvers(
    private val pluginManager: PluginManager,
    private val importerProxy: ImporterProxy,
    private val importService: ImportService,
    private val linkResolver: LinkResolverService,
    private val userService: UserService,
    private val environment: ApplicationEnvironment,
) {
    fun register(intakeService: IntakeService) {
        intakeService.registerProvider(UiRegistry.SERVER_SOURCE) { resolvers() }
    }

    fun resolvers(): List<IntakeResolver> =
        pluginManager.getAllImporters().filter { it.enabled }.map { ImporterResolver(it) } + SearchResolver

    private fun isDefault(importer: IImporter) = importer.id == importerProxy.defaultService.id

    inner class ImporterResolver(private val importer: IImporter) : IntakeResolver {
        override val id = "import.${importer.id}"
        override val titleKey = "intake.import.title"
        override val titleArgs = mapOf("name" to importer.name)
        override val descriptionKey = "intake.import.description"
        override val icon = UiIcon(UiIconName.IMPORT)
        override val jobKind = ImportService.JOB_KIND
        override val access = UiAccess(capabilities = setOf(UserCapability.IMPORT))

        private suspend fun target(item: IntakeItem): Target? = when (item) {
            is IntakeItem.Url -> importerProxy.resolveImporter(item.url)?.takeIf { it.first.id == importer.id }?.let { Target.Url(it.second) }
            is IntakeItem.Id -> if (item.provider == importer.id || (item.provider.isBlank() && isDefault(importer))) Target.Ids(listOf(item.id), item.contentType ?: Type.SONG) else null
            is IntakeItem.Code -> code(item)
            is IntakeItem.Text, is IntakeItem.File -> null
        }

        private suspend fun code(item: IntakeItem.Code): Target? {
            val isrc = item.value.takeIf { item.kind == UiIntakeCodeKind.ISRC }
            val upc = item.value.takeIf { item.kind == UiIntakeCodeKind.UPC }
            if (linkResolver.enabled) {
                val (resolvedImporter, url) = importerProxy.resolveImporterByCode(isrc = isrc, upc = upc) ?: return null
                if (resolvedImporter.id != importer.id) return null
                val parsed = importer.parseUrl(url)
                return if (parsed != null) Target.Ids(listOf(parsed.first), parsed.second ?: Type.SONG) else Target.Url(url)
            }
            if (!isDefault(importer)) return null
            val metadataType = importer.metadataType ?: return null
            val metadata = MetadataService.getMetadataService(metadataType, environment)
            return if (isrc != null) metadata.getTrackByIsrc(isrc)?.id?.let { Target.Ids(listOf(it), Type.SONG) }
            else metadata.getAlbumByBarcode(upc!!)?.id?.let { Target.Ids(listOf(it), Type.ALBUM) }
        }

        override suspend fun offer(items: List<IntakeItem>, user: UserInfo): IntakeOffer? {
            val targets = items.mapNotNull { item -> target(item)?.let { item to it } }
            if (targets.isEmpty()) return null
            return IntakeOffer(
                accepted = targets.map { it.first },
                titleArgs = titleArgs,
                icon = icon,
                priority = if (isDefault(importer)) 1 else 0,
                submit = { submit(targets.map { it.second }, user) },
            )
        }

        private suspend fun submit(targets: List<Target>, user: UserInfo): IntakeReceipt {
            val account = userService.findUserById(user.id) ?: throw IllegalStateException("Unknown user ${user.id}")
            val urls = mutableListOf<String>()
            val ids = mutableMapOf<Type, MutableList<String>>()
            targets.forEach { target ->
                when (target) {
                    is Target.Ids -> ids.getOrPut(target.type) { mutableListOf() }.addAll(target.ids)
                    is Target.Url -> {
                        val parsed = importer.parseUrl(target.url)
                        if (parsed != null) ids.getOrPut(parsed.second ?: Type.SONG) { mutableListOf() }.add(parsed.first)
                        else urls += target.url
                    }
                }
            }
            ids.forEach { (type, list) -> importService.importIds(list.asFlow(), type, account, importer.id) }
            if (urls.isNotEmpty()) {
                importService.addToQueue(UrlImportQueueEntry(urls = urls, byUser = account.id, importer = ImportBackend(importer.id)))
            }
            return IntakeReceipt(accepted = targets.size, messageKey = "importer.queued")
        }
    }

    object SearchResolver : IntakeResolver {
        override val id = "search.external"
        override val titleKey = "importer.hook.search"
        override val descriptionKey = "importer.hook.searchDescription"
        override val icon = UiIcon(UiIconName.SEARCH)
        override val jobKind = "search"

        override suspend fun offer(items: List<IntakeItem>, user: UserInfo): IntakeOffer? {
            val texts = items.filterIsInstance<IntakeItem.Text>()
            if (texts.isEmpty()) return null
            return IntakeOffer(
                accepted = texts,
                icon = icon,
                priority = -1,
                action = UiAction.OpenNative(UiPortals.EXTERNAL_SEARCH, mapOf("query" to texts.joinToString(" ") { it.text })),
            )
        }
    }
}
