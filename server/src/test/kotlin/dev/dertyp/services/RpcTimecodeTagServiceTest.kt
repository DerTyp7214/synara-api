package dev.dertyp.services

import dev.dertyp.data.TimecodeTagInput
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.data.User
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

class RpcTimecodeTagServiceTest {
    private val timecodeTagService = mockk<TimecodeTagService>(relaxed = true)
    private val user = User(UUID.randomUUID(), "user", passwordHash = "hash")
    private val service = RpcTimecodeTagService(user, timecodeTagService)

    @Test
    fun `createTag forwards the user id song id type text timestamp and endMs`() = runBlocking {
        val songId = UUID.randomUUID()

        service.createTag(songId, TimecodeTagType.CHAPTER, "Intro", 1000L, 2000L)

        coVerify(exactly = 1) { timecodeTagService.createTag(user.id, songId, TimecodeTagType.CHAPTER, "Intro", 1000L, 2000L) }
    }

    @Test
    fun `getTags forwards the user id and song id`() = runBlocking {
        val songId = UUID.randomUUID()

        service.getTags(songId)

        coVerify(exactly = 1) { timecodeTagService.getTags(user.id, songId) }
    }

    @Test
    fun `updateTag forwards the user id tag id type text timestamp and endMs`() = runBlocking {
        val tagId = UUID.randomUUID()

        service.updateTag(tagId, TimecodeTagType.MARKER, "Chorus", 5000L, null)

        coVerify(exactly = 1) { timecodeTagService.updateTag(user.id, tagId, TimecodeTagType.MARKER, "Chorus", 5000L, null) }
    }

    @Test
    fun `deleteTag forwards the user id and tag id`() = runBlocking {
        val tagId = UUID.randomUUID()

        service.deleteTag(tagId)

        coVerify(exactly = 1) { timecodeTagService.deleteTag(user.id, tagId) }
    }

    @Test
    fun `replaceTags forwards the user id song id and tags`() = runBlocking {
        val songId = UUID.randomUUID()
        val tags = listOf(TimecodeTagInput(type = TimecodeTagType.NOTE, text = "note", timestampMs = 100L, endMs = null))

        service.replaceTags(songId, tags)

        coVerify(exactly = 1) { timecodeTagService.replaceTags(user.id, songId, tags) }
    }

    @Test
    fun `listTags forwards the user id type page and pageSize`() = runBlocking {
        service.listTags(TimecodeTagType.CHAPTER, 2, 50)

        coVerify(exactly = 1) { timecodeTagService.listTags(user.id, TimecodeTagType.CHAPTER, 2, 50) }
    }
}
