package dev.dertyp.services.import

import dev.dertyp.randomPlatformUUID
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.metadata.IMetadataService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class ImportQueueEntrySerializationTest {
    private val noop: suspend () -> Unit = {}

    private val urlEntry = UrlImportQueueEntry(
        urls = mutableListOf("https://tidal.com/album/1"),
        ids = listOf("1", "2"),
        byUser = randomPlatformUUID(),
        type = Type.ALBUM,
        importer = ImportBackend("tidal"),
        metadata = IMetadataService.Artist(id = "a1", name = "Artist", popularity = 0.5f, images = emptyList()),
        callback = noop
    )

    private val favouriteEntry = FavouriteImportQueueEntry(
        favoriteType = ImportFavType.tracks,
        byUser = randomPlatformUUID(),
        type = Type.SONG,
        importer = ImportBackend("tidal"),
        callback = noop
    )

    private val entries = listOf(urlEntry, favouriteEntry)

    private fun normalize(entry: ImportQueueEntry): ImportQueueEntry = when (entry) {
        is UrlImportQueueEntry -> entry.copy(callback = noop)
        is FavouriteImportQueueEntry -> entry.copy(callback = noop)
    }

    private fun normalize(line: LogLine) = line.copy(queueEntry = normalize(line.queueEntry))

    @Test
    fun `log line with url entry round trips through json`() {
        val line = LogLine(urlEntry, "downloading")
        val json = AppJson.encodeToString(LogLine.serializer(), line)
        val entry = AppJson.parseToJsonElement(json).jsonObject["queueEntry"]!!.jsonObject
        assertEquals(UrlImportQueueEntry.serializer().descriptor.serialName, entry["entryType"]!!.jsonPrimitive.content)
        assertEquals("album", entry["type"]!!.jsonPrimitive.content)
        assertEquals(line, normalize(AppJson.decodeFromString(LogLine.serializer(), json)))
    }

    @Test
    fun `every entry round trips through json via the base serializer`() {
        entries.forEach { original ->
            val json = AppJson.encodeToString(ImportQueueEntry.serializer(), original)
            assertEquals(original, normalize(AppJson.decodeFromString(ImportQueueEntry.serializer(), json)))

            val line = LogLine(original, null)
            val lineJson = AppJson.encodeToString(LogLine.serializer(), line)
            assertEquals(line, normalize(AppJson.decodeFromString(LogLine.serializer(), lineJson)))
        }
    }

    @Test
    fun `every entry round trips through cbor without the json discriminator`() {
        entries.forEach { original ->
            val bytes = AppCbor.encodeToByteArray(ImportQueueEntry.serializer(), original)
            assertFalse(bytes.decodeToString().contains("entryType"))
            assertEquals(original, normalize(AppCbor.decodeFromByteArray(ImportQueueEntry.serializer(), bytes)))

            val line = LogLine(original, "line")
            val lineBytes = AppCbor.encodeToByteArray(LogLine.serializer(), line)
            assertEquals(line, normalize(AppCbor.decodeFromByteArray(LogLine.serializer(), lineBytes)))
        }
    }
}
