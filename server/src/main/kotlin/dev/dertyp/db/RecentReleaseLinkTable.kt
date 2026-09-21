package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object RecentReleaseLinkTable : Table("recent_release_link") {
    val releaseId = reference("releaseId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE)
    val linkId = reference("linkId", ProviderLinkTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(releaseId, linkId)

    init {
        index(false, linkId)
    }
}
