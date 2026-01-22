package dev.dertyp

import dev.dertyp.core.ClientCloseException
import dev.dertyp.core.kill
import dev.dertyp.core.lineFlow
import dev.dertyp.services.tdn.ProcessExecutionResult
import io.ktor.util.logging.*
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.buffer
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun getDateFromISO(iso: String?): LocalDate? {
    return if (iso == null) null else LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
}

fun getDateTimeFromISO(iso: String?): LocalDateTime? {
    return if (iso == null) null else LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

fun getISOFromDate(date: LocalDate?): String? {
    return if (date == null) null else DateTimeFormatter.ISO_LOCAL_DATE.format(date)
}

fun getISOFromDateTime(date: LocalDateTime): String {
    return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(date)
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    suspendTransaction { withContext(Dispatchers.IO) { block() } }

private val processes: MutableList<Process> = mutableListOf()

fun killAll() {
    while (processes.isNotEmpty()) {
        processes.removeFirst().kill()
    }
}

suspend fun executeCommand(
    command: List<String>,
    aliveCheck: suspend () -> Boolean,
    logger: Logger = KtorSimpleLogger("executeCommand"),
    onLineReceived: suspend (String) -> Unit = {}
): ProcessExecutionResult {
    val timeString = LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).split(".").first()
    logger.info("[$timeString] Starting command: ${command.joinToString(" ")}")

    val fullOutput = StringBuilder()

    val currentJob = currentCoroutineContext().job
    var completionHandle: DisposableHandle? = null

    var process: Process? = null

    return coroutineScope {
        val checkJob = launch(Dispatchers.Default) {
            try {
                while (aliveCheck()) {
                    delay(200)
                    yield()
                }
            } catch (_: Exception) {
                logger.info("Parent no longer alive, stoping forcefully")
            }

            if (process?.isAlive == true) process?.kill()
            cancel("Stopping command", ClientCloseException())
        }

        try {
            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment()["COLUMNS"] = "500" }
                .start()

            process?.let(processes::add)

            completionHandle = currentJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    process?.destroyForcibly()
                }
            }

            val outputJob = launch(Dispatchers.IO) {
                val reader = InputStreamReader(process.inputStream)

                try {
                    reader.lineFlow().buffer(UNLIMITED).collect { line ->
                        logger.info(line)
                        fullOutput.appendLine(line)
                        if (line.isNotBlank()) onLineReceived(line)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            val exitCode = process.waitFor()
            outputJob.join()

            return@coroutineScope ProcessExecutionResult(exitCode, fullOutput.toString(), "")

        } catch (e: Exception) {
            if (e is ClientCloseException || e.cause is ClientCloseException) logger.info("Client disconnected.")
            else e.printStackTrace()
            return@coroutineScope ProcessExecutionResult(
                -2,
                fullOutput.toString(),
                "Failed to execute '${command.first()}'. Error: ${e.message}"
            )
        } finally {
            completionHandle?.dispose()

            if (process?.isAlive == true) process.kill()
            if (checkJob.isActive) checkJob.cancel()

            process?.let(processes::remove)
        }
    }
}

fun findInPath(executableName: String): String? {
    val systemPath = System.getenv("PATH") ?: return null
    val pathSeparator = File.pathSeparator

    return systemPath.split(pathSeparator)
        .map { File(it, executableName) }
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
}