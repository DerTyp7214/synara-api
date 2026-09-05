package dev.dertyp.services.cover

import dev.dertyp.data.CoverPackInfo
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class CoverPackManifest(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val nsfw: Boolean = false,
    val attribution: String? = null,
    val backgrounds: List<String> = emptyList(),
    val overlays: List<String> = emptyList(),
    val fonts: List<String> = emptyList(),
)

class CoverAssetPack(
    val manifest: CoverPackManifest,
    val dir: Path?,
    val backgrounds: List<Path>,
    val overlays: List<Path>,
    val fonts: List<Path>,
) {
    val id: String get() = manifest.id
    val nsfw: Boolean get() = manifest.nsfw
    val tags: Set<String> = manifest.tags.map { it.lowercase() }.toSet()
    val isBuiltin: Boolean get() = dir == null

    fun info() = CoverPackInfo(
        id = manifest.id,
        name = manifest.name,
        tags = manifest.tags,
        nsfw = manifest.nsfw,
        attribution = manifest.attribution,
    )

    companion object {
        const val BUILTIN_ID = "builtin"

        fun builtin() = CoverAssetPack(
            manifest = CoverPackManifest(id = BUILTIN_ID, name = "Built-in"),
            dir = null,
            backgrounds = emptyList(),
            overlays = emptyList(),
            fonts = emptyList(),
        )
    }
}
