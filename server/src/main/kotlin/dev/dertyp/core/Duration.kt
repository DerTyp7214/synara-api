package dev.dertyp.core

import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.Duration as KDuration

fun Duration.coerceAtLeast(kDuration: KDuration) = coerceAtLeast(kDuration.toJavaDuration())
operator fun Duration.compareTo(other: KDuration) = compareTo(other.toJavaDuration())
operator fun Duration.plus(other: KDuration): Duration = this + other.toJavaDuration()