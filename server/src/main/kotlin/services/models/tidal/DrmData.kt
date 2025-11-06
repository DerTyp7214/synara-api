package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class DrmData(
    val certificateUrl: String? = null,
    val drmSystem: DrmSystem? = null,
    val licenseUrl: String? = null
) {
    @Suppress("unused")
    enum class DrmSystem {
        FAIRPLAY,
        WIDEVINE;
    }
}

