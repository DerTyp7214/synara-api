package dev.dertyp.db

import dev.dertyp.services.models.LyricsMatch
import dev.dertyp.services.models.LyricsSyncLevel
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

enum class LyricsCandidateStatus { FOUND, MISS }

object LyricsCandidateTable : UUIDTable("lyrics_candidate") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val provider = text("provider")
    val status = enumerationByName("status", 16, LyricsCandidateStatus::class)
    val syncLevel = enumerationByName("sync_level", 16, LyricsSyncLevel::class).default(LyricsSyncLevel.PLAIN)
    val instrumental = bool("instrumental").default(false)
    val match = enumerationByName("match", 16, LyricsMatch::class)
    val durationGapSec = integer("duration_gap_sec").nullable()
    val rank = integer("rank").default(0)
    val content = binary("content").nullable()
    val plain = text("plain").nullable()
    val fetchedAt = long("fetched_at")

    init {
        index(false, songId)
        uniqueIndex(songId, provider)
    }
}
