package dev.dertyp.core

import java.util.*
import kotlin.time.Duration

operator fun Date.plus(duration: Duration): Date = Date(time + duration.inWholeMilliseconds)