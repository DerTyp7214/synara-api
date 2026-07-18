package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object MBReleaseGroupCoverTable : Table("mb_release_group_cover") {
    val releaseGroupId = reference("releaseGroupId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE)
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val lastFetch = long("lastFetch").default(0L)

    override val primaryKey = PrimaryKey(releaseGroupId)
}
