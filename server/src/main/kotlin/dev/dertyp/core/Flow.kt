@file:JvmName("ServerFlow")

package dev.dertyp.core

import dev.dertyp.data.User
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.IMetadataService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<IMetadataService.Track>.filterExisting(
    songService: SongService,
    user: User,
    chunkSize: Int = 20,
    deduplicateByIsrc: Boolean = true,
    existingCallback: suspend (List<Pair<Long, UUID>>) -> Unit = {}
): Flow<List<IMetadataService.Track>> {
    return chunked(20)
        .map { tracks ->
            val existingSongs = songService.byOriginalIds(
                tracks.map { it.id },
                user.id
            )
            val isrcs = if (deduplicateByIsrc) {
                tracks.mapNotNull { it.isrc }.filter { it.isNotBlank() }
            } else emptyList()
            val existingSongsByIsrc = if (isrcs.isNotEmpty()) {
                songService.byOriginalTracks(tracks, user.id)
            } else emptyList()

            val allExistingSongs = (existingSongs + existingSongsByIsrc).distinctBy { it.id }
            val existingUrls = allExistingSongs.map { track -> track.originalUrl }
            val existingIsrcs = if (deduplicateByIsrc) allExistingSongs.mapNotNull { it.isrc } else emptyList()

            val songIds = tracks.map { track ->
                track.addedAt?.toInstant()
                    ?.toEpochMilli() to allExistingSongs.find {
                    it.originalUrl.endsWith("/${track.id}") ||
                        (deduplicateByIsrc && track.isrc?.isNotBlank() == true && it.isrc == track.isrc)
                }?.id
            }.filterNotNull()

            existingCallback(songIds)

            tracks.filter { track ->
                existingUrls.none { url ->
                    url.endsWith("/${track.id}")
                } && (!deduplicateByIsrc || track.isrc.isNullOrBlank() || existingIsrcs.none { it == track.isrc })
            }.asFlow()
        }
        .flattenConcat()
        .chunked(chunkSize)
}