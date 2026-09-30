package dev.dertyp

import io.ktor.http.ContentType
import java.io.File

data class StreamInfo(
    val file: File,
    val contentType: ContentType,
    val contentLength: Long,
    val fileName: String,
)
