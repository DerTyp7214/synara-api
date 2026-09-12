package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.db.ClientDeviceTable
import dev.dertyp.db.ClientSettingHistoryTable
import dev.dertyp.db.ClientSettingScopeTable
import dev.dertyp.db.ClientSettingTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.days

class ClientSettingsCleanupTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ClientSettingsService

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        database = TestDatabase.connect(dialect, "client_settings_cleanup_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                ClientDeviceTable,
                ClientSettingScopeTable,
                ClientSettingTable,
                ClientSettingHistoryTable,
            )
        }
        service = ClientSettingsService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        UserTable.insert {
            it[UserTable.id] = id
            it[username] = "user_$id"
            it[passwordHash] = "hash"
        }
        return id
    }

    private fun write(settingKey: String, settingValue: String?, baseVersion: Long = 0) =
        ClientSettingWrite(key = settingKey, value = settingValue, baseVersion = baseVersion)

    private fun backdateSetting(owner: UUID, ownerScope: ClientSettingScope, ownerDeviceId: String, settingKey: String, at: Long) {
        ClientSettingTable.update({
            (ClientSettingTable.userId eq owner) and
                (ClientSettingTable.scope eq ownerScope) and
                (ClientSettingTable.deviceId eq ownerDeviceId) and
                (ClientSettingTable.key eq settingKey)
        }) {
            it[modifiedAt] = at
        }
    }

    private fun backdateDevice(owner: UUID, ownerDeviceId: String, at: Long) {
        ClientDeviceTable.update({
            (ClientDeviceTable.userId eq owner) and (ClientDeviceTable.deviceId eq ownerDeviceId)
        }) {
            it[lastSeenAt] = at
        }
    }

    private fun staleTombstoneTimestamp(): Long = Instant.now().toEpochMilli() - 40.days.inWholeMilliseconds
    private fun staleDeviceTimestamp(): Long = Instant.now().toEpochMilli() - 200.days.inWholeMilliseconds

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `old tombstones are purged in both scopes and the scope records a purged version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val syncedCreated = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(syncedCreated is ClientSettingsWriteResult.Ok)
        val syncedTombstoned = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", null, baseVersion = syncedCreated.version)), false)
        check(syncedTombstoned is ClientSettingsWriteResult.Ok)

        val deviceCreated = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("b", "2")), false)
        check(deviceCreated is ClientSettingsWriteResult.Ok)
        val deviceTombstoned = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("b", null, baseVersion = deviceCreated.version)), false)
        check(deviceTombstoned is ClientSettingsWriteResult.Ok)

        transaction(database) {
            backdateSetting(userId, ClientSettingScope.SYNCED, "", "a", staleTombstoneTimestamp())
            backdateSetting(userId, ClientSettingScope.DEVICE, "device-1", "b", staleTombstoneTimestamp())
        }

        val counts = service.cleanup()

        assertEquals(2, counts.getValue("tombstonesPurged"))
        assertTrue(service.getSettings(userId, ClientSettingScope.SYNCED, null, true).isEmpty())
        assertTrue(service.getSettings(userId, ClientSettingScope.DEVICE, "device-1", true).isEmpty())

        val syncedChanges = service.getChanges(userId, ClientSettingScope.SYNCED, null, 1, 10)
        assertTrue(syncedChanges.fullResync)
        val deviceChanges = service.getChanges(userId, ClientSettingScope.DEVICE, "device-1", 1, 10)
        assertTrue(deviceChanges.fullResync)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recent tombstones and live entries survive cleanup`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("live", "1"), write("gone", "2")), false)
        check(created is ClientSettingsWriteResult.Ok)
        val tombstoned = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("gone", null, baseVersion = created.version)), false)
        check(tombstoned is ClientSettingsWriteResult.Ok)

        val counts = service.cleanup()

        assertEquals(0, counts.getValue("tombstonesPurged"))
        assertEquals("1", service.getSettings(userId, ClientSettingScope.SYNCED, null, false).single().value)
        assertTrue(service.getSettings(userId, ClientSettingScope.SYNCED, null, true).any { it.key == "gone" && it.deleted })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `history beyond the limit is trimmed by cleanup`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        repeat(8) { index -> service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("counter", index.toString())), true) }

        val beforeHistory = service.getHistory(userId, ClientSettingScope.SYNCED, null, "counter", 20)
        assertEquals(7, beforeHistory.size)

        val counts = service.cleanup(historyLimit = 5)

        assertEquals(2, counts.getValue("historyTrimmed"))
        val afterHistory = service.getHistory(userId, ClientSettingScope.SYNCED, null, "counter", 20)
        assertEquals(5, afterHistory.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `devices unseen past the cutoff are deleted together with their settings and history`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val first = service.setSettings(userId, ClientSettingScope.DEVICE, "stale-device", listOf(write("a", "1")), false)
        check(first is ClientSettingsWriteResult.Ok)
        service.setSettings(userId, ClientSettingScope.DEVICE, "stale-device", listOf(write("a", "2", baseVersion = first.version)), false)

        transaction(database) { backdateDevice(userId, "stale-device", staleDeviceTimestamp()) }

        val counts = service.cleanup()

        assertEquals(1, counts.getValue("devicesDeleted"))
        assertTrue(service.getDevices(userId).none { it.deviceId == "stale-device" })
        assertTrue(service.getSettings(userId, ClientSettingScope.DEVICE, "stale-device", true).isEmpty())
        assertTrue(service.getHistory(userId, ClientSettingScope.DEVICE, "stale-device", "a", 20).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a recently seen device and the synced scope's live entries are untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        service.registerDevice(userId, "fresh-device", "Phone", "android")

        val counts = service.cleanup()

        assertEquals(0, counts.getValue("devicesDeleted"))
        assertTrue(service.getDevices(userId).any { it.deviceId == "fresh-device" })
        assertEquals("1", service.getSettings(userId, ClientSettingScope.SYNCED, null, false).single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `cleanup never advances any scope version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(created is ClientSettingsWriteResult.Ok)
        val tombstoned = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", null, baseVersion = created.version)), false)
        check(tombstoned is ClientSettingsWriteResult.Ok)
        transaction(database) { backdateSetting(userId, ClientSettingScope.SYNCED, "", "a", staleTombstoneTimestamp()) }

        val before = service.getChanges(userId, ClientSettingScope.SYNCED, null, 0, 10).version

        service.cleanup()

        val after = service.getChanges(userId, ClientSettingScope.SYNCED, null, 0, 10).version
        assertEquals(before, after)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `cleanup returns the counts of tombstones history rows and devices it removed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) { insertUser() }

        val counts = service.cleanup()

        assertEquals(setOf("tombstonesPurged", "historyTrimmed", "devicesDeleted"), counts.keys)
    }
}
