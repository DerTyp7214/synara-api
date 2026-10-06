package dev.dertyp.services.release

import dev.dertyp.core.uuidOrder
import dev.dertyp.data.TitleTag
import java.time.LocalDate
import java.util.UUID

object AlbumVersionGroups {

    data class Edition(
        val id: UUID,
        val name: String,
        val tags: List<TitleTag>,
        val artistIds: Set<UUID>,
        val coverId: UUID?,
        val releaseGroupId: UUID?,
        val releaseDate: LocalDate?,
        val explicit: Boolean
    )

    data class Assignment(
        val moved: Map<UUID, List<UUID>>,
        val created: List<List<UUID>>,
        val unused: Set<UUID>
    )

    private data class Claim(val group: Int, val groupId: UUID, val holders: Int, val smallestAlbumId: UUID)

    private data class Identity(val name: String, val artistIds: Set<UUID>)

    private val whitespace = Regex("\\s+")

    private val idOrder: Comparator<Edition> = compareBy(uuidOrder) { it.id }

    fun groups(editions: List<Edition>): List<List<Edition>> {
        val unique = editions.distinctBy { it.id }
        val (linked, unlinked) = unique.partition { it.releaseGroupId != null }
        val byReleaseGroup = linked.groupBy { it.releaseGroupId!! }

        val releaseGroupsByIdentity = mutableMapOf<Identity, MutableSet<UUID>>()
        val releaseGroupsByCover = mutableMapOf<UUID, MutableSet<UUID>>()
        for ((releaseGroupId, members) in byReleaseGroup) {
            for (member in members) {
                identity(member)?.let { releaseGroupsByIdentity.getOrPut(it) { mutableSetOf() } += releaseGroupId }
                member.coverId?.let { releaseGroupsByCover.getOrPut(it) { mutableSetOf() } += releaseGroupId }
            }
        }

        val attached = mutableMapOf<UUID, MutableList<Edition>>()
        val standalone = mutableListOf<List<Edition>>()
        for (cluster in clusters(unlinked)) {
            val matches = cluster.flatMapTo(mutableSetOf()) { member ->
                identity(member)?.let { releaseGroupsByIdentity[it] }.orEmpty() +
                        member.coverId?.let { releaseGroupsByCover[it] }.orEmpty()
            }
            if (matches.size == 1) attached.getOrPut(matches.single()) { mutableListOf() } += cluster
            else standalone += cluster
        }

        val merged = byReleaseGroup.map { (releaseGroupId, members) -> members + attached[releaseGroupId].orEmpty() }
        return (merged + standalone)
            .map { it.sortedWith(idOrder) }
            .sortedWith(compareBy(uuidOrder) { it.first().id })
    }

    fun assign(groups: List<List<Edition>>, current: Map<UUID, UUID?>): Assignment {
        val members = groups.map { group -> group.map { it.id } }.filter { it.isNotEmpty() }

        val claims = members.mapIndexedNotNull { index, albumIds ->
            albumIds
                .mapNotNull { current[it] }
                .groupingBy { it }
                .eachCount()
                .entries
                .minWithOrNull(compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy(uuidOrder) { it.key })
                ?.let { Claim(index, it.key, it.value, albumIds.minWith(uuidOrder)) }
        }

        val kept = claims.groupBy { it.groupId }.values.associate { rivals ->
            val winner = rivals.minWith(
                compareByDescending<Claim> { it.holders }.thenBy(uuidOrder) { it.smallestAlbumId }
            )
            winner.group to winner.groupId
        }

        val moved = mutableMapOf<UUID, List<UUID>>()
        val created = mutableListOf<List<UUID>>()
        members.forEachIndexed { index, albumIds ->
            val groupId = kept[index]
            if (groupId == null) {
                created += albumIds
            } else {
                val changed = albumIds.filter { current[it] != groupId }
                if (changed.isNotEmpty()) moved[groupId] = changed
            }
        }

        return Assignment(
            moved = moved,
            created = created,
            unused = current.values.filterNotNullTo(mutableSetOf()) - kept.values.toSet()
        )
    }

    fun mainFirst(members: List<Edition>, explicit: Boolean): List<Edition> {
        val mixed = members.any { it.explicit } && members.any { !it.explicit }
        return members.sortedWith(
            compareBy<Edition> { mixed && it.explicit != explicit }
                .thenBy { it.tags.size }
                .thenBy { it.releaseDate == null }
                .thenBy { it.releaseDate }
                .then(idOrder)
        )
    }

    private fun identity(edition: Edition): Identity? =
        if (edition.artistIds.isEmpty()) null
        else Identity(whitespace.replace(edition.name.lowercase(), " ").trim(), edition.artistIds)

    private fun clusters(editions: List<Edition>): List<List<Edition>> {
        val parent = IntArray(editions.size) { it }

        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun <K> joinBy(key: (Edition) -> K?) {
            val first = mutableMapOf<K, Int>()
            editions.forEachIndexed { index, edition ->
                val value = key(edition) ?: return@forEachIndexed
                val seen = first.getOrPut(value) { index }
                if (seen != index) parent[root(index)] = root(seen)
            }
        }

        joinBy { identity(it) }
        joinBy { it.coverId }

        return editions.indices.groupBy({ root(it) }, { editions[it] }).values.toList()
    }
}
