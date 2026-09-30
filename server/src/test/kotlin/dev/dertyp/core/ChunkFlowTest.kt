package dev.dertyp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.random.Random

class ChunkFlowTest {

    private fun legacyChunks(file: File, offset: Long, chunkSize: Int): Flow<ByteArray> = flow {
        val buffer = ByteArray(chunkSize)
        file.inputStream().use { input ->
            input.skip(offset)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                emit(buffer.copyOf(bytesRead))
                bytesRead = input.read(buffer)
            }
        }
    }

    private fun assertSameChunks(expected: List<ByteArray>, actual: List<ByteArray>, label: String) {
        assertEquals(expected.size, actual.size, "chunk count for $label")
        expected.zip(actual).forEachIndexed { index, (e, a) ->
            assertArrayEquals(e, a, "chunk $index for $label")
        }
    }

    @Test
    fun `chunkFlow yields the same chunks as the previous stream loop`(@TempDir tempDir: Path) = runBlocking {
        val size = 10_000
        val bytes = Random(42).nextBytes(size)
        val file = tempDir.resolve("audio.bin").toFile().apply { writeBytes(bytes) }

        val chunkSizes = listOf(1, 7, 1024, 4096, size, size + 1)
        for (chunkSize in chunkSizes) {
            val lastChunkStart = ((size - 1) / chunkSize).toLong() * chunkSize
            val offsets = listOf(0L, 1L, size / 2L, 4097L, lastChunkStart, size - 1L, size.toLong(), size + 10L)
            for (offset in offsets) {
                val label = "offset=$offset chunkSize=$chunkSize"
                val expected = legacyChunks(file, offset, chunkSize).toList()
                val actual = file.chunkFlow(offset, chunkSize).toList()
                assertSameChunks(expected, actual, label)

                val joined = actual.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                val start = offset.coerceAtMost(size.toLong()).toInt()
                assertArrayEquals(bytes.copyOfRange(start, size), joined, label)
                assertTrue(actual.all { it.size <= chunkSize }, label)
            }
        }
    }

    @Test
    fun `offset equal to the file length yields nothing`(@TempDir tempDir: Path) = runBlocking {
        val file = tempDir.resolve("audio.bin").toFile().apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        assertEquals(emptyList<ByteArray>(), file.chunkFlow(file.length(), 4096).toList())
        assertEquals(0, legacyChunks(file, file.length(), 4096).toList().size)
    }

    @Test
    fun `empty file yields nothing`(@TempDir tempDir: Path) = runBlocking {
        val file = tempDir.resolve("empty.bin").toFile().apply { writeBytes(ByteArray(0)) }

        assertEquals(0, file.chunkFlow(0, 4096).toList().size)
        assertEquals(0, legacyChunks(file, 0, 4096).toList().size)
    }
}
