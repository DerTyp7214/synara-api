package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.InsertableAlbum

interface IPluginAlbumService {
    suspend fun createBatch(albums: List<InsertableAlbum>): Map<PlatformUUID, Album>
}
