package dev.dertyp.db

import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisodeType
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastSource
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object PodcastShowTable : UUIDTable("podcastShow") {
    val showSource = enumerationByName("source", 16, PodcastSource::class)
    val sourceKey = varchar("sourceKey", 72)
    val feedUrl = text("feedUrl").nullable()
    val localPath = text("localPath").nullable()
    val title = text("title")
    val description = text("description").default("")
    val author = text("author").nullable()
    val language = varchar("language", 16).nullable()
    val link = text("link").nullable()
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val imageUrl = text("imageUrl").nullable()
    val explicit = bool("explicit").default(false)
    val deliveryMode = enumerationByName("deliveryMode", 16, PodcastDeliveryMode::class)
        .default(PodcastDeliveryMode.STREAM)
    val keepEpisodes = integer("keepEpisodes").nullable()
    val retention = enumerationByName("retention", 16, PodcastRetention::class)
        .default(PodcastRetention.NEWEST)
    val etag = varchar("etag", 255).nullable()
    val lastModified = varchar("lastModified", 64).nullable()
    val lastFetchedAt = long("lastFetchedAt").nullable()
    val lastFetchError = text("lastFetchError").nullable()
    val orphanedAt = long("orphanedAt").nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
    val updatedAt = long("updatedAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(sourceKey)
        index(false, showSource, orphanedAt)
    }
}

object PodcastEpisodeTable : UUIDTable("podcastEpisode") {
    val showId = reference("showId", PodcastShowTable.id, onDelete = ReferenceOption.CASCADE)
    val guid = text("guid")
    val guidKey = varchar("guidKey", 64)
    val title = text("title")
    val description = text("description").default("")
    val link = text("link").nullable()
    val publishedAt = long("publishedAt")
    val durationMs = long("durationMs").nullable()
    val enclosureUrl = text("enclosureUrl").nullable()
    val enclosureType = varchar("enclosureType", 64).nullable()
    val enclosureLength = long("enclosureLength").nullable()
    val filePath = text("filePath").nullable()
    val fileSize = long("fileSize").nullable()
    val format = varchar("format", 8).nullable()
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val seasonNumber = integer("seasonNumber").nullable()
    val episodeNumber = integer("episodeNumber").nullable()
    val episodeType = enumerationByName("episodeType", 16, PodcastEpisodeType::class)
        .default(PodcastEpisodeType.FULL)
    val explicit = bool("explicit").default(false)
    val importState = enumerationByName("importState", 16, PodcastImportState::class)
        .default(PodcastImportState.NONE)
    val importAttempts = integer("importAttempts").default(0)
    val importError = text("importError").nullable()
    val importedAt = long("importedAt").nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
    val updatedAt = long("updatedAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(showId, guidKey)
        index(false, showId, publishedAt)
        index(false, importState)
    }
}

object PodcastTranscriptTable : UUIDTable("podcastTranscript") {
    val episodeId = reference("episodeId", PodcastEpisodeTable.id, onDelete = ReferenceOption.CASCADE)
    val sourceKey = varchar("sourceKey", 64)
    val url = text("url").nullable()
    val filePath = text("filePath").nullable()
    val type = varchar("type", 64)
    val language = varchar("language", 16).nullable()
    val rel = varchar("rel", 32).nullable()
    val content = text("content").nullable()
    val fetchedAt = long("fetchedAt").nullable()
    val fetchError = text("fetchError").nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        uniqueIndex(episodeId, sourceKey)
    }
}

object PodcastSubscriptionTable : Table("podcastSubscription") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val showId = reference("showId", PodcastShowTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(userId, showId)

    init {
        index(false, showId)
    }
}

object PodcastEpisodeProgressTable : Table("podcastEpisodeProgress") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val episodeId = reference("episodeId", PodcastEpisodeTable.id, onDelete = ReferenceOption.CASCADE)
    val positionMs = long("positionMs").default(0)
    val durationMs = long("durationMs").nullable()
    val completed = bool("completed").default(false)
    val lastPlayedAt = long("lastPlayedAt").clientDefault { Instant.now().toEpochMilli() }
    val updatedAt = long("updatedAt").clientDefault { Instant.now().toEpochMilli() }
    val deviceId = varchar("deviceId", 64).nullable()

    override val primaryKey = PrimaryKey(userId, episodeId)

    init {
        index(false, userId, lastPlayedAt)
        index(false, userId, completed)
    }
}
