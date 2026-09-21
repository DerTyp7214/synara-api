package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object HiddenReleaseTable : Table("hidden_release") {
    val releaseGroupId = reference("releaseGroupId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val providerReleaseId = reference("providerReleaseId", ProviderReleaseTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val hiddenBy = reference("hiddenBy", UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val hiddenAt = long("hiddenAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(releaseGroupId)
        uniqueIndex(providerReleaseId)
        index(false, artistId)
    }
}
