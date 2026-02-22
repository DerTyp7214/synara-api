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
    existingCallback: suspend (List<Pair<Long, UUID>>) -> Unit = {}
): Flow<List<IMetadataService.Track>> {
    return chunked(20)
        .map { tracks ->
            val existingSongs = songService.byTidalTrackIds(
                tracks.map { it.id },
                user.id
            )
            val existingUrls = existingSongs.map { track -> track.originalUrl }

            val songIds = tracks.map { track ->
                track.addedAt?.toInstant()
                    ?.toEpochMilli() to existingSongs.find { it.originalUrl.endsWith("/${track.id}") }?.id
            }.filterNotNull()

            existingCallback(songIds)

            tracks.filter { track ->
                existingUrls.none { url ->
                    url.endsWith("/${track.id}")
                }
            }.asFlow()
        }
        .flattenConcat()
        .chunked(20)
}