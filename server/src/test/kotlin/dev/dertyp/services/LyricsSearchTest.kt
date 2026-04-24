package dev.dertyp.services

import dev.dertyp.executeCommand
import dev.dertyp.services.download.ProcessExecutionResult
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class LyricsSearchTest {
    private val service = LyricsSearch()

    @AfterEach
    fun tearDown() {
        unmockkStatic("dev.dertyp.UtilsKt")
    }

    @Test
    fun `searchLyrics should return lines from temp file if command succeeds`() = runBlocking {
        mockkStatic("dev.dertyp.UtilsKt")
        
        coEvery { 
            executeCommand(any(), any(), any(), any(), any())
        } answers {
            val command = it.invocation.args[0] as List<*>
            val outputPath = command[command.indexOf("-o") + 1] as String
            File(outputPath).writeText("Line 1\nLine 2")
            ProcessExecutionResult(0, "output", "")
        }

        val lyrics = service.searchLyrics("Artist", "Title", false)
        
        assertEquals(listOf("Line 1", "Line 2"), lyrics)
    }
}
