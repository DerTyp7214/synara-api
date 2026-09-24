package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagAction
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
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

        fun playbackTags(userId: UUID, songIds: Collection<UUID>): Map<UUID, List<TimecodeTag>> = songIds
            .distinct()
            .chunked(1000)
            .flatMap { ids ->
                TimecodeTagTable
                    .selectAll()
                    .where { TimecodeTagTable.userId eq userId }
                    .andWhere { TimecodeTagTable.songId inList ids }
                    .andWhere { TimecodeTagTable.action neq TimecodeTagAction.NONE }
                    .orderBy(
                        TimecodeTagTable.timestampMs to SortOrder.ASC,
                        TimecodeTagTable.createdAt to SortOrder.ASC,
                        TimecodeTagTable.id to SortOrder.ASC
                    )
                    .map(::mapRow)
            }
            .groupBy { it.songId }

        private fun mapRow(row: ResultRow): TimecodeTag = TimecodeTag(
            id = row[TimecodeTagTable.id].value,
            userId = row[TimecodeTagTable.userId].value,
            songId = row[TimecodeTagTable.songId].value,
            type = row[TimecodeTagTable.type],
            text = row[TimecodeTagTable.text],
            timestampMs = row[TimecodeTagTable.timestampMs],
            endMs = row[TimecodeTagTable.endMs],
            createdAt = row[TimecodeTagTable.createdAt],
            updatedAt = row[TimecodeTagTable.updatedAt],
            action = row[TimecodeTagTable.action],
            fade = row[TimecodeTagTable.fade]
        )
    }

    private val locks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun createTag(
        userId: UUID,
        songId: UUID,
        type: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?,
        action: TimecodeTagAction = TimecodeTagAction.NONE,
        fade: Boolean = false
    ): TimecodeTag {
        validate(text, timestampMs, endMs)
        validateAction(type, endMs, action)

        return lock(userId).withLock {
            dbQuery {
                requireSong(songId)
                require(ownTags(userId, songId).count() < MAX_TAGS_PER_SONG) {
                    "A song holds at most $MAX_TAGS_PER_SONG tags"
                }

                val now = Instant.now().toEpochMilli()
                val id = insertTag(userId, songId, type, text, timestampMs, endMs, action, fade, now)

                TimecodeTag(
                    id = id,
                    userId = userId,
                    songId = songId,
                    type = type,
                    text = text,
                    timestampMs = timestampMs,
                    endMs = endMs,
                    createdAt = now,
                    updatedAt = now,
                    action = action,
                    fade = fade
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
        endMs: Long?,
        action: TimecodeTagAction? = null,
        fade: Boolean? = null
    ): TimecodeTag {
        validate(text, timestampMs, endMs)

        return dbQuery {
            val stored = TimecodeTagTable
                .select(TimecodeTagTable.action, TimecodeTagTable.fade)
                .where { TimecodeTagTable.id eq tagId }
                .andWhere { TimecodeTagTable.userId eq userId }
                .singleOrNull()
            require(stored != null) { "Timecode tag $tagId not found" }

            val effectiveAction = action ?: stored[TimecodeTagTable.action]
            val effectiveFade = fade ?: stored[TimecodeTagTable.fade]
            validateAction(type, endMs, effectiveAction)

            val updated = writeTag(
                userId, tagId, type, text, timestampMs, endMs, effectiveAction, effectiveFade, Instant.now().toEpochMilli()
            )
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
        tags.forEach { tag ->
            validate(tag.text, tag.timestampMs, tag.endMs)
            validateAction(tag.type, tag.endMs, tag.action)
        }

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
                    this[TimecodeTagTable.action] = tag.action
                    this[TimecodeTagTable.fade] = tag.fade
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

    private fun validateAction(type: TimecodeTagType, endMs: Long?, action: TimecodeTagAction) {
        when (action) {
            TimecodeTagAction.NONE -> Unit
            TimecodeTagAction.PLAY_ONLY, TimecodeTagAction.SKIP -> {
                require(type == TimecodeTagType.CHAPTER) { "The action $action is only available on chapters" }
                require(endMs != null) { "The action $action needs a chapter with an end position" }
            }
            TimecodeTagAction.SKIP_TO, TimecodeTagAction.PLAY_UNTIL -> {
                require(type == TimecodeTagType.MARKER) { "The action $action is only available on markers" }
                require(endMs == null) { "The action $action needs a marker without an end position" }
            }
        }
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
        tagAction: TimecodeTagAction,
        tagFade: Boolean,
        at: Long
    ): UUID = TimecodeTagTable.insertAndGetId {
        it[TimecodeTagTable.userId] = owner
        it[TimecodeTagTable.songId] = song
        it[type] = tagType
        it[text] = content
        it[timestampMs] = start
        it[endMs] = end
        it[action] = tagAction
        it[fade] = tagFade
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
        tagAction: TimecodeTagAction,
        tagFade: Boolean,
        at: Long
    ): Int = TimecodeTagTable.update({
        (TimecodeTagTable.id eq tag) and (TimecodeTagTable.userId eq owner)
    }) {
        it[type] = tagType
        it[text] = content
        it[timestampMs] = start
        it[endMs] = end
        it[action] = tagAction
        it[fade] = tagFade
        it[updatedAt] = at
    }

    private fun removeTag(owner: UUID, tag: UUID): Int = TimecodeTagTable.deleteWhere {
        (TimecodeTagTable.id eq tag) and (TimecodeTagTable.userId eq owner)
    }

    private fun clearTags(owner: UUID, song: UUID): Int = TimecodeTagTable.deleteWhere {
        (TimecodeTagTable.userId eq owner) and (TimecodeTagTable.songId eq song)
    }
}
