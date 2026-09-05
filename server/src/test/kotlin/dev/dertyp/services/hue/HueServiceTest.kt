package dev.dertyp.services.hue

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HueMotionMode
import dev.dertyp.data.HuePairingState
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.Image
import dev.dertyp.data.SongAudioData
import dev.dertyp.data.UserSong
import dev.dertyp.db.HueBridgeTable
import dev.dertyp.db.HueUserLinkTable
import dev.dertyp.db.UserTable
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.services.AudioAnalysisService
import dev.dertyp.services.HookService
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class HueServiceTest {
    private lateinit var database: Database
    private lateinit var songService: SongService
    private lateinit var imageService: ImageService
    private lateinit var audioAnalysisService: AudioAnalysisService
    private lateinit var api: HueBridgeApi
    private lateinit var service: HueService
    private val userId = UUID.randomUUID()
    private val sent = CopyOnWriteArrayList<Pair<String, LightUpdate>>()

    private fun setup(dialect: DbDialect) {
        songService = mockk()
        imageService = mockk()
        audioAnalysisService = mockk()
        api = mockk(relaxed = true)
        coEvery { api.putLight(any(), any()) } answers { sent += (firstArg<String>() to secondArg<LightUpdate>()) }
        coEvery { api.putGroupedLight(any(), any()) } answers { sent += (firstArg<String>() to secondArg<LightUpdate>()) }
        startKoin {
            modules(module {
                single<HookBus> { HookService() }
                single { songService }
                single { imageService }
                single { audioAnalysisService }
                single { HueDiscoveryService() }
            })
        }
        database = TestDatabase.connect(dialect, "hue_test")
        transaction(database) {
            SchemaUtils.create(UserTable, HueBridgeTable, HueUserLinkTable)
            UserTable.insert {
                it[id] = userId
                it[username] = "hue"
                it[passwordHash] = "hash"
            }
        }
        service = HueService()
        service.clientFactory = { api }
    }

    @AfterEach
    fun tearDown() {
        runBlocking { service.stopService() }
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun bridge(): UUID = transaction(database) {
        HueBridgeTable.insertAndGetId {
            it[bridgeId] = "001788fffe000001"
            it[ip] = "192.0.2.10"
            it[name] = "Test Bridge"
            it[applicationKey] = "key"
            it[createdAt] = 1L
        }.value
    }

    private fun light(id: String, name: String) = HueTarget(HueTargetType.LIGHT, id, name)

    private suspend fun awaitSent(count: Int) {
        repeat(100) {
            if (sent.size >= count) return
            delay(20)
        }
        throw AssertionError("expected $count commands, got ${sent.size}")
    }

    private fun song(id: UUID, coverId: UUID?): UserSong = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.coverId } returns coverId
        every { album } returns null
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `links round trip and validate targets`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        assertTrue(service.getLinks(userId).isEmpty())
        val link = HueUserLink(bridgeId, true, listOf(light("l1", "Desk")), HueIntensity.HIGH, HueTransitionMode.BPM, 700, HueStopMode.OFF)
        val saved = service.setLink(userId, link)
        assertTrue(saved.updatedAt > 0)
        val loaded = service.getLinks(userId).single()
        assertEquals(link.copy(updatedAt = loaded.updatedAt), loaded)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.setLink(userId, HueUserLink(bridgeId, enabled = true)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.setLink(userId, HueUserLink(UUID.randomUUID(), enabled = false)) } }
        assertTrue(service.removeLink(userId, bridgeId))
        assertTrue(service.getLinks(userId).isEmpty())
        assertEquals(1, service.listBridges().size)
        assertTrue(service.removeBridge(bridgeId))
        assertTrue(service.listBridges().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `now playing drives enabled links and stops turn lights off`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(userId, HueUserLink(bridgeId, true, listOf(light("l1", "Desk"), light("l2", "Shelf")), onStop = HueStopMode.OFF))
        val other = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song(songId, coverId))
        coEvery { imageService.byId(coverId) } returns Image(coverId, "p", "h", "o", palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()), primaryColor = 0xFFE01020.toInt())
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns mapOf(songId to SongAudioData(energy = 0.9, bpm = 120.0))

        service.onNowPlaying(HookEvent.NowPlayingChanged(other, songId, 1, 0))
        delay(100)
        assertTrue(sent.isEmpty())

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 5, 0))
        awaitSent(2)
        assertEquals(setOf("l1", "l2"), sent.map { it.first }.toSet())
        assertTrue(sent.all { it.second.on?.on == true && it.second.color != null })
        assertEquals(2, service.status(userId).currentColors.size)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 4, 0))
        delay(100)
        assertEquals(2, sent.size)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 6, 0))
        delay(100)
        assertEquals(2, sent.size)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 7, 0))
        awaitSent(4)
        assertTrue(sent.drop(2).all { it.second.on?.on == false })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ambient motion keeps sending rotated frames until playback stops`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.motionIntervalOverride = 60
        service.setLink(userId, HueUserLink(bridgeId, true, listOf(light("l1", "Desk"), light("l2", "Shelf")), motion = HueMotionMode.SLOW))
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song(songId, coverId))
        coEvery { imageService.byId(coverId) } returns Image(coverId, "p", "h", "o", palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()), primaryColor = 0xFFE01020.toInt())
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns emptyMap()

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitSent(6)
        assertEquals(1, service.activeMotions())
        val firstColors = sent.take(2).map { it.second.color!!.xy }
        val laterColors = sent.drop(2).take(2).map { it.second.color!!.xy }
        assertEquals(firstColors.reversed(), laterColors)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 2, System.currentTimeMillis()))
        delay(500)
        assertEquals(0, service.activeMotions())
        val count = sent.size
        delay(300)
        assertEquals(count, sent.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `targets come from the bridge and test sends commands`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        coEvery { api.lights() } returns listOf(
            ClipLight("l1", ClipMetadata("Desk"), color = ClipColor(ClipXy(0.3, 0.3))),
            ClipLight("plug", ClipMetadata("Plug")),
        )
        coEvery { api.rooms() } returns listOf(ClipGroup("r1", ClipMetadata("Living"), services = listOf(ClipResourceRef("g1", "grouped_light"))))
        coEvery { api.zones() } returns emptyList()
        val targets = service.listTargets(bridgeId)
        assertEquals(listOf(HueTarget(HueTargetType.ROOM, "r1", "Living", "g1"), HueTarget(HueTargetType.LIGHT, "l1", "Desk")), targets)
        assertTrue(service.test(userId, bridgeId, targets))
        awaitSent(2)
        assertEquals(setOf("g1", "l1"), sent.map { it.first }.toSet())
        assertNotNull(service.listBridges().single().lastSeen)
        assertFalse(service.test(userId, bridgeId, emptyList()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `pairing waits for the button and stores the bridge`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val pairApi = mockk<HueBridgeApi>(relaxed = true)
        var attempts = 0
        coEvery { pairApi.pair(any()) } answers { if (++attempts < 2) null else HuePairSuccess("app-key", "client-key") }
        coEvery { pairApi.bridge() } returns ClipBridge("uuid", "001788FFFE0000AA")
        service.pairingClientFactory = { _, _ -> pairApi }
        service.authenticatedClientFactory = { _, _, _ -> pairApi }
        service.pairingPoll = 50

        val states = service.startPairing(userId, "192.0.2.20").toList()
        assertEquals(HuePairingState.PAIRED, states.last().state)
        assertTrue(states.any { it.state == HuePairingState.WAITING_FOR_BUTTON })
        val stored = service.listBridges().single()
        assertEquals("001788fffe0000aa", stored.bridgeId)
        assertEquals("192.0.2.20", stored.ip)
        assertEquals(stored, states.last().bridge)
        assertTrue(service.activePairings().isEmpty())
    }
}
