@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.LegacyServerCbor
import dev.dertyp.core.ServerWire
import dev.dertyp.core.wire.fixtures.*
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.serializers.AppCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LegacyCborWireTest {
    private val stateSongId = UUID.fromString("00000000-0000-0000-0000-000000000011")

    private val legacy = LegacyCborFormat(FixtureLegacyCbor, FixtureWire)

    private fun <N, O> assertByteIdentical(case: WireCase<N, O>) {
        val old = AppCbor.encodeToByteArray(case.oldSerializer, case.oldValue)
        assertArrayEquals(old, legacy.encodeToByteArray(case.serializer, case.value), case.name)
    }

    private fun <N, O> assertServerDecodesOldClient(case: WireCase<N, O>) {
        val old = AppCbor.encodeToByteArray(case.oldSerializer, case.oldValue)
        assertEquals(case.value, legacy.decodeFromByteArray(case.serializer, old), case.name)
    }

    private fun <N, O> assertOldClientDecodesLegacy(case: WireCase<N, O>) {
        val bytes = legacy.encodeToByteArray(case.serializer, case.value)
        assertEquals(case.oldValue, AppCbor.decodeFromByteArray(case.oldSerializer, bytes), case.name)
    }

    @Test
    fun `legacy encoding is byte identical to the old client encoding`() {
        wireCases.forEach { assertByteIdentical(it) }
    }

    @Test
    fun `the server decodes old client payloads into the new models`() {
        wireCases.forEach { assertServerDecodesOldClient(it) }
    }

    @Test
    fun `an old client decodes the legacy encoding`() {
        wireCases.forEach { assertOldClientDecodesLegacy(it) }
    }

    @Test
    fun `legacy encoding carries only the old names`() {
        val text = legacy.encodeToByteArray(WirePlaybackState.serializer(), newState).latin1()
        assertTrue("shuffleMode" in text)
        assertTrue("Explicit" in text)
        assertTrue("musicbrainzId" in text)
        assertFalse("isShuffled" in text)
        assertFalse("WithSong" in text)
        val items = legacy.encodeToByteArray(PaginatedResponse.serializer(WireQueueItem.serializer()), newItems).latin1()
        assertFalse("userAdded" in items)
        val report = legacy.encodeToByteArray(WirePlaybackReport.serializer(), newReport).latin1()
        assertTrue("playing" in report)
        assertFalse("isPlaying" in report)
    }

    @Test
    fun `the plain encoding for newer clients carries only the new names`() {
        val bytes = AppCbor.encodeToByteArray(WirePlaybackState.serializer(), newState)
        val text = bytes.latin1()
        assertTrue("isShuffled" in text)
        assertTrue("WithSong" in text)
        assertTrue("musicBrainzId" in text)
        assertFalse("shuffleMode" in text)
        assertFalse("Explicit" in text)
        assertFalse("musicbrainzId" in text)
        assertEquals(newState, AppCbor.decodeFromByteArray(WirePlaybackState.serializer(), bytes))
    }

    @Test
    fun `a renamed field inside a subclass without a rename is read under its old name`() {
        val old = AppCbor.encodeToByteArray(OldWireQueueWriteResult.serializer(), OldWireQueueWriteResult.Ok(oldInfo))
        assertTrue("shuffleMode" in old.latin1())
        assertEquals(
            WireQueueWriteResult.Ok(newInfo),
            legacy.decodeFromByteArray(WireQueueWriteResult.serializer(), old)
        )
    }

    @Test
    fun `descriptors are cached and untouched classes keep their descriptor`() {
        val state = WirePlaybackState.serializer().descriptor
        assertNotSame(state, FixtureWire.wireDescriptor(state))
        assertSame(FixtureWire.wireDescriptor(state), FixtureWire.wireDescriptor(state))
        assertEquals(
            "shuffleMode",
            FixtureWire.wireDescriptor(state).getElementName(state.getElementIndex("isShuffled"))
        )
        val device = QueueSyncDevice.serializer().descriptor
        assertSame(device, FixtureWire.wireDescriptor(device))
    }

    @Test
    fun `the server legacy format encodes real models exactly like the plain format`() {
        val server = LegacyCborFormat(LegacyServerCbor, ServerWire)
        val info = QueueInfo(
            version = 3,
            modifiedAt = 4,
            currentIndex = 1,
            isShuffled = true,
            repeatMode = RepeatMode.ONE,
            total = 2
        )
        val state = PlaybackState(
            queue = listOf(PlaybackState.QueueEntry.FromSource(stateSongId, 1)),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 5,
            isShuffled = true,
            repeatMode = RepeatMode.ALL,
        )
        val results = listOf(QueueWriteResult.Ok(info), QueueWriteResult.Conflict(info))
        val resultSerializer = ListSerializer(QueueWriteResult.serializer())

        val stateBytes = server.encodeToByteArray(PlaybackState.serializer(), state)
        assertArrayEquals(AppCbor.encodeToByteArray(PlaybackState.serializer(), state), stateBytes)
        assertEquals(state, server.decodeFromByteArray(PlaybackState.serializer(), stateBytes))
        val resultBytes = server.encodeToByteArray(resultSerializer, results)
        assertArrayEquals(AppCbor.encodeToByteArray(resultSerializer, results), resultBytes)
        assertEquals(results, server.decodeFromByteArray(resultSerializer, resultBytes))
        assertSame(PlaybackState.serializer().descriptor, ServerWire.wireDescriptor(PlaybackState.serializer().descriptor))
    }
}
