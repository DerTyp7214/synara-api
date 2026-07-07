package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.db.ListenSource
import dev.dertyp.db.ListenTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.koin.core.component.inject

data class IncomingListen(
    val songId: PlatformUUID,
    val listenedAtMs: Long,
    val msPlayed: Long? = null,
)

class ListenService : Service() {
    private val hooks by inject<HookBus>()

    suspend fun ingestListenBrainz(listenBrainzUserId: PlatformUUID, listens: List<IncomingListen>): Int {
        if (listens.isEmpty()) return 0

        dbQuery {
            ListenTable.batchInsert(listens, ignore = true) { listen ->
                this[ListenTable.listenBrainzUserId] = listenBrainzUserId
                this[ListenTable.songId] = listen.songId
                this[ListenTable.listenedAt] = listen.listenedAtMs
                this[ListenTable.listenSource] = ListenSource.LISTENBRAINZ
                this[ListenTable.msPlayed] = listen.msPlayed
            }
        }

        hooks.emit(HookEvent.ListenIngested(listenBrainzUserId, listens.size))
        return listens.size
    }
}
