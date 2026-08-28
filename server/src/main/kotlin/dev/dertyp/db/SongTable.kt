package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object SongTable : UUIDTable("song") {
    val title = text("title").default("")
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.SET_NULL)
    val duration = long("duration").default(0L)
    val releaseDate = varchar("releaseDate", 128).nullable()
    val lyrics = text("lyrics").default("")
    val explicit = bool("explicit").default(false)
    val filePath = text("filePath").default("")
    val format = varchar("format", 8).default("flac")
    val cover = reference("cover", ImageTable.id).nullable()
    val animatedCover = reference("animatedCover", AnimatedImageTable.id).nullable()
    val originalUrl = text("originalUrl").default("")
    val isrc = varchar("isrc", 32).nullable()
    val trackNumber = integer("trackNumber").default(1)
    val discNumber = integer("discNumber").default(1)
    val copyright = text("copyright").default("")
    val sampleRate = integer("sampleRate").default(0)
    val bitsPerSample = integer("bitsPerSample").default(0)
    val bitRate = long("bitRate").default(0)
    val fileSize = long("fileSize").default(0)
    val audioStartMs = long("audioStartMs").nullable()
    val atmosPath = text("atmosPath").nullable()
    val inserted = long("inserted").clientDefault { Instant.now().toEpochMilli() }
    val lastMetadataCheck = long("lastMetadataCheck").default(0L)
    val lastLyricsFetchAttempt = long("lastLyricsFetchAttempt").default(0L)
    val lastProviderEnrichment = long("lastProviderEnrichment").default(0L)
    val searchVector = tsvector("search_vector").nullable()
}
