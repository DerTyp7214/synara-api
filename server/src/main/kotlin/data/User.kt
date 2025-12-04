package dev.dertyp.data

import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class User(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val username: String,
    val passwordHash: String,
)

@Serializable
data class UserInfo(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val username: String,
) {
    companion object {
        fun fromUser(user: User): UserInfo {
            return UserInfo(
                id = user.id,
                username = user.username,
            )
        }
    }
}