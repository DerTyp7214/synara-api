package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object ListenBackupConfigTable : IdTable<String>("listen_backup_config") {
    override val id = text("key").entityId()
    override val primaryKey = PrimaryKey(id)

    val enabled = bool("enabled").default(false)
    val url = text("url").default("")
    val apiKey = text("apiKey").nullable()
    val batchSize = integer("batchSize").default(1000)
    val serverId = javaUUID("serverId")
    val lastSyncedUpdatedAt = long("lastSyncedUpdatedAt").default(0L)
    val lastSyncAt = long("lastSyncAt").nullable()
    val lastSyncedCount = integer("lastSyncedCount").default(0)
    val lastError = text("lastError").nullable()

    const val DEFAULT_KEY = "default"
}
