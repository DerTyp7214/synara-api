package dev.dertyp.db

import dev.dertyp.data.ClientSettingScope
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object ClientDeviceTable : Table("clientDevice") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("deviceId", 64)
    val name = text("name").default("")
    val platform = varchar("platform", 32).default("")
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
    val lastSeenAt = long("lastSeenAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(userId, deviceId)

    init {
        index(false, lastSeenAt)
    }
}

object ClientSettingScopeTable : Table("clientSettingScope") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val scope = enumerationByName("scope", 16, ClientSettingScope::class)
    val deviceId = varchar("deviceId", 64).default("")
    val version = long("version").default(0)
    val modifiedAt = long("modifiedAt").default(0)
    val purgedVersion = long("purgedVersion").default(0)

    override val primaryKey = PrimaryKey(userId, scope, deviceId)
}

object ClientSettingTable : Table("clientSetting") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val scope = enumerationByName("scope", 16, ClientSettingScope::class)
    val deviceId = varchar("deviceId", 64).default("")
    val key = varchar("key", 255)
    val value = text("value").nullable()
    val deleted = bool("deleted").default(false)
    val version = long("version").default(0)
    val modifiedAt = long("modifiedAt").default(0)
    val modifiedByDeviceId = varchar("modifiedByDeviceId", 64).nullable()

    override val primaryKey = PrimaryKey(userId, scope, deviceId, key)

    init {
        index(false, userId, scope, deviceId, version)
        index(false, deleted, modifiedAt)
    }
}

object ClientSettingHistoryTable : Table("clientSettingHistory") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val scope = enumerationByName("scope", 16, ClientSettingScope::class)
    val deviceId = varchar("deviceId", 64).default("")
    val key = varchar("key", 255)
    val version = long("version")
    val value = text("value").nullable()
    val deleted = bool("deleted").default(false)
    val modifiedAt = long("modifiedAt").default(0)
    val modifiedByDeviceId = varchar("modifiedByDeviceId", 64).nullable()

    override val primaryKey = PrimaryKey(userId, scope, deviceId, key, version)
}
