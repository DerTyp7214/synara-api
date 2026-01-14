package dev.dertyp.core

import io.ktor.http.*

fun Url.tidalId(): String = segments.last { s -> s != "u" }