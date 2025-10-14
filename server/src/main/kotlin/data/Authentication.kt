package dev.dertyp.data

import dev.dertyp.serializers.DateSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class AuthenticationRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthenticationResponse(
    val token: String,
    val refreshToken: String,
    @Serializable(with = DateSerializer::class)
    val expiresAt: Date,
)