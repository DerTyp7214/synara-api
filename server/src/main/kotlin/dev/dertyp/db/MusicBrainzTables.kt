package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import java.util.UUID

abstract class MBIdTable(name: String) : IdTable<UUID>(name) {
    final override val id: Column<EntityID<UUID>> = varchar("id", 36)
        .clientDefault { UUID.randomUUID().toString() }
        .transform({ UUID.fromString(it) }, { it.toString() })
        .entityId()
    final override val primaryKey = PrimaryKey(id)
}

object MBAreaTable : MBIdTable("mb_area") {
    val name = text("name")
    val sortName = text("sortName")
    val type = varchar("type", 128).nullable()
    val disambiguation = text("disambiguation").nullable()
}

object MBArtistTable : MBIdTable("mb_artist") {
    val name = text("name")
    val sortName = text("sortName")
    val type = varchar("type", 128).nullable()
    val disambiguation = text("disambiguation").nullable()
    val country = varchar("country", 2).nullable()
    val area = reference("area", MBAreaTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val beginArea = reference("beginArea", MBAreaTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val lifeSpanBegin = varchar("lifeSpanBegin", 64).nullable()
    val lifeSpanEnd = varchar("lifeSpanEnd", 64).nullable()
    val lifeSpanEnded = bool("lifeSpanEnded").nullable()
    val score = integer("score").nullable()
    val lastUpdate = long("lastUpdate").default(0L)
}

object MBArtistAliasTable : LongIdTable("mb_artist_alias") {
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val sortName = text("sortName")
    val locale = varchar("locale", 16).nullable()
    val type = varchar("type", 128).nullable()
    val primary = bool("primary").default(false)
    val beginDate = varchar("beginDate", 64).nullable()
    val endDate = varchar("endDate", 64).nullable()
}

object MBArtistTagTable : Table("mb_artist_tag") {
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val count = integer("count").default(0)

    override val primaryKey = PrimaryKey(artistId, name)
}

object MBRecordingTable : MBIdTable("mb_recording") {
    val title = text("title")
    val length = long("length").nullable()
    val video = bool("video").nullable()
    val score = integer("score").nullable()
    val lastUpdate = long("lastUpdate").default(0L)
}

object MBReleaseGroupTable : MBIdTable("mb_release_group") {
    val title = text("title")
    val primaryType = varchar("primaryType", 128).nullable()
    val secondaryTypes = text("secondaryTypes").nullable()
    val disambiguation = text("disambiguation").nullable()
    val firstReleaseDate = varchar("firstReleaseDate", 128).nullable()
    val score = integer("score").nullable()
    val lastUpdate = long("lastUpdate").default(0L)
}

object MBReleaseTable : MBIdTable("mb_release") {
    val title = text("title")
    val status = varchar("status", 128).nullable()
    val quality = varchar("quality", 128).nullable()
    val barcode = varchar("barcode", 128).nullable()
    val country = varchar("country", 128).nullable()
    val date = varchar("date", 128).nullable()
    val disambiguation = text("disambiguation").nullable()
    val releaseGroupId = reference("releaseGroupId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val score = integer("score").nullable()
    val lastUpdate = long("lastUpdate").default(0L)
}

object MBReleaseGroupArtistCreditTable : Table("mb_release_group_artist_credit") {
    val releaseGroupId = reference("releaseGroupId", MBReleaseGroupTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val joinPhrase = text("joinPhrase").nullable()
    val position = integer("position")

    override val primaryKey = PrimaryKey(releaseGroupId, artistId, position)
}


object MBRecordingArtistCreditTable : Table("mb_recording_artist_credit") {
    val recordingId = reference("recordingId", MBRecordingTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val joinPhrase = text("joinPhrase").nullable()
    val position = integer("position")

    override val primaryKey = PrimaryKey(recordingId, artistId, position)
}

object MBReleaseArtistCreditTable : Table("mb_release_artist_credit") {
    val releaseId = reference("releaseId", MBReleaseTable.id, onDelete = ReferenceOption.CASCADE)
    val artistId = reference("artistId", MBArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val joinPhrase = text("joinPhrase").nullable()
    val position = integer("position")

    override val primaryKey = PrimaryKey(releaseId, artistId, position)
}

object MBRecordingReleaseTable : Table("mb_recording_release") {
    val recordingId = reference("recordingId", MBRecordingTable.id, onDelete = ReferenceOption.CASCADE)
    val releaseId = reference("releaseId", MBReleaseTable.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(recordingId, releaseId)
}

object MBMediaTable : LongIdTable("mb_media") {
    val releaseId = reference("releaseId", MBReleaseTable.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val format = varchar("format", 128).nullable()
    val trackCount = integer("trackCount").default(0)
}

object MBTrackTable : MBIdTable("mb_track") {
    val mediaId = reference("mediaId", MBMediaTable.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position").nullable()
    val number = varchar("number", 32).nullable()
    val title = text("title").nullable()
    val recordingId = reference("recordingId", MBRecordingTable.id, onDelete = ReferenceOption.CASCADE).nullable()
}

object MBRecordingIsrcTable : Table("mb_recording_isrc") {
    val recordingId = reference("recordingId", MBRecordingTable.id, onDelete = ReferenceOption.CASCADE)
    val isrc = varchar("isrc", 12)

    override val primaryKey = PrimaryKey(recordingId, isrc)
}

object MBRelationTable : Table("mb_relation") {
    val id = varchar("id", 36)
        .transform({ UUID.fromString(it) }, { it.toString() })
    val ownerId = varchar("ownerId", 36)
        .transform({ UUID.fromString(it) }, { it.toString() })
        .index()
    val type = varchar("type", 64)
    val resource = text("resource")

    override val primaryKey = PrimaryKey(id, ownerId)
}

val allMusicBrainzTables = arrayOf(
    MBAreaTable,
    MBArtistTable,
    MBArtistAliasTable,
    MBArtistTagTable,
    MBRecordingTable,
    MBReleaseGroupTable,
    MBReleaseTable,
    MBReleaseGroupArtistCreditTable,
    MBRecordingArtistCreditTable,
    MBReleaseArtistCreditTable,
    MBRecordingReleaseTable,
    MBMediaTable,
    MBTrackTable,
    MBRecordingIsrcTable,
    MBRelationTable,
    MBRelationProviderTable
)
