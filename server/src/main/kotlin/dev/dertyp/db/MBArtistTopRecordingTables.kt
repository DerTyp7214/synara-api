package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object MBArtistTopRecordingTable : Table("mb_artist_top_recording") {
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val recordingMbid = javaUUID("recordingMbid")
    val listenCount = long("listenCount")
    val userCount = long("userCount")

    override val primaryKey = PrimaryKey(artistId, recordingMbid)
}

object MBArtistTopRecordingFetchTable : Table("mb_artist_top_recording_fetch") {
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val fetchedAt = long("fetchedAt")

    override val primaryKey = PrimaryKey(artistId)
}
