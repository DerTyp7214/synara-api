package dev.dertyp.core

import java.time.Instant
import kotlin.time.Duration

operator fun Instant.plus(duration: Duration): Instant {
    return Instant.ofEpochMilli(this.toEpochMilli() + duration.inWholeMilliseconds)
}