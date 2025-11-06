package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.DurationSerializer
import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import java.time.OffsetDateTime

@Serializable
data class PlaylistsAttributes(
    val accessType: AccessType,
    val bounded: Boolean,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    val externalLinks: List<ExternalLink>,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastModifiedAt: OffsetDateTime,
    val name: String,
    val playlistType: PlaylistType,
    val description: String? = null,
    @Serializable(with = DurationSerializer::class)
    val duration: Duration,
    val numberOfItems: Int? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class AccessType {
        PUBLIC,
        UNLISTED;
    }

    @Suppress("unused")
    enum class PlaylistType {
        EDITORIAL,
        USER,
        MIX,
        ARTIST;
    }
}

