package dev.dertyp.db

import dev.dertyp.core.withTitleTags
import dev.dertyp.data.TitleTag
import dev.dertyp.serializers.AppJson
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.ResultRow

fun encodeTitleTags(tags: List<TitleTag>): String = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags)

fun decodeTitleTags(raw: String?): List<TitleTag> = raw
    ?.takeIf { it.isNotBlank() && it != "[]" }
    ?.let { runCatching { AppJson.decodeFromString<List<TitleTag>>(it) }.getOrNull() }
    ?: emptyList()

fun ResultRow.titleTags(): List<TitleTag> = decodeTitleTags(getOrNull(SongTable.titleTags))

fun ResultRow.fullSongTitle(): String = this[SongTable.title].withTitleTags(titleTags())
