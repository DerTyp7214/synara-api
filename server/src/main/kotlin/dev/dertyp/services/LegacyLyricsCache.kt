package dev.dertyp.services

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityType
import dev.dertyp.db.SongTable
import dev.dertyp.plugins.HookEvent
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.UUID

private const val LEGACY_LYRICS_CHUNK_SIZE = 5000
private const val LEGACY_LYRICS_MAX_ENTRIES = 20_000L
private val legacyLyricsTtl = Duration.ofMinutes(1)

class LegacyLyricsCache : EntityWriteSubscriber, HookSubscriber {
    private val cache: Cache<UUID, String> = Caffeine.newBuilder()
        .expireAfterWrite(legacyLyricsTtl)
        .maximumSize(LEGACY_LYRICS_MAX_ENTRIES)
        .build()

    override fun subscribe(hooks: HookService) {
        hooks.inTransaction(this)
        val handler: suspend (HookEvent) -> Unit = ::handle
        hooks.on(HookEvent.EntitiesUpdated::class, handler)
        hooks.on(HookEvent.EntitiesDeleted::class, handler)
        hooks.on(HookEvent.EntitiesMerged::class, handler)
    }

    override fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean) = invalidate(type, ids)

    override fun deleting(type: EntityType, ids: Collection<UUID>) = invalidate(type, ids)

    override fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) =
        invalidate(type, removedIds + keptId)

    private fun handle(event: HookEvent) {
        when (event) {
            is HookEvent.EntitiesUpdated -> invalidate(event.type, event.ids)
            is HookEvent.EntitiesDeleted -> invalidate(event.type, event.ids)
            is HookEvent.EntitiesMerged -> invalidate(event.type, event.removedIds + event.keptId)
            else -> {}
        }
    }

    private fun invalidate(type: EntityType, ids: Collection<UUID>) {
        if (type == EntityType.SONG) cache.invalidateAll(ids)
    }

    suspend fun prefetch(songIds: Collection<UUID>) {
        val missing = songIds.filter { cache.getIfPresent(it) == null }
        if (missing.isEmpty()) return
        val loaded = dbQuery {
            missing.chunked(LEGACY_LYRICS_CHUNK_SIZE).flatMap { chunk ->
                SongTable.select(SongTable.id, SongTable.lyrics)
                    .where { SongTable.id inList chunk }
                    .map { it[SongTable.id].value to it[SongTable.lyrics] }
            }.toMap()
        }
        missing.forEach { cache.put(it, loaded[it] ?: "") }
    }

    fun lyrics(songId: UUID): String = cache.get(songId) { id ->
        transaction {
            SongTable.select(SongTable.lyrics)
                .where { SongTable.id eq id }
                .singleOrNull()
                ?.get(SongTable.lyrics)
                ?: ""
        }
    }
}
