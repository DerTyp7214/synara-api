package dev.dertyp.audio

import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LosslessFormatTest {

    @Test
    fun `fromExtension maps known extensions case-insensitively`() {
        assertEquals(LosslessFormat.FLAC, LosslessFormat.fromExtension("flac"))
        assertEquals(LosslessFormat.WAV, LosslessFormat.fromExtension("WAV"))
        assertEquals(LosslessFormat.AIFF, LosslessFormat.fromExtension("aiff"))
        assertEquals(LosslessFormat.AIFF, LosslessFormat.fromExtension("aif"))
        assertNull(LosslessFormat.fromExtension("mp3"))
        assertNull(LosslessFormat.fromExtension(""))
    }

    @Test
    fun `file helpers use the extension`() {
        assertTrue(File("/music/a.wav").isLossless)
        assertEquals(LosslessFormat.AIFF, File("/music/a.AIF").losslessFormat)
        assertFalse(File("/music/a.ogg").isLossless)
    }

    @Test
    fun `parse falls back to FLAC`() {
        assertEquals(LosslessFormat.FLAC, LosslessFormat.parse(null))
        assertEquals(LosslessFormat.FLAC, LosslessFormat.parse(""))
        assertEquals(LosslessFormat.FLAC, LosslessFormat.parse("mp3"))
        assertEquals(LosslessFormat.WAV, LosslessFormat.parse("wav"))
        assertEquals(LosslessFormat.AIFF, LosslessFormat.parse(" Aiff "))
    }

    @Test
    fun `toAudioConfig reads the lossless format`() {
        assertEquals(LosslessFormat.WAV, MapApplicationConfig("audio.losslessFormat" to "WAV").toAudioConfig().losslessFormat)
        assertEquals(LosslessFormat.FLAC, MapApplicationConfig().toAudioConfig().losslessFormat)
    }
}
