package dev.dertyp.db

import dev.dertyp.services.sync.SyncService
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object FavSyncTable : Table("favSync") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val service = enumeration<SyncService.SyncServiceType>("service")
    val syncedAt = long("syncedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(userId, service)
}