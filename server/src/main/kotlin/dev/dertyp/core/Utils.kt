package dev.dertyp.core

import kotlinx.coroutines.delay
import java.io.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue


data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
) : Serializable {
    override fun toString(): String = "($first, $second, $third, $fourth)"
}

data class Quintuple<out A, out B, out C, out D, out E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
): Serializable {
    override fun toString(): String = "($first, $second, $third, $fourth, $fifth)"
}


fun <T, K> List<T>.duplicatesBy(keySelector: (T) -> K): List<T> {
    return this.groupBy(keySelector)
        .filterValues { it.size > 1 }
        .values
        .flatten()
}

fun Number.toHumanReadableSize(): String {
    val bytes = this.toLong()
    if (bytes <= 0) return "0 Bytes"

    val units = arrayOf("Bytes", "KB", "MB", "GB", "TB", "PB", "EB")
    val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val size = bytes / 1024.0.pow(i.toDouble())
    val unit = units.getOrElse(i) { units.last() }

    return "%.1f %s".format(size, unit)
}

fun <T> logTimeSplit(label: String, block: () -> T): T {
    val result: TimedValue<T> = measureTimedValue(block)

    println("Time Split: | $label | took ${result.duration}")

    return result.value
}

suspend fun <T> logTimeSplitSuspend(label: String, block: suspend () -> T): T {
    val result: TimedValue<T> = measureTimedValue {
        block()
    }

    // Log the split time to the console
    println("Time Split (Suspend): | $label | took ${result.duration}")

    return result.value
}

@OptIn(ExperimentalAtomicApi::class)
suspend fun AtomicBoolean.waitForChange(expected: Boolean = true) {
    val pollIntervalMs = 100L

    while (load() != expected) {
        delay(pollIntervalMs.milliseconds)
    }
}