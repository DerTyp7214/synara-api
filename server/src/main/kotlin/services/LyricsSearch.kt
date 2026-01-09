package dev.dertyp.services

import dev.dertyp.executeCommand
import dev.dertyp.services.tdn.ProcessExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

class LyricsSearch : ILyricsSearch, Service() {
    override suspend fun searchLyrics(
        artist: String,
        title: String,
        syncedOnly: Boolean,
        onLineReceived: suspend (String) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val command = mutableListOf("syncedlyrics")

        val lyricsFile = File.createTempFile(
            "$title - $artist".hashCode().toHexString(HexFormat.UpperCase),
            ".lrc"
        )

        logger.info("Searching lyrics for $title by $artist ($syncedOnly) -> $lyricsFile")

        command.add("-v")
        command.add("-o")
        command.add(lyricsFile.absolutePath)

        if (syncedOnly) command.add("--synced-only")

        command.add("$title - $artist")

        val exitCode = runCommand(command) {
            onLineReceived(it)
            logger.info(it)
        }.exitCode

        if (exitCode != 0) {
            logger.info("Search failed, exitCode=$exitCode")
            logger.info("Deleting temp lyrics file (${lyricsFile.absolutePath}): ${lyricsFile.delete()}")
            throw RuntimeException("Lyrics search failed with exit code $exitCode")
        }

        val lyrics = lyricsFile.readLines()

        logger.info("Deleting temp lyrics file (${lyricsFile.absolutePath}): ${lyricsFile.delete()}")

        return@withContext lyrics
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun runCommand(command: List<String>, onLineReceived: suspend (String) -> Unit): ProcessExecutionResult {
        val startTime = Clock.System.now()
        return executeCommand(
            command,
            { Clock.System.now().minus(startTime) < 5.minutes },
            logger,
            onLineReceived
        )
    }
}