package dev.dertyp.services

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.TimecodeTag
import dev.dertyp.data.TimecodeTagInput
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.data.User
import dev.dertyp.utils.LogParam
import java.util.UUID

class RpcTimecodeTagService(
    private val user: User,
    private val timecodeTagService: TimecodeTagService
) : ITimecodeTagService {
    override suspend fun createTag(
        songId: UUID,
        tagType: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?
    ): TimecodeTag = timecodeTagService.createTag(user.id, songId, tagType, text, timestampMs, endMs)

    override suspend fun getTags(songId: UUID): List<TimecodeTag> = timecodeTagService.getTags(user.id, songId)

    override suspend fun updateTag(
        tagId: UUID,
        tagType: TimecodeTagType,
        text: String,
        timestampMs: Long,
        endMs: Long?
    ): TimecodeTag = timecodeTagService.updateTag(user.id, tagId, tagType, text, timestampMs, endMs)

    override suspend fun deleteTag(tagId: UUID): Boolean = timecodeTagService.deleteTag(user.id, tagId)

    override suspend fun replaceTags(songId: UUID, @LogParam("size") tags: List<TimecodeTagInput>): List<TimecodeTag> =
        timecodeTagService.replaceTags(user.id, songId, tags)

    override suspend fun listTags(tagType: TimecodeTagType?, page: Int, pageSize: Int): PaginatedResponse<TimecodeTag> =
        timecodeTagService.listTags(user.id, tagType, page, pageSize)
}
