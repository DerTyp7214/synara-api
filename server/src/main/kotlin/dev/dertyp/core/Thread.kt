package dev.dertyp.core

import java.lang.Thread.sleep
import kotlin.time.Duration
import kotlin.time.toJavaDuration

fun sleep(duration: Duration) = sleep(duration.toJavaDuration())