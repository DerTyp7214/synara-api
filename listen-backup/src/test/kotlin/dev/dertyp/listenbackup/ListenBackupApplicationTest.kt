package dev.dertyp.listenbackup

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import java.io.File
import java.util.UUID

class ListenBackupApplicationTest {
    private val dbFile = File.createTempFile("listen_backup", ".db")
    private val store = ListenBackupStore(SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.absolutePath}" })

    @AfterEach
    fun tearDown() {
        dbFile.delete()
    }

    private fun listen(id: UUID, msPlayed: Long?, updatedAt: Long) = BackupListen(
        id = id,
        userId = UUID.randomUUID(),
        songId = null,
        recordingMbid = null,
        recordingMsid = null,
        releaseMbid = null,
        isrcs = "ABC",
        artistMbids = null,
        trackName = "Track",
        artistName = "Artist",
        releaseName = null,
        listenedAt = 1000L,
        msPlayed = msPlayed,
        updatedAt = updatedAt,
    )

    @Test
    fun `rejects uploads without a valid key and upserts by id`() = testApplication {
        application { backupModule(store, "secret") }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }
        val serverId = UUID.randomUUID()
        val listenId = UUID.randomUUID()
        val batch = ListenBackupBatch(serverId, listOf(listen(listenId, 100L, 1L)))

        val unauthorized = client.post(ListenBackupProtocol.LISTENS_PATH) {
            contentType(ContentType.Application.Json)
            setBody(batch)
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val wrongKey = client.get(ListenBackupProtocol.STATUS_PATH) {
            header(ListenBackupProtocol.KEY_HEADER, "nope")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)

        val first = client.post(ListenBackupProtocol.LISTENS_PATH) {
            header(ListenBackupProtocol.KEY_HEADER, "secret")
            contentType(ContentType.Application.Json)
            setBody(batch)
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(1, first.body<ListenBackupBatchResult>().received)

        val second = client.post(ListenBackupProtocol.LISTENS_PATH) {
            header(ListenBackupProtocol.KEY_HEADER, "secret")
            contentType(ContentType.Application.Json)
            setBody(ListenBackupBatch(serverId, listOf(listen(listenId, 250L, 2L), listen(UUID.randomUUID(), null, 3L))))
        }
        assertEquals(2, second.body<ListenBackupBatchResult>().received)

        val health = client.get(ListenBackupProtocol.HEALTH_PATH).body<ListenBackupHealth>()
        assertEquals(2L, health.listenCount)

        val status = client.get(ListenBackupProtocol.STATUS_PATH) {
            header(ListenBackupProtocol.KEY_HEADER, "secret")
            parameter(ListenBackupProtocol.SERVER_ID_PARAM, serverId.toString())
        }.body<ListenBackupStatus>()
        assertEquals(serverId, status.serverId)
        assertEquals(2L, status.listenCount)
        assertEquals(3L, status.maxUpdatedAt)
        assertNotNull(status.lastReceivedAt)

        val other = client.get(ListenBackupProtocol.STATUS_PATH) {
            header(ListenBackupProtocol.KEY_HEADER, "secret")
            parameter(ListenBackupProtocol.SERVER_ID_PARAM, UUID.randomUUID().toString())
        }.body<ListenBackupStatus>()
        assertEquals(0L, other.listenCount)
    }
}
