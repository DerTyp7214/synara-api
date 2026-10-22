package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SidecarLyricsProviderTest {
    @TempDir
    lateinit var dir: File

    private val provider = SidecarLyricsProvider()

    private fun read(path: String?): ProviderResult = runBlocking {
        provider.fetch(ProviderTestSupport.REQUEST.copy(songPath = path), HttpClientPriority.HIGH)
    }

    private fun song(): File = File(dir, "01 Paper Lanterns.flac").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `prefers the lyricsfile then ttml then lrc`() {
        val song = song()
        File(dir, "01 Paper Lanterns.lrc").writeText("[00:01.00]Paper lanterns")
        assertEquals(ProviderResult.Found(LyricsPayload.Lrc("[00:01.00]Paper lanterns")), read(song.path))
        File(dir, "01 Paper Lanterns.ttml").writeText("<tt/>")
        assertEquals(ProviderResult.Found(LyricsPayload.Ttml("<tt/>")), read(song.path))
        File(dir, "01 Paper Lanterns.lyricsfile.yaml").writeText("﻿lines: []")
        assertEquals(ProviderResult.Found(LyricsPayload.LyricsFile("lines: []")), read(song.path))
    }

    @Test
    fun `skips files over the size limit and blank files`() {
        val song = song()
        File(dir, "01 Paper Lanterns.lyricsfile.yaml").writeText("   ")
        File(dir, "01 Paper Lanterns.ttml").writeBytes(ByteArray(SidecarLyricsProvider.MAX_BYTES.toInt() + 1) { 'a'.code.toByte() })
        File(dir, "01 Paper Lanterns.lrc").writeText("[00:01.00]Paper lanterns")
        assertEquals(ProviderResult.Found(LyricsPayload.Lrc("[00:01.00]Paper lanterns")), read(song.path))
    }

    @Test
    fun `no sidecar or no path is a miss`() {
        val song = song()
        File(dir, "02 Other.lrc").writeText("[00:01.00]Other")
        assertEquals(ProviderResult.Miss, read(song.path))
        assertEquals(ProviderResult.Miss, read(null))
    }
}
