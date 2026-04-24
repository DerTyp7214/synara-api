package dev.dertyp.core

import java.time.Duration
import java.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.Duration as KDuration

fun Duration.coerceAtLeast(kDuration: KDuration) = coerceAtLeast(kDuration.toJavaDuration())
operator fun Duration.compareTo(other: KDuration) = compareTo(other.toJavaDuration())
operator fun Duration.plus(other: KDuration): Duration = this + other.toJavaDuration()
operator fun Instant.plus(other: KDuration): Instant = this.plus(other.toJavaDuration())
