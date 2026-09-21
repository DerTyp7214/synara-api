package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ProviderReleaseLinkTable : Table("provider_release_link") {
    val providerReleaseId = reference("providerReleaseId", ProviderReleaseTable.id, onDelete = ReferenceOption.CASCADE)
    val linkId = reference("linkId", ProviderLinkTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(providerReleaseId, linkId)

    init {
        index(false, linkId)
    }
}
