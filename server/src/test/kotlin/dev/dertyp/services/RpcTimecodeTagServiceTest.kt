package dev.dertyp.services

import dev.dertyp.data.TimecodeTagAction
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
    fun `createTag forwards the user id song id type text timestamp endMs action and fade`() = runBlocking {
        val songId = UUID.randomUUID()

        service.createTag(songId, TimecodeTagType.CHAPTER, "Intro", 1000L, 2000L, TimecodeTagAction.SKIP, true)

        coVerify(exactly = 1) {
            timecodeTagService.createTag(user.id, songId, TimecodeTagType.CHAPTER, "Intro", 1000L, 2000L, TimecodeTagAction.SKIP, true)
        }
    }

    @Test
    fun `createTag defaults to no action and no fade`() = runBlocking {
        val songId = UUID.randomUUID()

        service.createTag(songId, TimecodeTagType.MARKER, "Drop", 1000L)

        coVerify(exactly = 1) {
            timecodeTagService.createTag(user.id, songId, TimecodeTagType.MARKER, "Drop", 1000L, null, TimecodeTagAction.NONE, false)
        }
    }

    @Test
    fun `getTags forwards the user id and song id`() = runBlocking {
        val songId = UUID.randomUUID()

        service.getTags(songId)

        coVerify(exactly = 1) { timecodeTagService.getTags(user.id, songId) }
    }

    @Test
    fun `updateTag forwards the user id tag id type text timestamp endMs action and fade`() = runBlocking {
        val tagId = UUID.randomUUID()

        service.updateTag(tagId, TimecodeTagType.MARKER, "Chorus", 5000L, null, TimecodeTagAction.PLAY_UNTIL, true)

        coVerify(exactly = 1) {
            timecodeTagService.updateTag(user.id, tagId, TimecodeTagType.MARKER, "Chorus", 5000L, null, TimecodeTagAction.PLAY_UNTIL, true)
        }
    }

    @Test
    fun `updateTag forwards missing action and fade as null`() = runBlocking {
        val tagId = UUID.randomUUID()

        service.updateTag(tagId, TimecodeTagType.MARKER, "Chorus", 5000L, null)

        coVerify(exactly = 1) { timecodeTagService.updateTag(user.id, tagId, TimecodeTagType.MARKER, "Chorus", 5000L, null, null, null) }
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
        val tags = listOf(
            TimecodeTagInput(type = TimecodeTagType.NOTE, text = "note", timestampMs = 100L, endMs = null),
            TimecodeTagInput(type = TimecodeTagType.CHAPTER, timestampMs = 200L, endMs = 900L, action = TimecodeTagAction.SKIP, fade = true)
        )

        service.replaceTags(songId, tags)

        coVerify(exactly = 1) { timecodeTagService.replaceTags(user.id, songId, tags) }
    }

    @Test
    fun `listTags forwards the user id type page and pageSize`() = runBlocking {
        service.listTags(TimecodeTagType.CHAPTER, 2, 50)

        coVerify(exactly = 1) { timecodeTagService.listTags(user.id, TimecodeTagType.CHAPTER, 2, 50) }
    }
}
