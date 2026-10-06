@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core

import dev.dertyp.core.wire.LegacyWire
import dev.dertyp.core.wire.fixtures.*
import dev.dertyp.core.wire.legacyCbor
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.RepeatMode
import dev.dertyp.serializers.AppCbor
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.KrpcSerialFormat
import kotlinx.rpc.krpc.serialization.KrpcSerialFormatBuilder
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.rpc.withService
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID

private class Recording(
    val cbor: Cbor,
    val wire: LegacyWire?,
    val sent: MutableList<ByteArray>,
    val received: MutableList<ByteArray>
) :
    BinaryFormat {
    override val serializersModule: SerializersModule get() = cbor.serializersModule

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(wire?.strategy(serializer) ?: serializer, value).also { sent += it }

    override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        received += bytes
        return cbor.decodeFromByteArray(wire?.deserializer(deserializer) ?: deserializer, bytes)
    }
}

private object RecordingKrpcFormat : KrpcSerialFormat<Recording, CborBuilder> {
    override fun withBuilder(from: Recording?, builderConsumer: CborBuilder.() -> Unit): Recording {
        val base = requireNotNull(from)
        return Recording(Cbor(base.cbor, builderConsumer), base.wire, base.sent, base.received)
    }

    override fun CborBuilder.applySerializersModule(serializersModule: SerializersModule) {
        this.serializersModule = this.serializersModule + serializersModule
    }
}

class WireFormatKrpcTest {
    private val meta = WireQueueMeta(currentIndex = 1, isShuffled = true, repeatMode = RepeatMode.ALL)
    private val uploadId = UUID.fromString("00000000-0000-0000-0000-000000000042")

    private class Connection(val version: String, wire: LegacyWire?) {
        val sent: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val received: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val format = Recording(if (wire == null) AppCbor else FixtureLegacyCbor, wire, sent, received)

        fun sentText(): String = sent.joinToString("") { it.latin1() }

        fun receivedText(): String = received.joinToString("") { it.latin1() }
    }

    private class CallResults(
        val info: WireQueueInfo,
        val page: PaginatedResponse<WireQueueItem>,
        val observed: List<WireQueueInfo>,
        val insert: WireQueueWriteResult,
        val begin: WireQueueUploadStart,
        val commit: WireQueueWriteResult,
    )

    @Test
    fun `connections on the legacy and the plain format are served side by side with their own names`() = testApplication {
        val queue = mockk<IWireQueueService>()
        val inserted = Collections.synchronizedList(mutableListOf<List<WireQueueItem>>())
        val committed = Collections.synchronizedList(mutableListOf<WireQueueMeta>())
        val items = slot<List<WireQueueItem>>()
        val metaSlot = slot<WireQueueMeta>()
        coEvery { queue.getQueueInfo() } returns newInfo
        coEvery { queue.getQueue(any(), any()) } returns newItems
        every { queue.observeQueue() } returns flowOf(newInfo, newInfo.copy(version = 4))
        coEvery { queue.insert(any(), any(), capture(items)) } answers {
            inserted += items.captured
            WireQueueWriteResult.Ok(newInfo)
        }
        coEvery { queue.beginUpload(any()) } returns WireQueueUploadStart.Conflict(newInfo)
        coEvery { queue.commitUpload(any(), capture(metaSlot)) } answers {
            committed += metaSlot.captured
            WireQueueWriteResult.Conflict(newInfo)
        }
        application {
            install(WebSockets)
        }
        routing {
            rpc("/rpc") {
                rpcConfig {
                    serialization {
                        if (call.clientInfo.apiVersion < FIXTURE_RENAMES_API_VERSION) {
                            legacyCbor(FixtureLegacyCbor, FixtureWire)
                        } else {
                            cbor(AppCbor)
                        }
                    }
                }
                registerService(IWireQueueService::class) { queue }
            }
        }

        val legacy = Connection("7", FixtureWire)
        val current = Connection("8", null)

        val results = coroutineScope {
            listOf(legacy, current, legacy, current).map { connection ->
                async {
                    val client = createClient {
                        installKrpc {
                            serialization {
                                register(KrpcSerialFormatBuilder.Binary(RecordingKrpcFormat, connection.format) {})
                            }
                        }
                    }
                    val service = client.rpc("/rpc") { header(ApiVersion.HEADER, connection.version) }
                        .withService<IWireQueueService>()
                    CallResults(
                        info = service.getQueueInfo(),
                        page = service.getQueue(0, 50),
                        observed = service.observeQueue().toList(),
                        insert = service.insert(1, 0, newItems.data),
                        begin = service.beginUpload(1),
                        commit = service.commitUpload(uploadId, meta),
                    )
                }
            }.awaitAll()
        }

        results.forEach { result ->
            assertEquals(newInfo, result.info)
            assertEquals(newItems, result.page)
            assertEquals(listOf(newInfo, newInfo.copy(version = 4)), result.observed)
            assertEquals(WireQueueWriteResult.Ok(newInfo), result.insert)
            assertEquals(WireQueueUploadStart.Conflict(newInfo), result.begin)
            assertEquals(WireQueueWriteResult.Conflict(newInfo), result.commit)
        }
        assertEquals(List(4) { newItems.data }, inserted.toList())
        assertEquals(List(4) { meta }, committed.toList())

        val oldReceived = legacy.receivedText()
        assertTrue("shuffleMode" in oldReceived)
        assertTrue("musicbrainzId" in oldReceived)
        assertFalse("isShuffled" in oldReceived)
        assertFalse("userAdded" in oldReceived)
        val oldSent = legacy.sentText()
        assertTrue("shuffleMode" in oldSent)
        assertFalse("isShuffled" in oldSent)
        assertFalse("userAdded" in oldSent)

        val newReceived = current.receivedText()
        assertTrue("isShuffled" in newReceived)
        assertTrue("userAdded" in newReceived)
        assertFalse("shuffleMode" in newReceived)
        assertFalse("musicbrainzId" in newReceived)
        val newSent = current.sentText()
        assertTrue("isShuffled" in newSent)
        assertTrue("userAdded" in newSent)
        assertFalse("shuffleMode" in newSent)
    }
}
