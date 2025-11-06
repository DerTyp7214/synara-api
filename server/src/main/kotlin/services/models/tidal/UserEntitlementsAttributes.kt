package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class UserEntitlementsAttributes(
    val entitlements: List<Entitlements>
): BaseAttributes() {
    @Suppress("unused")
    enum class Entitlements {
        MUSIC,
        DJ;
    }
}

