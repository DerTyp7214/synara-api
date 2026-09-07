package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.HueBridgeTable
import dev.dertyp.db.HueUserLinkTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class BackfillHueBridgeOwnersTest {
    private lateinit var database: Database

    private val userA = UUID.randomUUID()
    private val userB = UUID.randomUUID()

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "backfill_hue_bridge_owners_test")
        transaction(database) {
            SchemaUtils.create(UserTable, HueBridgeTable, HueUserLinkTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun addUser(user: UUID, name: String, admin: Boolean = false) = transaction(database) {
        UserTable.insert {
            it[UserTable.id] = user
            it[username] = name
            it[passwordHash] = "hash"
            it[isAdmin] = admin
        }
    }

    private fun addBridge(hardwareId: String = "001788fffe000001", ownedBy: UUID? = null): UUID = transaction(database) {
        HueBridgeTable.insertAndGetId {
            it[bridgeId] = hardwareId
            it[ip] = "10.0.0.2"
            it[name] = "Living room bridge"
            it[modelId] = "BSB002"
            it[applicationKey] = "app-key"
            it[clientKey] = "client-key"
            it[certFingerprint] = "AA:BB"
            it[createdAt] = 1_000
            it[lastSeen] = 2_000
            it[lastError] = "boom"
            it[userId] = ownedBy
        }.value
    }

    private fun addLink(user: UUID, bridge: UUID, updated: Long) = transaction(database) {
        HueUserLinkTable.insert {
            it[userId] = user
            it[bridgeId] = bridge
            it[updatedAt] = updated
        }
    }

    private fun ownerOf(bridge: UUID): UUID? = transaction(database) {
        HueBridgeTable.selectAll()
            .where { HueBridgeTable.id eq bridge }
            .firstOrNull()
            ?.get(HueBridgeTable.userId)
            ?.value
    }

    private fun linkedBridge(user: UUID): UUID? = transaction(database) {
        HueUserLinkTable.selectAll()
            .where { HueUserLinkTable.userId eq user }
            .firstOrNull()
            ?.get(HueUserLinkTable.bridgeId)
            ?.value
    }

    private fun bridgeCount(): Long = transaction(database) { HueBridgeTable.selectAll().count() }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an owned bridge keeps its owner`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        addUser(userA, "alice", admin = true)
        addUser(userB, "bob", admin = true)
        val owned = addBridge(ownedBy = userA)

        BackfillHueBridgeOwners().migrate()

        assertEquals(userA, ownerOf(owned))
        assertEquals(1L, bridgeCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an unowned bridge is assigned to the earliest linked user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        addUser(userA, "alice", admin = true)
        addUser(userB, "bob")
        val unowned = addBridge()
        addLink(userA, unowned, 5_000)
        addLink(userB, unowned, 1_000)

        BackfillHueBridgeOwners().migrate()

        assertEquals(userB, ownerOf(unowned))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an unowned bridge without links falls back to the first admin`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        addUser(UUID.randomUUID(), "aaron")
        addUser(userA, "brenda", admin = true)
        addUser(userB, "carla", admin = true)
        val unowned = addBridge()

        BackfillHueBridgeOwners().migrate()

        assertEquals(userA, ownerOf(unowned))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an unowned bridge without any user is deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val unowned = addBridge()

        BackfillHueBridgeOwners().migrate()

        assertNull(ownerOf(unowned))
        assertEquals(0L, bridgeCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a link of another user is repointed to a clone of the bridge`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        addUser(userA, "alice", admin = true)
        addUser(userB, "bob")
        val shared = addBridge(ownedBy = userA)
        addLink(userA, shared, 1_000)
        addLink(userB, shared, 2_000)

        BackfillHueBridgeOwners().migrate()

        assertEquals(shared, linkedBridge(userA))
        val clone = linkedBridge(userB)
        assertNotEquals(shared, clone)
        assertEquals(2L, bridgeCount())

        val original = transaction(database) { HueBridgeTable.selectAll().where { HueBridgeTable.id eq shared }.single() }
        val copy = transaction(database) { HueBridgeTable.selectAll().where { HueBridgeTable.id eq clone!! }.single() }
        assertEquals(userB, copy[HueBridgeTable.userId]?.value)
        assertEquals(original[HueBridgeTable.bridgeId], copy[HueBridgeTable.bridgeId])
        assertEquals(original[HueBridgeTable.ip], copy[HueBridgeTable.ip])
        assertEquals(original[HueBridgeTable.name], copy[HueBridgeTable.name])
        assertEquals(original[HueBridgeTable.modelId], copy[HueBridgeTable.modelId])
        assertEquals(original[HueBridgeTable.applicationKey], copy[HueBridgeTable.applicationKey])
        assertEquals(original[HueBridgeTable.clientKey], copy[HueBridgeTable.clientKey])
        assertEquals(original[HueBridgeTable.certFingerprint], copy[HueBridgeTable.certFingerprint])
        assertEquals(original[HueBridgeTable.createdAt], copy[HueBridgeTable.createdAt])
        assertEquals(original[HueBridgeTable.lastSeen], copy[HueBridgeTable.lastSeen])
        assertEquals(original[HueBridgeTable.lastError], copy[HueBridgeTable.lastError])
    }
}
