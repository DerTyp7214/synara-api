package dev.dertyp.services.metadata

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.process.ExternalTool
import dev.dertyp.services.Service
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

data class Fingerprint(val duration: Int, val fingerprint: String)

@Serializable
private data class FpcalcOutput(val duration: Double, val fingerprint: String)

open class AcoustIdFingerprintService : Service() {
    private val fpcalc = ExternalTool("fpcalc")
    protected open val fpcalcPath: String? get() = fpcalc.path
    private val missingLogged = AtomicBoolean(false)

    suspend fun fingerprint(path: String): Fingerprint? {
        if (fpcalcPath == null) {
            if (missingLogged.compareAndSet(false, true)) {
                logger.warn("fpcalc not found in PATH. AcoustID fingerprinting is disabled.")
            }
            return null
        }
        val output = try {
            runFpcalc(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("fpcalc failed for $path: ${e.message}", e)
            null
        } ?: return null
        return parse(output).also {
            if (it == null) logger.warn("Could not parse fpcalc output for $path")
        }
    }

    protected open suspend fun runFpcalc(path: String): String? {
        val result = fpcalc.run(
            args = listOf("-json", path),
            logger = logger,
            logCommand = false,
        ) ?: return null
        return result.fullOutput.takeIf { result.exitCode == 0 }
    }

    private fun parse(output: String): Fingerprint? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val parsed = ApplicationScope.json.decodeFromString<FpcalcOutput>(output.substring(start, end + 1))
            parsed.fingerprint.takeIf { it.isNotBlank() }?.let { Fingerprint(parsed.duration.toInt(), it) }
        } catch (_: Exception) {
            null
        }
    }
}
