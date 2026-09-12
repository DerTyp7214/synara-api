package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagInput
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.db.SongTable
import dev.dertyp.db.TimecodeTagTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TimecodeTagService : Service() {
    companion object {
        const val MAX_TEXT_LENGTH = 1000
        const val MAX_TAGS_PER_SONG = 500
        const val MAX_PAGE = 500
    }

    private val locks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun createTag(
        userId: UUID,
        songId: UUID,
        type: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?
    ): TimecodeTag {
        validate(text, timestampMs, endMs)

        return lock(userId).withLock {
            dbQuery {
                requireSong(songId)
                require(ownTags(userId, songId).count() < MAX_TAGS_PER_SONG) {
                    "A song holds at most $MAX_TAGS_PER_SONG tags"
                }

                val now = Instant.now().toEpochMilli()
                val id = insertTag(userId, songId, type, text, timestampMs, endMs, now)

                TimecodeTag(
                    id = id,
                    userId = userId,
                    songId = songId,
                    type = type,
                    text = text,
                    timestampMs = timestampMs,
                    endMs = endMs,
                    createdAt = now,
                    updatedAt = now
                )
            }
        }
    }

    suspend fun getTags(userId: UUID, songId: UUID): List<TimecodeTag> = dbQuery {
        orderedTags(userId, songId)
    }

    suspend fun updateTag(
        userId: UUID,
        tagId: UUID,
        type: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?
    ): TimecodeTag {
        validate(text, timestampMs, endMs)

        return dbQuery {
            val updated = writeTag(userId, tagId, type, text, timestampMs, endMs, Instant.now().toEpochMilli())
            require(updated == 1) { "Timecode tag $tagId not found" }

            TimecodeTagTable
                .selectAll()
                .where { TimecodeTagTable.id eq tagId }
                .single()
                .let(::mapRow)
        }
    }

    suspend fun deleteTag(userId: UUID, tagId: UUID): Boolean = dbQuery { removeTag(userId, tagId) > 0 }

    suspend fun replaceTags(userId: UUID, songId: UUID, tags: List<TimecodeTagInput>): List<TimecodeTag> {
        require(tags.size <= MAX_TAGS_PER_SONG) { "A song holds at most $MAX_TAGS_PER_SONG tags" }
        tags.forEach { tag -> validate(tag.text, tag.timestampMs, tag.endMs) }

        return lock(userId).withLock {
            dbQuery {
                requireSong(songId)
                clearTags(userId, songId)

                val now = Instant.now().toEpochMilli()
                TimecodeTagTable.batchInsert(tags) { tag ->
                    this[TimecodeTagTable.userId] = userId
                    this[TimecodeTagTable.songId] = songId
                    this[TimecodeTagTable.type] = tag.type
                    this[TimecodeTagTable.text] = tag.text
                    this[TimecodeTagTable.timestampMs] = tag.timestampMs
                    this[TimecodeTagTable.endMs] = tag.endMs
                    this[TimecodeTagTable.createdAt] = now
                    this[TimecodeTagTable.updatedAt] = now
                }

                orderedTags(userId, songId)
            }
        }
    }

    suspend fun listTags(
        userId: UUID,
        type: TimecodeTagType?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<TimecodeTag> {
        val size = pageSize.coerceIn(1, MAX_PAGE)
        return dbQuery {
            val total = scopedTags(userId, type).count().toInt()
            val rows = scopedTags(userId, type)
                .orderBy(TimecodeTagTable.createdAt to SortOrder.DESC, TimecodeTagTable.id to SortOrder.ASC)
                .paging(page, size)
                .map(::mapRow)

            PaginatedResponse(
                data = rows,
                page = page,
                total = total,
                pageSize = size,
                hasNextPage = (page + 1).toLong() * size < total
            )
        }
    }

    private fun validate(text: String, timestampMs: Long, endMs: Long?) {
        require(timestampMs >= 0) { "A timecode tag position must not be negative" }
        require(endMs == null || endMs >= timestampMs) { "A timecode tag must not end before it starts" }
        require(text.length <= MAX_TEXT_LENGTH) { "The text of a timecode tag is at most $MAX_TEXT_LENGTH characters long" }
    }

    private fun requireSong(songId: UUID) {
        val exists = SongTable
            .select(SongTable.id)
            .where { SongTable.id eq songId }
            .limit(1)
            .any()

        require(exists) { "Song $songId does not exist" }
    }

    private fun lock(userId: UUID): Mutex = locks.computeIfAbsent(userId) { Mutex() }

    private fun ownTags(owner: UUID, song: UUID): Query = TimecodeTagTable
        .selectAll()
        .where { TimecodeTagTable.userId eq owner }
        .andWhere { TimecodeTagTable.songId eq song }

    private fun scopedTags(owner: UUID, tagType: TimecodeTagType?): Query {
        val query = TimecodeTagTable
            .selectAll()
            .where { TimecodeTagTable.userId eq owner }

        if (tagType != null) query.andWhere { TimecodeTagTable.type eq tagType }

        return query
    }

    private fun orderedTags(owner: UUID, song: UUID): List<TimecodeTag> = ownTags(owner, song)
        .orderBy(
            TimecodeTagTable.timestampMs to SortOrder.ASC,
            TimecodeTagTable.createdAt to SortOrder.ASC,
            TimecodeTagTable.id to SortOrder.ASC
        )
        .map(::mapRow)

    private fun insertTag(
        owner: UUID,
        song: UUID,
        tagType: TimecodeTagType,
        content: String,
        start: Long,
        end: Long?,
        at: Long
    ): UUID = TimecodeTagTable.insertAndGetId {
        it[TimecodeTagTable.userId] = owner
        it[TimecodeTagTable.songId] = song
        it[type] = tagType
        it[text] = content
        it[timestampMs] = start
        it[endMs] = end
        it[createdAt] = at
        it[updatedAt] = at
    }.value

    private fun writeTag(
        owner: UUID,
        tag: UUID,
        tagType: TimecodeTagType,
        content: String,
        start: Long,
        end: Long?,
        at: Long
    ): Int = TimecodeTagTable.update({
        (TimecodeTagTable.id eq tag) and (TimecodeTagTable.userId eq owner)
    }) {
        it[type] = tagType
        it[text] = content
        it[timestampMs] = start
        it[endMs] = end
        it[updatedAt] = at
    }

    private fun removeTag(owner: UUID, tag: UUID): Int = TimecodeTagTable.deleteWhere {
        (TimecodeTagTable.id eq tag) and (TimecodeTagTable.userId eq owner)
    }

    private fun clearTags(owner: UUID, song: UUID): Int = TimecodeTagTable.deleteWhere {
        (TimecodeTagTable.userId eq owner) and (TimecodeTagTable.songId eq song)
    }

    private fun mapRow(row: ResultRow): TimecodeTag = TimecodeTag(
        id = row[TimecodeTagTable.id].value,
        userId = row[TimecodeTagTable.userId].value,
        songId = row[TimecodeTagTable.songId].value,
        type = row[TimecodeTagTable.type],
        text = row[TimecodeTagTable.text],
        timestampMs = row[TimecodeTagTable.timestampMs],
        endMs = row[TimecodeTagTable.endMs],
        createdAt = row[TimecodeTagTable.createdAt],
        updatedAt = row[TimecodeTagTable.updatedAt]
    )
}
