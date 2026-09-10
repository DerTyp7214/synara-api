package dev.dertyp.db

import dev.dertyp.data.RepeatMode
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.time.Instant

object UserQueueTable : Table("userQueue") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val version = long("version").default(0)
    val modifiedAt = long("modifiedAt").clientDefault { Instant.now().toEpochMilli() }
    val modifiedBySessionId = javaUUID("modifiedBySessionId").nullable()
    val modifiedByDeviceName = text("modifiedByDeviceName").nullable()
    val currentIndex = integer("currentIndex").default(0)
    val shuffleMode = bool("shuffleMode").default(false)
    val repeatMode = enumerationByName("repeatMode", 16, RepeatMode::class).default(RepeatMode.OFF)
    val sourceId = text("sourceId").nullable()

    override val primaryKey = PrimaryKey(userId)
}

object UserQueueEntryTable : Table("userQueueEntry") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val queueId = long("queueId")
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position")
    val shuffledPosition = integer("shuffledPosition").nullable()
    val explicit = bool("explicit").default(false)

    override val primaryKey = PrimaryKey(userId, queueId)

    init {
        index(false, userId, position)
    }
}

object QueueSyncDeviceTable : Table("queueSyncDevice") {
    val sessionId = reference("sessionId", SessionTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceName = text("deviceName").default("")
    val enabled = bool("enabled").default(true)
    val lastSyncedVersion = long("lastSyncedVersion").default(0)
    val lastSyncAt = long("lastSyncAt").default(0)

    override val primaryKey = PrimaryKey(sessionId)

    init {
        index(false, userId)
    }
}
