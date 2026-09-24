package dev.dertyp.services.release

import dev.dertyp.core.splitTitleTags
import dev.dertyp.data.ReleaseSource
import dev.dertyp.data.ReleaseType
import dev.dertyp.data.TitleTagKind
import java.util.UUID
import kotlin.time.Duration.Companion.days

object ReleaseVersions {

    val GROUP_WINDOW_MS: Long = 90.days.inWholeMilliseconds

    private val FORMAT_SUFFIX = Regex("""\s+-\s+(single|ep)\s*$""", RegexOption.IGNORE_CASE)

    private val EXPLICIT_OR_CLEAN =
        Regex("""[(\[]\s*(explicit|clean)\s*[)\]]|\s+-\s+(explicit|clean)\s*$""", RegexOption.IGNORE_CASE)

    private val BRACKET_SEGMENT = Regex("""\s*[(\[]([^()\[\]]*)[)\]]""")

    private val WHITESPACE = Regex("\\s+")

    private val VERSION_KINDS = setOf(
        TitleTagKind.VERSION,
        TitleTagKind.REMASTER,
        TitleTagKind.EDIT,
        TitleTagKind.INSTRUMENTAL
    )

    data class VersionKey(val value: String, val versionTagCount: Int)

    data class Facet(
        val artistId: UUID,
        val type: ReleaseType,
        val key: VersionKey,
        val date: Long?,
        val id: UUID,
        val source: ReleaseSource,
        val suspect: Boolean,
        val hidden: Boolean
    )

    class Member<T>(val item: T, val facet: Facet)

    private data class Bucket(val artistId: UUID, val type: ReleaseType, val key: String, val hidden: Boolean)

    fun versionKey(title: String): VersionKey {
        val stripped = FORMAT_SUFFIX.replace(title.trim(), "").trim()
        val split = stripped.splitTitleTags()
        val (versionTags, otherTags) = split.tags.partition { it.kind in VERSION_KINDS }
        val unclassified = BRACKET_SEGMENT.findAll(split.title)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
        val base = BRACKET_SEGMENT.replace(split.title, "").takeIf { it.isNotBlank() } ?: split.title
        val labels = (otherTags.map { it.label } + unclassified).map { norm(it) }.sorted()
        return VersionKey(
            value = (listOf(norm(base)) + labels).joinToString("|"),
            versionTagCount = versionTags.size + EXPLICIT_OR_CLEAN.findAll(stripped).count()
        )
    }

    fun <T> cluster(items: List<T>, facet: (T) -> Facet): List<List<Member<T>>> {
        val members = items.map { Member(it, facet(it)) }
        val undated = members.filter { it.facet.date == null }.map { listOf(it) }
        val dated = members
            .filter { it.facet.date != null }
            .groupBy { Bucket(it.facet.artistId, it.facet.type, it.facet.key.value, it.facet.hidden) }
            .values
            .flatMap { bucket ->
                val sorted = bucket.sortedWith(compareBy<Member<T>> { it.facet.date }.thenBy { it.facet.id })
                val groups = mutableListOf<List<Member<T>>>()
                var current = mutableListOf<Member<T>>()
                var anchor = 0L
                sorted.forEach { member ->
                    val date = member.facet.date!!
                    if (current.isNotEmpty() && date - anchor <= GROUP_WINDOW_MS) {
                        current += member
                    } else {
                        if (current.isNotEmpty()) groups += current
                        current = mutableListOf(member)
                        anchor = date
                    }
                }
                if (current.isNotEmpty()) groups += current
                groups
            }
        return dated + undated
    }

    fun <T> primary(members: List<Member<T>>): Member<T> = members.minWith(
        compareBy<Member<T>> { it.facet.source != ReleaseSource.MusicBrainz }
            .thenBy { it.facet.suspect }
            .thenBy { it.facet.key.versionTagCount }
            .thenBy { it.facet.date ?: Long.MAX_VALUE }
            .thenBy { it.facet.id }
    )

    private fun norm(value: String): String = WHITESPACE.replace(value.lowercase(), " ").trim()
}
