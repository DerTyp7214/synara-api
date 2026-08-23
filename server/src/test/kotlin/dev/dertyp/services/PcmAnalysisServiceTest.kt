package dev.dertyp.services

import dev.dertyp.audio.LosslessFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class PcmAnalysisServiceTest {

    private val service = PcmAnalysisService()

    @Test
    fun `parseProbeOutput reads stream and format fields`() {
        val output = """
            codec_name=pcm_s24le
            sample_rate=96000
            channels=2
            bits_per_sample=24
            bits_per_raw_sample=N/A
            sample_fmt=s32
            duration=12.5
        """.trimIndent()

        val info = service.parseProbeOutput(output)!!
        assertEquals("pcm_s24le", info.codec)
        assertEquals(96000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(24, info.bitDepth)
        assertEquals("s32", info.sampleFmt)
        assertEquals(12.5, info.duration)
    }

    @Test
    fun `parseProbeOutput returns null without sample rate`() {
        assertNull(service.parseProbeOutput("codec_name=pcm_s16le\nchannels=2"))
    }

    @Test
    fun `readChunkLayout walks a RIFF WAV`(@TempDir tempDir: Path) {
        val pcm = ByteArray(1000)
        val file = tempDir.resolve("a.wav").toFile()
        file.writeBytes(buildWav(pcm, withInfo = true, withId3 = true))

        val layout = service.readChunkLayout(file, LosslessFormat.WAV)
        assertEquals(1000L, layout.dataSize)
        assertTrue(layout.hasInfoChunk)
        assertTrue(layout.hasId3)
        assertEquals(pcm.size, file.readBytes().copyOfRange(layout.dataOffset.toInt(), (layout.dataOffset + layout.dataSize).toInt()).size)
        assertEquals("data", String(file.readBytes(), layout.dataOffset.toInt() - 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `readChunkLayout walks a FORM AIFF`(@TempDir tempDir: Path) {
        val pcm = ByteArray(501)
        val file = tempDir.resolve("a.aiff").toFile()
        file.writeBytes(buildAiff(pcm, withId3 = false))

        val layout = service.readChunkLayout(file, LosslessFormat.AIFF)
        assertEquals(501L, layout.dataSize)
        assertFalse(layout.hasId3)
        assertFalse(layout.hasInfoChunk)
        assertEquals("SSND", String(file.readBytes(), layout.dataOffset.toInt() - 16, 4, Charsets.US_ASCII))
    }

    @Test
    fun `readChunkLayout tolerates garbage`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("junk.wav").toFile().apply { writeText("not a wav") }
        assertEquals(PcmAnalysisService.ChunkLayout(), service.readChunkLayout(file, LosslessFormat.WAV))
    }

    private fun chunk(id: String, payload: ByteArray, order: ByteOrder): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(order).putInt(payload.size).array())
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun buildWav(pcm: ByteArray, withInfo: Boolean, withId3: Boolean): ByteArray {
        val le = ByteOrder.LITTLE_ENDIAN
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        body.write(chunk("fmt ", ByteArray(16), le))
        if (withInfo) body.write(chunk("LIST", "INFO".toByteArray(Charsets.US_ASCII) + chunk("INAM", "x".toByteArray(), le), le))
        body.write(chunk("data", pcm, le))
        if (withId3) body.write(chunk("id3 ", ByteArray(10), le))
        return chunk("RIFF", body.toByteArray(), le)
    }

    private fun buildAiff(pcm: ByteArray, withId3: Boolean): ByteArray {
        val be = ByteOrder.BIG_ENDIAN
        val body = ByteArrayOutputStream()
        body.write("AIFF".toByteArray(Charsets.US_ASCII))
        body.write(chunk("COMM", ByteArray(18), be))
        body.write(chunk("SSND", ByteArray(8) + pcm, be))
        if (withId3) body.write(chunk("ID3 ", ByteArray(10), be))
        return chunk("FORM", body.toByteArray(), be)
    }
}
