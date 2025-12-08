package dev.dertyp.services.models

import dev.dertyp.serializers.DateSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class Album(
    val id: String,
    val title: String,
    val numberOfVolumes: Long,
    val numberOfItems: Long,
    val duration: Long,
    val explicit: Boolean,
    @Serializable(with = DateSerializer::class)
    val releaseDate: Date,
    val copyright: String,
    val coverUrl: String,
)