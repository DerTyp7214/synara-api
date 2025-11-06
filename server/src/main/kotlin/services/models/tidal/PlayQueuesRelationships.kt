package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class PlayQueuesRelationships<A : BaseAttributes, R : BaseRelationships>(
    val current: SingleRelationshipDataDocument,
    val future: PlayQueuesFutureMultiRelationshipDataDocument<A, R>,
    val owners: MultiRelationshipDataDocument,
    val past: PlayQueuesPastMultiRelationshipDataDocument<A, R>
)