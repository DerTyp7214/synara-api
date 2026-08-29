package dev.dertyp.plugins

import dev.dertyp.services.ILrcLibService
import dev.dertyp.services.metadata.IMetadataService
import org.slf4j.Logger

interface PluginContext {
    val logger: Logger
    val storageService: IServerStorageService
    val indexer: IPluginIndexer
    val importService: IPluginImportService
    val songLibrary: SongLibrary
    val albumLibrary: AlbumLibrary
    val artistLibrary: ArtistLibrary
    val playlistLibrary: PlaylistLibrary
    val imageLibrary: ImageLibrary
    val metadataService: IMetadataService
    val lrcLibService: ILrcLibService
    val scheduleService: IScheduleService
    val hooks: HookBus
    val apiKeyScopes: ApiKeyScopeRegistrar
    val ui: UiRegistrar
    val settings: PluginSettings
    val i18n: TranslationRegistrar
}
