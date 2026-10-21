package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.UserInfo
import dev.dertyp.data.UserProfile
import dev.dertyp.db.*
import dev.dertyp.services.ui.UiRegistry
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject

data class ListenShareLink(
    val id: PlatformUUID,
    val partner: UserProfile,
    val useForRecommendations: Boolean,
    val createdAt: Long,
)

data class ListenSharePair(
    val id: PlatformUUID,
    val userA: UserProfile,
    val userB: UserProfile,
    val useForRecommendations: Boolean,
    val createdAt: Long,
)

class ListenShareService : Service() {
    companion object {
        const val SETTINGS_CONTRIBUTION_ID = "listenShare.settings"
        const val ADMIN_CONTRIBUTION_ID = "listenShare.admin"
    }

    private val listenService by inject<ListenService>()
    private val uiRegistry by inject<UiRegistry>()

    suspend fun linksOf(userId: PlatformUUID): List<ListenShareLink> = dbQuery {
        val pairs = pairs((ListenShareTable.userA eq userId) or (ListenShareTable.userB eq userId))
        pairs.map {
            ListenShareLink(
                id = it.id,
                partner = if (it.userA.id == userId) it.userB else it.userA,
                useForRecommendations = it.useForRecommendations,
                createdAt = it.createdAt,
            )
        }.sortedBy { it.partner.username.lowercase() }
    }

    suspend fun allLinks(): List<ListenSharePair> = dbQuery {
        pairs(null).sortedWith(compareBy({ it.userA.username.lowercase() }, { it.userB.username.lowercase() }))
    }

    suspend fun candidates(userId: PlatformUUID): List<UserProfile> = dbQuery {
        val listenBrainzUserId = listenBrainzUserIdOf(userId) ?: return@dbQuery emptyList()
        val linked = listenOwnerIds(userId, ListenScope.SHARED)
        val candidateIds = UserListenBrainzLinkTable
            .select(UserListenBrainzLinkTable.userId)
            .where { UserListenBrainzLinkTable.listenBrainzUserId eq listenBrainzUserId }
            .map { it[UserListenBrainzLinkTable.userId].value }
            .filter { it !in linked }
        profiles(candidateIds).values.sortedBy { it.username.lowercase() }
    }

    suspend fun ownerIds(userId: PlatformUUID, scope: ListenScope): Set<PlatformUUID> =
        dbQuery { listenOwnerIds(userId, scope) }

    suspend fun link(
        actor: UserInfo,
        userA: PlatformUUID,
        userB: PlatformUUID,
        useForRecommendations: Boolean,
    ): ListenSharePair {
        require(userA != userB) { "A user cannot be linked to itself" }
        val (first, second) = if (userA < userB) userA to userB else userB to userA

        val pair = dbQuery {
            val existing = UserTable
                .select(UserTable.id)
                .where { UserTable.id inList listOf(first, second) }
                .count()
            require(existing == 2L) { "Unknown user" }
            if (!actor.isAdmin) {
                if (actor.id != first && actor.id != second) {
                    throw UnauthorizedException("Only an admin can link other users")
                }
                val firstAccount = listenBrainzUserIdOf(first)
                if (firstAccount == null || firstAccount != listenBrainzUserIdOf(second)) {
                    throw UnauthorizedException("Both users must be linked to the same ListenBrainz account")
                }
            }
            require(
                ListenShareTable.select(ListenShareTable.id)
                    .where { ListenShareTable.userA eq first }
                    .andWhere { ListenShareTable.userB eq second }
                    .empty()
            ) { "These users are already linked" }

            val id = ListenShareTable.insertAndGetId {
                it[ListenShareTable.userA] = first
                it[ListenShareTable.userB] = second
                it[ListenShareTable.useForRecommendations] = useForRecommendations
            }.value
            pairs(ListenShareTable.id eq id).single()
        }

        changed(first, second)
        return pair
    }

    suspend fun unlink(actor: UserInfo, linkId: PlatformUUID) {
        val (userA, userB) = dbQuery {
            val members = membersFor(actor, linkId)
            ListenShareTable.deleteWhere { ListenShareTable.id eq linkId }
            members
        }
        changed(userA, userB)
    }

    suspend fun setUseForRecommendations(actor: UserInfo, linkId: PlatformUUID, useForRecommendations: Boolean) {
        val (userA, userB) = dbQuery {
            val members = membersFor(actor, linkId)
            ListenShareTable.update({ ListenShareTable.id eq linkId }) {
                it[ListenShareTable.useForRecommendations] = useForRecommendations
            }
            members
        }
        changed(userA, userB)
    }

    private fun membersFor(actor: UserInfo, linkId: PlatformUUID): Pair<PlatformUUID, PlatformUUID> {
        val row = ListenShareTable
            .select(ListenShareTable.userA, ListenShareTable.userB)
            .where { ListenShareTable.id eq linkId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Unknown listen share $linkId")
        val members = row[ListenShareTable.userA].value to row[ListenShareTable.userB].value
        if (!actor.isAdmin && actor.id != members.first && actor.id != members.second) {
            throw UnauthorizedException("Only an admin or a linked user can change this listen share")
        }
        return members
    }

    private fun listenBrainzUserIdOf(userId: PlatformUUID): PlatformUUID? =
        UserListenBrainzLinkTable
            .select(UserListenBrainzLinkTable.listenBrainzUserId)
            .where { UserListenBrainzLinkTable.userId eq userId }
            .singleOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

    private fun pairs(filter: Op<Boolean>?): List<ListenSharePair> {
        val query = ListenShareTable.selectAll()
        filter?.let { query.where(it) }
        val rows = query.toList()
        val users = profiles(rows.flatMap { listOf(it[ListenShareTable.userA].value, it[ListenShareTable.userB].value) })
        return rows.map {
            ListenSharePair(
                id = it[ListenShareTable.id].value,
                userA = users.getValue(it[ListenShareTable.userA].value),
                userB = users.getValue(it[ListenShareTable.userB].value),
                useForRecommendations = it[ListenShareTable.useForRecommendations],
                createdAt = it[ListenShareTable.createdAt],
            )
        }
    }

    private fun profiles(ids: Collection<PlatformUUID>): Map<PlatformUUID, UserProfile> {
        if (ids.isEmpty()) return emptyMap()
        return UserTable
            .leftJoin(ImageTable, onColumn = { UserTable.profileImage }, otherColumn = { ImageTable.id })
            .select(UserTable.id, UserTable.username, UserTable.displayName, UserTable.profileImage, ImageTable.blurHash)
            .where { UserTable.id inList ids.distinct() }
            .associate {
                it[UserTable.id].value to UserProfile(
                    id = it[UserTable.id].value,
                    username = it[UserTable.username],
                    displayName = it[UserTable.displayName],
                    profileImageId = it[UserTable.profileImage]?.value,
                    blurHash = it.getOrNull(ImageTable.blurHash),
                )
            }
    }

    private suspend fun changed(userA: PlatformUUID, userB: PlatformUUID) {
        listenService.listensChanged(listOf(userA, userB))
        uiRegistry.invalidate(SETTINGS_CONTRIBUTION_ID)
        uiRegistry.invalidate(ADMIN_CONTRIBUTION_ID)
    }
}
