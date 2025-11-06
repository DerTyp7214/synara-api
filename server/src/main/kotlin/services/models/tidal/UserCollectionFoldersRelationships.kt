package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserCollectionFoldersRelationships<A : BaseAttributes, R : BaseRelationships>(
    val items: UserCollectionFoldersItemsMultiRelationshipDataDocument<A, R>,
    val owners: MultiRelationshipDataDocument
)