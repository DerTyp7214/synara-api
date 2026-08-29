package dev.dertyp.services.ui

import dev.dertyp.db.UserHomeCardTable
import dev.dertyp.dbQuery
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiHomeCard
import dev.dertyp.ui.UiHomeLayout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

class UserHomeCardService {
    private data class Row(val contributionId: String, val pinned: Boolean, val position: Int)

    private val changes = MutableSharedFlow<UUID>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private suspend fun rows(userId: UUID): List<Row> = dbQuery {
        UserHomeCardTable.selectAll()
            .where { UserHomeCardTable.userId eq userId }
            .map { Row(it[UserHomeCardTable.contributionId], it[UserHomeCardTable.pinned], it[UserHomeCardTable.position]) }
    }

    suspend fun layoutFor(userId: UUID, available: List<UiContributionInfo>): UiHomeLayout {
        val stored = rows(userId).associateBy { it.contributionId }
        val cards = available.map { info ->
            val row = stored[info.id]
            UiHomeCard(
                contributionId = info.id,
                pinned = row?.pinned ?: false,
                position = row?.position ?: Int.MAX_VALUE,
                size = info.cardSize,
            )
        }.sortedWith(compareByDescending<UiHomeCard> { it.pinned }.thenBy { it.position }.thenBy { it.contributionId })
        return UiHomeLayout(cards.mapIndexed { index, card -> card.copy(position = index) })
    }

    suspend fun setPinned(userId: UUID, contributionId: String, pinned: Boolean) {
        val position = rows(userId).filter { it.pinned }.maxOfOrNull { it.position }?.plus(1) ?: 0
        dbQuery {
            UserHomeCardTable.upsert(UserHomeCardTable.userId, UserHomeCardTable.contributionId) {
                it[UserHomeCardTable.userId] = userId
                it[UserHomeCardTable.contributionId] = contributionId
                it[UserHomeCardTable.pinned] = pinned
                if (pinned) it[UserHomeCardTable.position] = position
            }
        }
        changes.tryEmit(userId)
    }

    suspend fun setOrder(userId: UUID, contributionIds: List<String>) {
        dbQuery {
            contributionIds.forEachIndexed { index, contributionId ->
                UserHomeCardTable.upsert(UserHomeCardTable.userId, UserHomeCardTable.contributionId) {
                    it[UserHomeCardTable.userId] = userId
                    it[UserHomeCardTable.contributionId] = contributionId
                    it[pinned] = true
                    it[position] = index
                }
            }
        }
        changes.tryEmit(userId)
    }

    suspend fun forget(userId: UUID, contributionId: String) {
        dbQuery {
            UserHomeCardTable.deleteWhere { (UserHomeCardTable.userId eq userId) and (UserHomeCardTable.contributionId eq contributionId) }
        }
        changes.tryEmit(userId)
    }

    fun layoutFlow(userId: UUID, available: suspend () -> List<UiContributionInfo>): Flow<UiHomeLayout> =
        changes.filter { it == userId }.map { }.onStart { emit(Unit) }.map { layoutFor(userId, available()) }
}
