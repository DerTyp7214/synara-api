package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ExternalLinkMeta(
    val type: Type
) {
    @Suppress("unused")
    enum class Type {
        TIDAL_SHARING,
        TIDAL_USER_SHARING,
        TIDAL_AUTOPLAY_ANDROID,
        TIDAL_AUTOPLAY_IOS,
        TIDAL_AUTOPLAY_WEB,
        TWITTER,
        FACEBOOK,
        INSTAGRAM,
        TIKTOK,
        SNAPCHAT,
        OFFICIAL_HOMEPAGE,
        CASHAPP_CONTRIBUTIONS;
    }
}

