package dev.dertyp.core

import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt


fun Float.roundToNDecimals(n: Int = 0): Float {
    return (this * 10.0.pow(n)).roundToInt() / 100.0f
}

fun Double.roundToNDecimals(n: Int = 0): Double {
    return (this * 10.0.pow(n)).roundToInt() / 100.0
}

fun Int.digitCount(): Int = when (this) {
    0 -> 1
    else -> log10(this.toDouble().absoluteValue).toInt() + 1
}

fun Int.zeroPad(length: Int): String {
    return this.toString().padStart(length, '0')
}

val Long.date get() = Date(this)
val Long?.date get() = this?.let { Date(this) }