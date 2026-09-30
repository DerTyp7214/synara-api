package dev.dertyp.core.process

import dev.dertyp.services.import.ProcessExecutionResult
import io.ktor.util.logging.Logger
import java.io.File

class ExternalTool(
    val name: String,
    val pythonWrapped: Boolean = false,
    private val acceptsResolvedPath: Boolean = false,
    private val invalidCommandMessage: String = "Error: Command must start with '$name'.",
) {
    val path: String? by lazy { findInPath(name) }

    val installed: Boolean get() = path != null

    suspend fun run(
        args: List<String>,
        logger: Logger,
        aliveCheck: suspend () -> Boolean = { true },
        directory: File? = null,
        logCommand: Boolean = true,
        onLineReceived: suspend (String) -> Unit = {},
    ): ProcessExecutionResult? {
        val resolved = path ?: return null
        return executeCommand(listOf(resolved) + args, aliveCheck, logger, directory, logCommand, onLineReceived)
    }

    suspend fun runCommand(
        command: Collection<String>,
        logger: Logger,
        aliveCheck: suspend () -> Boolean,
        directory: File?,
        onLineReceived: suspend (String) -> Unit,
    ): ProcessExecutionResult {
        val cmd = command.toMutableList()
        if (cmd.isEmpty() || !accepts(cmd[0])) {
            return ProcessExecutionResult(-1, invalidCommandMessage, "")
        }

        val resolved = path ?: return ProcessExecutionResult(-1, "Error: The $name path does not exist.", "")

        if (!isPythonInvocation(cmd[0])) {
            cmd[0] = resolved
            if (pythonWrapped) {
                cmd.add(0, PYTHON)
                cmd.add(1, UNBUFFERED)
            }
        }

        return executeCommand(cmd, aliveCheck, logger, directory, onLineReceived = onLineReceived)
    }

    private fun accepts(head: String): Boolean =
        head == name || isPythonInvocation(head) || (acceptsResolvedPath && head == path)

    private fun isPythonInvocation(head: String): Boolean = pythonWrapped && head == PYTHON

    private companion object {
        const val PYTHON = "python3"
        const val UNBUFFERED = "-u"
    }
}
