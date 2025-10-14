package dev.dertyp.data

import dev.dertyp.serializers.DateSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class RefreshToken(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val tokenHash: String,
    val isRevoked: Boolean,
    @Serializable(with = UUIDSerializer::class)
    val userId: UUID,
    @Serializable(with = DateSerializer::class)
    val expiresAt: Date,
)
