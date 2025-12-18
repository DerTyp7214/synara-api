package dev.dertyp.data

import dev.dertyp.serializers.DateSerializer
import dev.dertyp.serializers.UUIDSerializer
import dev.dertyp.services.sync.SyncService
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class FavSync(
    @Serializable(with = UUIDSerializer::class)
    val userId: UUID,
    val service: SyncService.SyncServiceType,
    @Serializable(with = DateSerializer::class)
    val syncedAt: Date,
)
