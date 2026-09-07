package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.HueBridgeTable
import dev.dertyp.db.HueUserLinkTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

@Migration("3.16")
class BackfillHueBridgeOwners : CustomMigration() {
    override suspend fun migrate() {
        var assigned = 0
        var cloned = 0
        var deleted = 0

        dbQuery {
            val firstAdmin = UserTable.selectAll()
                .where { UserTable.isAdmin eq true }
                .orderBy(UserTable.username to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.get(UserTable.id)
                ?.value

            val links = HueUserLinkTable.selectAll()
                .orderBy(HueUserLinkTable.updatedAt to SortOrder.ASC)
                .map { it[HueUserLinkTable.bridgeId].value to it[HueUserLinkTable.userId].value }

            val earliestLink = mutableMapOf<UUID, UUID>()
            for ((bridge, user) in links) earliestLink.putIfAbsent(bridge, user)

            val unowned = HueBridgeTable.selectAll()
                .where { HueBridgeTable.userId.isNull() }
                .map { it[HueBridgeTable.id].value }

            for (bridge in unowned) {
                val owner = earliestLink[bridge] ?: firstAdmin
                if (owner == null) {
                    HueBridgeTable.deleteWhere { HueBridgeTable.id eq bridge }
                    deleted++
                } else {
                    HueBridgeTable.update({ HueBridgeTable.id eq bridge }) { it[userId] = owner }
                    assigned++
                }
            }

            val bridges = HueBridgeTable.selectAll().associateBy { it[HueBridgeTable.id].value }
            val clones = mutableMapOf<Pair<UUID, UUID>, UUID>()

            for ((bridge, user) in links) {
                val row = bridges[bridge] ?: continue
                val owner = row[HueBridgeTable.userId]?.value ?: continue
                if (owner == user) continue

                val clone = clones[bridge to user] ?: run {
                    val created = HueBridgeTable.insertAndGetId {
                        it[bridgeId] = row[HueBridgeTable.bridgeId]
                        it[ip] = row[HueBridgeTable.ip]
                        it[name] = row[HueBridgeTable.name]
                        it[modelId] = row[HueBridgeTable.modelId]
                        it[applicationKey] = row[HueBridgeTable.applicationKey]
                        it[clientKey] = row[HueBridgeTable.clientKey]
                        it[certFingerprint] = row[HueBridgeTable.certFingerprint]
                        it[createdAt] = row[HueBridgeTable.createdAt]
                        it[lastSeen] = row[HueBridgeTable.lastSeen]
                        it[lastError] = row[HueBridgeTable.lastError]
                        it[userId] = user
                    }.value
                    clones[bridge to user] = created
                    cloned++
                    created
                }

                HueUserLinkTable.update({
                    (HueUserLinkTable.userId eq user) and (HueUserLinkTable.bridgeId eq bridge)
                }) {
                    it[bridgeId] = clone
                }
            }
        }

        logger.info("Hue bridges: assigned $assigned owner(s), cloned $cloned bridge(s) for other users, deleted $deleted ownerless bridge(s)")
    }
}
