package dev.dertyp.core

import io.ktor.util.logging.*

private val logger = KtorSimpleLogger("Process")

fun Process.kill(): Boolean {
    return try {
        val pid = this.pid()
        logger.info("killing: $pid (${this.info().commandLine()?.get()})")

        ProcessBuilder("kill", "-9", pid.toString()).start()
        true
    } catch (_: Exception) {
        false
    }
}