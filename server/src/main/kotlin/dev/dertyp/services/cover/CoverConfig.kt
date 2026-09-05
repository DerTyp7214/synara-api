package dev.dertyp.services.cover

import io.ktor.server.config.ApplicationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CoverConfig(
    val assetsPath: String = "data/cover-assets",
    val nsfwPacks: Boolean = false,
    val autoGenerate: Boolean = true,
    val debounce: Duration = 30.seconds,
)

fun ApplicationConfig.toCoverConfig(): CoverConfig = CoverConfig(
    assetsPath = propertyOrNull("data.cover-assets")?.getString()?.takeIf { it.isNotBlank() } ?: "data/cover-assets",
    nsfwPacks = propertyOrNull("covers.nsfwPacks")?.getString()?.toBooleanStrictOrNull() ?: false,
    autoGenerate = propertyOrNull("covers.autoGenerate")?.getString()?.toBooleanStrictOrNull() ?: true,
    debounce = (propertyOrNull("covers.debounceSeconds")?.getString()?.toLongOrNull() ?: 30L).seconds,
)
