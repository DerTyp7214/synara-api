package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class UsersAttributes(
    val country: String,
    val username: String,
    val email: String,
    val emailVerified: Boolean,
    val firstName: String? = null,
    val lastName: String? = null,
    val nostrPublicKey: String? = null
): BaseAttributes()