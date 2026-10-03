package dev.dertyp.core.process

import dev.dertyp.services.import.ProcessExecutionResult
import io.ktor.util.logging.KtorSimpleLogger
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ExternalToolTest {
    private val logger = KtorSimpleLogger("ExternalToolTest")
    private val directory = File("/tmp/work")
    private val executed = mutableListOf<List<String>>()

    private sealed interface Outcome {
        data class Rejected(val result: ProcessExecutionResult) : Outcome
        data class Executed(val command: List<String>) : Outcome
    }

    private class LegacyImporter(
        val name: String,
        val path: String?,
        val accepted: (String, String?) -> Boolean,
        val invalidMessage: String,
        val pythonWrapped: Boolean,
    )

    private fun legacy(importer: LegacyImporter, command: Collection<String>): Outcome {
        val cmd = command.toMutableList()
        if (cmd.isEmpty() || !importer.accepted(cmd[0], importer.path)) {
            return Outcome.Rejected(ProcessExecutionResult(-1, importer.invalidMessage, ""))
        }
        val path = importer.path
            ?: return Outcome.Rejected(
                ProcessExecutionResult(
                    -1,
                    "Error: The ${importer.name} path does not exist.",
                    ""
                )
            )
        if (importer.pythonWrapped) {
            if (cmd[0] != "python3") {
                cmd[0] = path
                cmd.add(0, "python3")
                cmd.add(1, "-u")
            }
        } else {
            cmd[0] = path
        }
        return Outcome.Executed(cmd)
    }

    private fun legacyYtdlp(path: String?) = LegacyImporter(
        "yt-dlp", path, { head, _ -> head == "yt-dlp" }, "Invalid command", false
    )

    private fun legacyTiddl(path: String?) = LegacyImporter(
        "tiddl",
        path,
        { head, _ -> head == "tiddl" || head == "python3" },
        "Error: Command must start with 'tiddl'.",
        true
    )

    private fun legacyTdn(path: String?) = LegacyImporter(
        "tdn", path, { head, _ -> head == "tdn" || head == "python3" }, "Error: Command must start with 'tdn'.", true
    )

    private fun legacyGamdl(path: String?) = LegacyImporter(
        "gamdl",
        path,
        { head, resolved -> head == "gamdl" || head == resolved },
        "Error: Command must start with 'gamdl'.",
        false
    )

    @BeforeEach
    fun setup() {
        mockkStatic("dev.dertyp.core.process.CommandKt")
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } answers {
            executed.add(firstArg<List<String>>())
            assertEquals(logger, thirdArg<Any?>())
            assertEquals(directory, arg<Any?>(3))
            assertEquals(true, arg<Any?>(4))
            ProcessExecutionResult(0, "ok", "")
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun assertMatchesLegacy(tool: ExternalTool, legacy: LegacyImporter, commands: List<List<String>>) =
        runBlocking {
            for (command in commands) {
                executed.clear()
                val result = tool.runCommand(command, logger, { true }, directory) {}
                when (val expected = legacy(legacy, command)) {
                    is Outcome.Rejected -> {
                        assertEquals(expected.result, result, "command $command")
                        assertTrue(executed.isEmpty(), "command $command must not run")
                    }

                    is Outcome.Executed -> {
                        assertEquals(listOf(expected.command), executed, "command $command")
                        assertEquals(ProcessExecutionResult(0, "ok", ""), result)
                    }
                }
            }
        }

    private fun commandsFor(name: String, path: String) = listOf(
        emptyList(),
        listOf(name),
        listOf(name, "-J", "--simulate", "https://example.com/a b"),
        listOf("python3", path, "download", "url", "https://tidal.com/track/1"),
        listOf(path, "--cookies-path", "/c.txt", "https://music.apple.com/us/song/1"),
        listOf("other", "arg"),
    )

    @Test
    fun `yt-dlp commands match the previous importer`() {
        every { findInPath("yt-dlp") } returns "/usr/bin/yt-dlp"
        assertMatchesLegacy(
            ExternalTool("yt-dlp", invalidCommandMessage = "Invalid command"),
            legacyYtdlp("/usr/bin/yt-dlp"),
            commandsFor("yt-dlp", "/usr/bin/yt-dlp"),
        )
    }

    @Test
    fun `tiddl commands match the previous importer`() {
        every { findInPath("tiddl") } returns "/usr/local/bin/tiddl"
        assertMatchesLegacy(
            ExternalTool("tiddl", pythonWrapped = true),
            legacyTiddl("/usr/local/bin/tiddl"),
            commandsFor("tiddl", "/usr/local/bin/tiddl"),
        )
    }

    @Test
    fun `tdn commands match the previous importer`() {
        every { findInPath("tdn") } returns "/usr/local/bin/tdn"
        assertMatchesLegacy(
            ExternalTool("tdn", pythonWrapped = true),
            legacyTdn("/usr/local/bin/tdn"),
            commandsFor("tdn", "/usr/local/bin/tdn"),
        )
    }

    @Test
    fun `gamdl commands match the previous importer`() {
        every { findInPath("gamdl") } returns "/usr/bin/gamdl"
        assertMatchesLegacy(
            ExternalTool("gamdl", acceptsResolvedPath = true),
            legacyGamdl("/usr/bin/gamdl"),
            commandsFor("gamdl", "/usr/bin/gamdl"),
        )
    }

    @Test
    fun `missing executables are reported like the previous importers`() {
        every { findInPath(any()) } returns null
        assertMatchesLegacy(
            ExternalTool("yt-dlp", invalidCommandMessage = "Invalid command"),
            legacyYtdlp(null),
            commandsFor("yt-dlp", "/x")
        )
        assertMatchesLegacy(ExternalTool("tiddl", pythonWrapped = true), legacyTiddl(null), commandsFor("tiddl", "/x"))
        assertMatchesLegacy(ExternalTool("tdn", pythonWrapped = true), legacyTdn(null), commandsFor("tdn", "/x"))
        assertMatchesLegacy(
            ExternalTool("gamdl", acceptsResolvedPath = true),
            legacyGamdl(null),
            commandsFor("gamdl", "/x")
        )
    }

    @Test
    fun `run prefixes the resolved path and forwards every argument`() = runBlocking {
        every { findInPath("ffmpeg") } returns "/usr/bin/ffmpeg"
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")

        val tool = ExternalTool("ffmpeg")
        tool.run(listOf("-v", "error", "-i", "in.wav"), logger, directory = directory, logCommand = false)

        coVerify(exactly = 1) {
            executeCommand(
                listOf("/usr/bin/ffmpeg", "-v", "error", "-i", "in.wav"),
                any(),
                logger,
                directory,
                false,
                any()
            )
        }
    }

    @Test
    fun `run returns null without executing when the tool is missing`() = runBlocking {
        every { findInPath("fpcalc") } returns null

        val tool = ExternalTool("fpcalc")

        assertFalse(tool.installed)
        assertNull(tool.run(listOf("-json", "a.flac"), logger))
        coVerify(exactly = 0) { executeCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `path is resolved lazily and only once`() {
        every { findInPath("metaflac") } returns "/usr/bin/metaflac"

        val tool = ExternalTool("metaflac")
        verify(exactly = 0) { findInPath("metaflac") }

        assertEquals("/usr/bin/metaflac", tool.path)
        assertEquals("/usr/bin/metaflac", tool.path)
        verify(exactly = 1) { findInPath("metaflac") }
    }
}
