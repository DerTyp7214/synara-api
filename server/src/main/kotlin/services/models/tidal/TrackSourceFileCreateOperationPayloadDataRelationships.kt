package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackSourceFileCreateOperationPayloadDataRelationships(
    val track: AttachSourceFileToTrack
): BaseRelationships()