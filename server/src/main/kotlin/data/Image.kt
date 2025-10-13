package dev.dertyp.data

import java.util.*

data class Image(
    val id: UUID,
    val path: String,
    val imageHash: String,
)

data class InsertableImage(
    val data: ByteArray,
    val imageHash: String,
)