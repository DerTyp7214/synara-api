package dev.dertyp.core

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.FollowedArtistTable
import org.jetbrains.exposed.v1.core.*
import java.util.UUID

val followedArtistAlias = FollowedArtistTable.alias("followedArtist")

fun ColumnSet.followedArtist(
    userId: UUID?,
    alias: Alias<FollowedArtistTable> = followedArtistAlias,
    artistId: Expression<*> = ArtistTable.id,
): ColumnSet = if (userId != null) {
    leftJoin(
        alias,
        onColumn = { artistId },
        otherColumn = { alias[FollowedArtistTable.artistId] },
        additionalConstraint = { alias[FollowedArtistTable.userId] eq userId }
    )
} else this

fun <T> orderByIds(ids: List<UUID>, items: List<T>, id: (T) -> UUID): List<T> {
    val byId = items.distinctBy(id).associateBy(id)
    return ids.mapNotNull { byId[it] }
}

fun <T> idOrderedPage(
    ids: List<UUID>,
    items: List<T>,
    total: Long,
    page: Int,
    pageSize: Int,
    id: (T) -> UUID,
): PaginatedResponse<T> = PaginatedResponse(
    data = orderByIds(ids, items, id),
    total = total.toInt(),
    page = page,
    pageSize = pageSize,
    hasNextPage = (page + 1).toLong() * pageSize < total,
)
