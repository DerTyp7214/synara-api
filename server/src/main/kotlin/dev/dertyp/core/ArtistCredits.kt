package dev.dertyp.core

import dev.dertyp.data.Artist
import dev.dertyp.data.Genre
import java.util.UUID

data class ResolvedCredit(val artist: Artist, val creditedName: String?, val joinPhrase: String?)

data class CreditLink(val ownerId: UUID, val artistId: UUID, val position: Int, val joinPhrase: String?)

val uuidOrder: Comparator<UUID> = compareBy { it.toString() }

val creditLinkOrder: Comparator<CreditLink> = compareBy<CreditLink> { it.position }.thenBy(uuidOrder) { it.artistId }

fun List<CreditLink>.mergedPerOwner(): List<CreditLink> =
    groupBy { it.ownerId }.values.map { links ->
        val ordered = links.sortedWith(creditLinkOrder)
        ordered.first().copy(joinPhrase = ordered.last().joinPhrase)
    }

fun List<CreditLink>.splitInto(targetIds: List<UUID>, existingLinks: Set<Pair<UUID, UUID>>, keepsOriginal: Boolean): List<CreditLink> =
    flatMap { original ->
        val added = targetIds
            .filter { it != original.artistId && (original.ownerId to it) !in existingLinks }
            .sortedWith(uuidOrder)
        added.mapIndexed { index, targetId ->
            CreditLink(
                ownerId = original.ownerId,
                artistId = targetId,
                position = original.position,
                joinPhrase = original.joinPhrase.takeIf { !keepsOriginal && index == added.lastIndex },
            )
        }
    }

fun List<Artist>.inCreditOrder(position: (Artist) -> Int): List<Artist> =
    sortedWith(compareBy<Artist> { position(it) }.thenBy(uuidOrder) { it.id })

fun List<Genre>.inNameOrder(): List<Genre> =
    sortedWith(compareBy<Genre> { it.name }.thenBy(uuidOrder) { it.id })
