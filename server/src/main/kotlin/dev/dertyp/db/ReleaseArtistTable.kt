package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ReleaseArtistTable : Table("release_artist") {
    val releaseGroupId = reference("releaseGroupId", RecentReleaseTable.releaseId, onDelete = ReferenceOption.CASCADE).nullable()
    val providerReleaseId = reference("providerReleaseId", ProviderReleaseTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)

    init {
        uniqueIndex(releaseGroupId, artistId)
        uniqueIndex(providerReleaseId, artistId)
        index(false, artistId)
    }
}
