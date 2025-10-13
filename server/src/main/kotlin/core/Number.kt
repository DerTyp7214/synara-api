package dev.dertyp.core

import kotlin.math.pow
import kotlin.math.roundToInt


fun Float.roundToNDecimals(n: Int = 0): Float {
    return (this * 10.0.pow(n)).roundToInt() / 100.0f
}

fun Double.roundToNDecimals(n: Int = 0): Double {
    return (this * 10.0.pow(n)).roundToInt() / 100.0
}