package dev.dertyp.services.release

import dev.dertyp.core.classifyAlbumTitleTag
import dev.dertyp.core.mergeTitleTags
import dev.dertyp.core.splitAlbumTitleTags
import dev.dertyp.core.withTitleTags
import dev.dertyp.data.TitleTag

object AlbumEditions {

    fun fullName(releaseTitle: String?, disambiguation: String?, providerTitle: String?): String? {
        val release = releaseTitle?.takeIf { it.isNotBlank() }?.splitAlbumTitleTags() ?: return providerTitle
        val edition = disambiguation?.trim()?.let { label ->
            classifyAlbumTitleTag(label)?.let { kind -> TitleTag(kind, label) }
        }
        val tags = release.tags.mergeTitleTags(listOfNotNull(edition))
            .ifEmpty { providerTitle?.splitAlbumTitleTags()?.tags.orEmpty() }
        return release.title.withTitleTags(tags)
    }
}
