package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsCreateOperationPayloadDataRelationships(
    val track: LyricsCreateOperationPayloadDataRelationshipsTrack
): BaseRelationships()