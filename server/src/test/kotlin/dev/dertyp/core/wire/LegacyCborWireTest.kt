@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.LegacyServerCbor
import dev.dertyp.core.ServerWire
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaybackReport
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.serializers.AppCbor
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.dertyp.core.wire.old.QueueWriteResult as OldQueueWriteResult

class LegacyCborWireTest {
    private val legacy = LegacyCborFormat(LegacyServerCbor, ServerWire)

    private fun <N, O> assertByteIdentical(case: WireCase<N, O>) {
        val old = OldClientCbor.encodeToByteArray(case.oldSerializer, case.oldValue)
        assertArrayEquals(old, legacy.encodeToByteArray(case.serializer, case.value), case.name)
    }

    private fun <N, O> assertServerDecodesOldClient(case: WireCase<N, O>) {
        val old = OldClientCbor.encodeToByteArray(case.oldSerializer, case.oldValue)
        assertEquals(case.value, legacy.decodeFromByteArray(case.serializer, old), case.name)
    }

    private fun <N, O> assertOldClientDecodesLegacy(case: WireCase<N, O>) {
        val bytes = legacy.encodeToByteArray(case.serializer, case.value)
        assertEquals(case.oldValue, OldClientCbor.decodeFromByteArray(case.oldSerializer, bytes), case.name)
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
        val text = legacy.encodeToByteArray(PlaybackState.serializer(), newState).latin1()
        assertTrue("shuffleMode" in text)
        assertTrue("Explicit" in text)
        assertTrue("musicbrainzId" in text)
        assertFalse("isShuffled" in text)
        assertFalse("WithSong" in text)
        val items = legacy.encodeToByteArray(PaginatedResponse.serializer(QueueItem.serializer()), newItems).latin1()
        assertFalse("userAdded" in items)
        val report = legacy.encodeToByteArray(PlaybackReport.serializer(), newReport).latin1()
        assertTrue("playing" in report)
        assertFalse("isPlaying" in report)
    }

    @Test
    fun `the plain encoding for newer clients carries only the new names`() {
        val bytes = AppCbor.encodeToByteArray(PlaybackState.serializer(), newState)
        val text = bytes.latin1()
        assertTrue("isShuffled" in text)
        assertTrue("WithSong" in text)
        assertTrue("musicBrainzId" in text)
        assertFalse("shuffleMode" in text)
        assertFalse("Explicit" in text)
        assertFalse("musicbrainzId" in text)
        assertEquals(newState, AppCbor.decodeFromByteArray(PlaybackState.serializer(), bytes))
    }

    @Test
    fun `a renamed field inside a subclass without a rename is read under its old name`() {
        val old = OldClientCbor.encodeToByteArray(OldQueueWriteResult.serializer(), OldQueueWriteResult.Ok(oldInfo))
        assertTrue("shuffleMode" in old.latin1())
        assertEquals(QueueWriteResult.Ok(newInfo), legacy.decodeFromByteArray(QueueWriteResult.serializer(), old))
    }

    @Test
    fun `descriptors are cached and untouched classes keep their descriptor`() {
        val state = PlaybackState.serializer().descriptor
        assertNotSame(state, ServerWire.wireDescriptor(state))
        assertSame(ServerWire.wireDescriptor(state), ServerWire.wireDescriptor(state))
        assertEquals(
            "shuffleMode",
            ServerWire.wireDescriptor(state).getElementName(state.getElementIndex("isShuffled"))
        )
        val device = QueueSyncDevice.serializer().descriptor
        assertSame(device, ServerWire.wireDescriptor(device))
    }
}
