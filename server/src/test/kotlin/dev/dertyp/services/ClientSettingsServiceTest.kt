package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.db.ClientDeviceTable
import dev.dertyp.db.ClientSettingHistoryTable
import dev.dertyp.db.ClientSettingScopeTable
import dev.dertyp.db.ClientSettingTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ClientSettingsServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ClientSettingsService

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        database = TestDatabase.connect(dialect, "client_settings_test")
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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an empty scope is reported at version 0 with no entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertTrue(service.getSettings(userId, ClientSettingScope.SYNCED, null, true).isEmpty())
        assertEquals(0L, service.getChanges(userId, ClientSettingScope.SYNCED, null, 0, 10).version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a batch write bumps the version by exactly one and stamps the writer device`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val result = service.setSettings(
            userId, ClientSettingScope.DEVICE, "device-1",
            listOf(write("theme", "\"dark\""), write("volume", "50")), false
        )

        check(result is ClientSettingsWriteResult.Ok)
        assertEquals(1L, result.version)
        assertEquals(2, result.entries.size)
        assertTrue(result.entries.all { it.modifiedByDeviceId == "device-1" })

        val second = service.setSettings(
            userId, ClientSettingScope.DEVICE, "device-1",
            listOf(write("theme", "\"light\"", baseVersion = 1)), false
        )

        check(second is ClientSettingsWriteResult.Ok)
        assertEquals(2L, second.version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a stale baseVersion is rejected with a conflict carrying the stored entry and writes nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val first = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("theme", "\"dark\"")), false)
        check(first is ClientSettingsWriteResult.Ok)

        val conflict = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("theme", "\"light\"", baseVersion = 0)), false)

        check(conflict is ClientSettingsWriteResult.Conflict)
        assertEquals(1L, conflict.version)
        val storedConflict = conflict.conflicts.single()
        assertEquals("theme", storedConflict.key)
        assertEquals("\"dark\"", storedConflict.current?.value)

        val settings = service.getSettings(userId, ClientSettingScope.DEVICE, "device-1", false)
        assertEquals("\"dark\"", settings.single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `force applies a write even past the stored version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val first = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("theme", "\"dark\"")), false)
        check(first is ClientSettingsWriteResult.Ok)

        val forced = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("theme", "\"light\"", baseVersion = 0)), true)

        check(forced is ClientSettingsWriteResult.Ok)
        assertEquals(2L, forced.version)
        assertEquals("\"light\"", forced.entries.single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a new key is accepted at baseVersion 0 and rejected once it already exists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(created is ClientSettingsWriteResult.Ok)
        assertEquals(1L, created.version)

        val rejected = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "2")), false)

        check(rejected is ClientSettingsWriteResult.Conflict)
        assertEquals(0L, rejected.conflicts.single().baseVersion)
        assertEquals(1L, rejected.conflicts.single().current?.version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a tombstone is hidden by default and shown with includeDeleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(created is ClientSettingsWriteResult.Ok)

        val deleted = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", null, baseVersion = created.version)), false)
        check(deleted is ClientSettingsWriteResult.Ok)
        assertTrue(deleted.entries.single().deleted)

        assertTrue(service.getSettings(userId, ClientSettingScope.SYNCED, null, false).isEmpty())
        val withDeleted = service.getSettings(userId, ClientSettingScope.SYNCED, null, true)
        assertTrue(withDeleted.single().deleted)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting an absent key is a no-op and does not bump the version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val result = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("ghost", null, baseVersion = 0)), false)

        check(result is ClientSettingsWriteResult.Ok)
        assertEquals(0L, result.version)
        assertTrue(result.entries.isEmpty())
        assertTrue(service.getSettings(userId, ClientSettingScope.SYNCED, null, true).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a deleted key can be rewritten with baseVersion 0 or the tombstone version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(created is ClientSettingsWriteResult.Ok)
        val firstTombstone = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", null, baseVersion = created.version)), false)
        check(firstTombstone is ClientSettingsWriteResult.Ok)

        val rewrittenAtZero = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "2", baseVersion = 0)), false)
        check(rewrittenAtZero is ClientSettingsWriteResult.Ok)
        assertEquals("2", rewrittenAtZero.entries.single().value)

        val secondTombstone = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", null, baseVersion = rewrittenAtZero.version)), false)
        check(secondTombstone is ClientSettingsWriteResult.Ok)

        val rewrittenAtTombstoneVersion = service.setSettings(
            userId, ClientSettingScope.SYNCED, null,
            listOf(write("a", "3", baseVersion = secondTombstone.version)), false
        )

        check(rewrittenAtTombstoneVersion is ClientSettingsWriteResult.Ok)
        assertEquals("3", rewrittenAtTombstoneVersion.entries.single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getChanges orders by version, includes tombstones and pages with hasMore`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("b", "2")), false)
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("c", "3")), false)
        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("d", "4")), false)
        check(created is ClientSettingsWriteResult.Ok)
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("d", null, baseVersion = created.version)), false)

        val firstPage = service.getChanges(userId, ClientSettingScope.SYNCED, null, 0, 2)
        assertEquals(5L, firstPage.version)
        assertEquals(listOf(1L, 2L), firstPage.entries.map { it.version })
        assertTrue(firstPage.hasMore)
        assertFalse(firstPage.fullResync)

        val secondPage = service.getChanges(userId, ClientSettingScope.SYNCED, null, 2, 2)
        assertEquals(listOf(3L, 5L), secondPage.entries.map { it.version })
        assertFalse(secondPage.hasMore)
        assertTrue(secondPage.entries.last().deleted)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getChanges reports a full resync once cleanup purged the tombstones it would have returned`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(created is ClientSettingsWriteResult.Ok)
        val tombstoned = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", null, baseVersion = created.version)), false)
        check(tombstoned is ClientSettingsWriteResult.Ok)

        service.cleanup(tombstoneMaxAgeMs = -1)

        val changes = service.getChanges(userId, ClientSettingScope.SYNCED, null, 1, 500)
        assertTrue(changes.fullResync)

        val fromScratch = service.getChanges(userId, ClientSettingScope.SYNCED, null, 0, 500)
        assertFalse(fromScratch.fullResync)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `synced entries are visible to every device while device entries stay isolated per device`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        service.setSettings(userId, ClientSettingScope.SYNCED, "device-a", listOf(write("theme", "\"dark\"")), false)
        service.setSettings(userId, ClientSettingScope.DEVICE, "device-a", listOf(write("volume", "50")), false)
        service.setSettings(userId, ClientSettingScope.DEVICE, "device-b", listOf(write("volume", "80")), false)

        assertEquals("\"dark\"", service.getSettings(userId, ClientSettingScope.SYNCED, "device-a", false).single().value)
        assertEquals("\"dark\"", service.getSettings(userId, ClientSettingScope.SYNCED, "device-b", false).single().value)

        assertEquals("50", service.getSettings(userId, ClientSettingScope.DEVICE, "device-a", false).single().value)
        assertEquals("80", service.getSettings(userId, ClientSettingScope.DEVICE, "device-b", false).single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a snapshot carries the live entries of both scopes tagged with their scope and versions`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val created = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1"), write("gone", "x")), false)
        check(created is ClientSettingsWriteResult.Ok)
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("gone", null, baseVersion = created.version)), false)
        service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("b", "2")), false)

        val snapshot = service.getSnapshot(userId, "device-1")

        assertEquals(2L, snapshot.syncedVersion)
        assertEquals(1L, snapshot.deviceVersion)
        assertEquals(2, snapshot.entries.size)
        assertTrue(snapshot.entries.none { it.deleted })
        val synced = snapshot.entries.single { it.scope == ClientSettingScope.SYNCED }
        assertEquals("a", synced.key)
        assertNull(synced.deviceId)
        val device = snapshot.entries.single { it.scope == ClientSettingScope.DEVICE }
        assertEquals("b", device.key)
        assertEquals("device-1", device.deviceId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a user never sees another user's entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userA, userB) = transaction(database) { insertUser() to insertUser() }
        service.setSettings(userA, ClientSettingScope.SYNCED, null, listOf(write("shared-key", "1")), false)

        assertTrue(service.getSettings(userB, ClientSettingScope.SYNCED, null, true).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `history is returned newest first and trimmed to the history limit`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        repeat(25) { index ->
            service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("counter", index.toString())), true)
        }

        val history = service.getHistory(userId, ClientSettingScope.SYNCED, null, "counter", 25)

        assertEquals(20, history.size)
        assertEquals(history.sortedByDescending { it.version }.map { it.version }, history.map { it.version })
        assertEquals("23", history.first().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `restore writes a new version with the historic value and pushes the replaced value into history`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val first = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "\"v1\"")), false)
        check(first is ClientSettingsWriteResult.Ok)
        val second = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "\"v2\"", baseVersion = first.version)), false)
        check(second is ClientSettingsWriteResult.Ok)

        val restored = service.restore(userId, ClientSettingScope.SYNCED, null, "a", first.version, false)

        check(restored is ClientSettingsWriteResult.Ok)
        assertEquals(3L, restored.version)
        assertEquals("\"v1\"", restored.entries.single().value)

        val history = service.getHistory(userId, ClientSettingScope.SYNCED, null, "a", 20)
        assertEquals(listOf("\"v2\"", "\"v1\""), history.map { it.value })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `restore of an unknown version throws`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "\"v1\"")), false)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.restore(userId, ClientSettingScope.SYNCED, null, "a", 99, false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a plain restore succeeds even after another write changed the key in between`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val first = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "\"v1\"")), false)
        check(first is ClientSettingsWriteResult.Ok)
        val second = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "\"v2\"", baseVersion = first.version)), false)
        check(second is ClientSettingsWriteResult.Ok)
        val third = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "\"v3\"", baseVersion = second.version)), false)
        check(third is ClientSettingsWriteResult.Ok)

        val restored = service.restore(userId, ClientSettingScope.SYNCED, null, "a", first.version, false)

        check(restored is ClientSettingsWriteResult.Ok)
        assertEquals(4L, restored.version)
        assertEquals("\"v1\"", restored.entries.single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `observe emits a change after a successful write but nothing after a conflict`(dialect: DbDialect) = runTest {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val results = mutableListOf<ClientSettingsChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observe(userId).collect { results.add(it) }
        }

        val ok = service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        check(ok is ClientSettingsWriteResult.Ok)
        assertEquals(1, results.size)
        assertEquals(listOf("a"), results.single().keys)

        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "2", baseVersion = 0)), false)
        assertEquals(1, results.size)

        job.cancel()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a value that is not valid JSON is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "not json")), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a value larger than 64 KiB is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val oversized = "x".repeat(70_000)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", oversized)), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a key longer than 255 characters is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val longKey = "k".repeat(256)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write(longKey, "1")), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a blank key is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("   ", "1")), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `duplicate keys in the same batch are rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1"), write("a", "2")), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a batch larger than the maximum size is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val entries = (0 until 201).map { write("k$it", "1") }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, entries, false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a scope is capped at the maximum number of entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        (0 until 10).forEach { batch ->
            val entries = (0 until 200).map { offset -> write("k${batch * 200 + offset}", "1") }
            val result = service.setSettings(userId, ClientSettingScope.SYNCED, null, entries, false)
            check(result is ClientSettingsWriteResult.Ok)
        }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("overflow", "1")), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `writing the device scope without a device id is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.setSettings(userId, ClientSettingScope.DEVICE, null, listOf(write("a", "1")), false) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `writing the device scope registers the device`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("a", "1")), false)

        val devices = service.getDevices(userId)
        assertEquals(listOf("device-1"), devices.map { it.deviceId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `registerDevice refreshes name platform and lastSeenAt without duplicating the device`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val first = service.registerDevice(userId, "device-1", "Phone", "android")
        val second = service.registerDevice(userId, "device-1", "New Phone", "ios")

        assertEquals("New Phone", second.name)
        assertEquals("ios", second.platform)
        assertTrue(second.lastSeenAt >= first.lastSeenAt)
        assertEquals(1, service.getDevices(userId).size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getDevices reports the settings version of each device`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        service.registerDevice(userId, "device-1", "Phone", "android")

        service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("a", "1")), false)

        val device = service.getDevices(userId).single { it.deviceId == "device-1" }
        assertEquals(1L, device.settingsVersion)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteDevice removes its settings history and scope row but keeps the synced entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("shared", "1")), false)
        val first = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("a", "1")), false)
        check(first is ClientSettingsWriteResult.Ok)
        service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("a", "2", baseVersion = first.version)), false)

        service.deleteDevice(userId, "device-1")

        assertTrue(service.getDevices(userId).none { it.deviceId == "device-1" })
        assertTrue(service.getHistory(userId, ClientSettingScope.DEVICE, "device-1", "a", 20).isEmpty())
        assertTrue(service.getSettings(userId, ClientSettingScope.DEVICE, "device-1", true).isEmpty())
        assertEquals(0L, service.getChanges(userId, ClientSettingScope.DEVICE, "device-1", 0, 10).version)

        assertEquals("1", service.getSettings(userId, ClientSettingScope.SYNCED, null, false).single().value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting the user cascades to their settings devices and history`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assumeTrue(dialect == DbDialect.POSTGRES, "FK cascade requires foreign key enforcement")

        val userId = transaction(database) { insertUser() }
        service.setSettings(userId, ClientSettingScope.SYNCED, null, listOf(write("a", "1")), false)
        val deviceWrite = service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("b", "2")), false)
        check(deviceWrite is ClientSettingsWriteResult.Ok)
        service.setSettings(userId, ClientSettingScope.DEVICE, "device-1", listOf(write("b", "3", baseVersion = deviceWrite.version)), false)

        transaction(database) { UserTable.deleteWhere { UserTable.id eq userId } }

        assertTrue(service.getSettings(userId, ClientSettingScope.SYNCED, null, true).isEmpty())
        assertTrue(service.getDevices(userId).isEmpty())
        assertTrue(service.getHistory(userId, ClientSettingScope.DEVICE, "device-1", "b", 20).isEmpty())
    }
}
