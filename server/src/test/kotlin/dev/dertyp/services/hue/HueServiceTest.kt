package dev.dertyp.services.hue

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.data.AudioBand
import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HueMotionMode
import dev.dertyp.data.HuePairingState
import dev.dertyp.data.HuePairingStatus
import dev.dertyp.data.HueScene
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.Image
import dev.dertyp.data.SongAudioBand
import dev.dertyp.data.SongAudioData
import dev.dertyp.data.SongAudioTimeline
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
import io.mockk.clearConstructorMockk
import io.mockk.clearStaticMockk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HueServiceTest {
    private companion object {
        const val STOP_GRACE_MS = 3_000L
        const val PAIRING_POLL_MS = 2_000L
        const val SLOW_MOTION_INTERVAL_MS = 8_000L

        val constructed = arrayOf(HueBridgeClient::class, HueDtlsStream::class, HueEntertainmentSession::class)
        val facades = arrayOf(
            Class.forName("kotlinx.coroutines.DelayKt").kotlin,
            Class.forName("kotlinx.coroutines.flow.StateFlowKt").kotlin,
        )

        @BeforeAll
        @JvmStatic
        fun mockGlobals() {
            mockkConstructor(*constructed)
            mockkStatic(*facades)
        }

        @AfterAll
        @JvmStatic
        fun unmockGlobals() {
            unmockkConstructor(*constructed)
            unmockkStatic(*facades)
        }
    }

    private lateinit var database: Database
    private lateinit var songService: SongService
    private lateinit var imageService: ImageService
    private lateinit var audioAnalysisService: AudioAnalysisService
    private lateinit var api: HueBridgeApi
    private lateinit var pairingApi: HueBridgeApi
    private lateinit var streamTarget: HueEntertainmentStream
    private lateinit var service: HueService
    private val userId = UUID.randomUUID()
    private val areaId = "0b216bc8-1d1a-4a2f-8b8c-4d5e6f708192"
    private val sent = CopyOnWriteArrayList<Pair<String, LightUpdate>>()
    private val recalled = CopyOnWriteArrayList<Pair<String, SceneRecallUpdate>>()
    private val streaming = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val streamJobs = CopyOnWriteArraySet<Job>()
    private val playbackClocks = CopyOnWriteArrayList<MutableStateFlow<PlaybackClock>>()

    private class FakeStream(private val failStart: Boolean = false) : HueEntertainmentStream {
        @Volatile
        var started = false
        @Volatile
        var closed = false
        val frames = CopyOnWriteArrayList<ByteArray>()

        override suspend fun start() {
            if (failStart) throw HueBridgeException("handshake failed")
            started = true
        }

        override fun send(frame: ByteArray) {
            frames += frame
        }

        override fun close() {
            closed = true
        }
    }

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "hue_test", UserTable, HueBridgeTable, HueUserLinkTable)
        songService = mockk()
        imageService = mockk()
        audioAnalysisService = mockk()
        api = mockk(relaxed = true)
        coEvery { api.putLight(any(), any()) } answers { sent += (firstArg<String>() to secondArg<LightUpdate>()) }
        coEvery {
            api.putGroupedLight(
                any(),
                any()
            )
        } answers { sent += (firstArg<String>() to secondArg<LightUpdate>()) }
        coEvery {
            api.recallScene(
                any(),
                any()
            )
        } answers { recalled += (firstArg<String>() to secondArg<SceneRecallUpdate>()) }
        coEvery { api.entertainmentConfigurations() } returns emptyList()
        coEvery { api.entertainmentServices() } returns emptyList()
        coEvery {
            api.setEntertainmentStreaming(
                any(),
                any()
            )
        } answers { streaming += (firstArg<String>() to secondArg<Boolean>()) }
        routeBridgeClients()
        routeStreams()
        trackMotions()
        coEvery { delay(STOP_GRACE_MS) } coAnswers { delay(100L) }
        startKoin {
            modules(module {
                single<HookBus> { HookService() }
                single { songService }
                single { imageService }
                single { audioAnalysisService }
                single { HueDiscoveryService() }
                single { mockk<HttpClientFactory>(relaxed = true) }
            })
        }
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "hue"
                it[passwordHash] = "hash"
            }
        }
        service = HueService()
    }

    @AfterEach
    fun tearDown() {
        try {
            if (::service.isInitialized) runBlocking { service.stopService() }
        } finally {
            clearConstructorMockk(*constructed)
            clearStaticMockk(*facades)
            unmockkObject(HueLightScore)
            stopKoin()
            TestDatabase.cleanUp()
        }
    }

    private fun routeBridgeClients() {
        coEvery { anyConstructed<HueBridgeClient>().pair(any()) } coAnswers { pairingApi.pair(firstArg()) }
        coEvery { anyConstructed<HueBridgeClient>().bridge() } coAnswers { pairingApi.bridge() }
        coEvery { anyConstructed<HueBridgeClient>().config() } coAnswers { api.config() }
        coEvery { anyConstructed<HueBridgeClient>().lights() } coAnswers { api.lights() }
        coEvery { anyConstructed<HueBridgeClient>().rooms() } coAnswers { api.rooms() }
        coEvery { anyConstructed<HueBridgeClient>().zones() } coAnswers { api.zones() }
        coEvery { anyConstructed<HueBridgeClient>().groupedLights() } coAnswers { api.groupedLights() }
        coEvery { anyConstructed<HueBridgeClient>().scenes() } coAnswers { api.scenes() }
        coEvery { anyConstructed<HueBridgeClient>().entertainmentConfigurations() } coAnswers { api.entertainmentConfigurations() }
        coEvery { anyConstructed<HueBridgeClient>().entertainmentServices() } coAnswers { api.entertainmentServices() }
        coEvery {
            anyConstructed<HueBridgeClient>().setEntertainmentStreaming(
                any(),
                any()
            )
        } coAnswers { api.setEntertainmentStreaming(firstArg(), secondArg()) }
        coEvery { anyConstructed<HueBridgeClient>().putLight(any(), any()) } coAnswers {
            api.putLight(
                firstArg(),
                secondArg()
            )
        }
        coEvery { anyConstructed<HueBridgeClient>().putGroupedLight(any(), any()) } coAnswers {
            api.putGroupedLight(
                firstArg(),
                secondArg()
            )
        }
        coEvery { anyConstructed<HueBridgeClient>().recallScene(any(), any()) } coAnswers {
            api.recallScene(
                firstArg(),
                secondArg()
            )
        }
        every { anyConstructed<HueBridgeClient>().close() } answers { api.close() }
    }

    private fun routeStreams() {
        coEvery { anyConstructed<HueDtlsStream>().start() } coAnswers { streamTarget.start() }
        every { anyConstructed<HueDtlsStream>().send(any()) } answers { streamTarget.send(firstArg()) }
        every { anyConstructed<HueDtlsStream>().close() } answers { streamTarget.close() }
        every { anyConstructed<HueEntertainmentSession>().launch(any()) } answers { callOriginal().also { streamJobs += it } }
    }

    private fun trackMotions() {
        every { MutableStateFlow(ofType<PlaybackClock>()) } answers { callOriginal().also { playbackClocks += it } }
    }

    private fun runningStreams(): Int = streamJobs.count { it.isActive }

    private fun runningMotions(): Int = playbackClocks.count { it.subscriptionCount.value > 0 }

    private suspend fun awaitRunningMotions(): Int {
        repeat(50) {
            if (runningMotions() > 0) return runningMotions()
            delay(20)
        }
        return runningMotions()
    }

    private fun insertUser(name: String): UUID {
        val newId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = newId
                it[username] = name
                it[passwordHash] = "hash"
            }
        }
        return newId
    }

    private fun bridge(owner: UUID = userId, hardwareId: String = "001788fffe000001"): UUID = transaction(database) {
        HueBridgeTable.insertAndGetId {
            it[bridgeId] = hardwareId
            it[ip] = "192.0.2.10"
            it[name] = "Test Bridge"
            it[applicationKey] = "key"
            it[clientKey] = "0123456789abcdef0123456789abcdef"
            it[userId] = EntityID(owner, UserTable)
            it[createdAt] = 1L
        }.value
    }

    private fun light(id: String, name: String) = HueTarget(HueTargetType.LIGHT, id, name)

    private fun colorLight(id: String, name: String, device: String, points: Int? = null) = ClipLight(
        id,
        ClipMetadata(name),
        color = ClipColor(ClipXy(0.3, 0.3)),
        gradient = points?.let { ClipGradient(pointsCapable = it) },
        owner = ClipResourceRef(device, "device"),
    )

    private fun entertainmentBridge(active: Boolean = false) {
        coEvery { api.lights() } returns listOf(
            colorLight("l1", "Desk", "d1"),
            colorLight("l2", "Shelf", "d2"),
            colorLight("l3", "Lamp", "d3"),
        )
        coEvery { api.rooms() } returns emptyList()
        coEvery { api.zones() } returns emptyList()
        coEvery { api.entertainmentServices() } returns listOf(
            ClipEntertainment("e1", ClipResourceRef("d1", "device")),
            ClipEntertainment("e2", ClipResourceRef("d2", "device")),
        )
        coEvery { api.entertainmentConfigurations() } returns listOf(
            ClipEntertainmentConfiguration(
                id = areaId,
                metadata = ClipMetadata("Living"),
                status = if (active) "active" else "inactive",
                channels = listOf(
                    ClipEntertainmentChannel(
                        0,
                        ClipPosition(-0.5, 0.0, 0.0),
                        listOf(ClipChannelMember(ClipResourceRef("e1", "entertainment"), 0))
                    ),
                    ClipEntertainmentChannel(
                        1,
                        ClipPosition(0.5, 0.0, 0.0),
                        listOf(ClipChannelMember(ClipResourceRef("e2", "entertainment"), 0))
                    ),
                ),
                lightServices = listOf(ClipResourceRef("l1", "light"), ClipResourceRef("l2", "light")),
            ),
        )
    }

    private fun area(id: String = areaId, name: String = "Living") = HueTarget(HueTargetType.ENTERTAINMENT, id, name)

    private suspend fun awaitSent(count: Int) {
        repeat(100) {
            if (sent.size >= count) return
            delay(20)
        }
        throw AssertionError("expected $count commands, got ${sent.size}")
    }

    private suspend fun awaitRecalled(count: Int) {
        repeat(150) {
            if (recalled.size >= count) return
            delay(20)
        }
        throw AssertionError("expected $count scene recalls, got ${recalled.size}")
    }

    private suspend fun awaitStreaming(count: Int) {
        repeat(150) {
            if (streaming.size >= count) return
            delay(20)
        }
        throw AssertionError("expected $count streaming actions, got $streaming")
    }

    private suspend fun awaitFrames(stream: FakeStream, count: Int) {
        repeat(150) {
            if (stream.frames.size >= count) return
            delay(20)
        }
        throw AssertionError("expected $count frames, got ${stream.frames.size}")
    }

    private suspend fun awaitDimmed(threshold: Double): List<Double> {
        repeat(100) {
            val dimmings = sent.mapNotNull { it.second.dimming?.brightness }
            val below = dimmings.filter { it < threshold }
            if (below.isNotEmpty()) return below
            delay(20)
        }
        throw AssertionError("no command below $threshold, got ${sent.mapNotNull { it.second.dimming?.brightness }}")
    }

    private fun song(id: UUID, coverId: UUID?): UserSong = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.coverId } returns coverId
        every { album } returns null
    }

    private fun playingSong(songId: UUID, coverId: UUID, palette: List<Int>, duration: Long = 60_000L): UserSong {
        val song = song(songId, coverId)
        every { song.duration } returns duration
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song)
        coEvery { imageService.byId(coverId) } returns Image(
            coverId,
            "p",
            "h",
            "o",
            palette = palette,
            primaryColor = palette.first()
        )
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns emptyMap()
        return song
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `links round trip and validate targets`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        assertTrue(service.getLinks(userId).isEmpty())
        val link = HueUserLink(
            bridgeId,
            true,
            listOf(light("l1", "Desk")),
            HueIntensity.HIGH,
            HueTransitionMode.BPM,
            700,
            HueStopMode.OFF
        )
        val saved = service.setLink(userId, link)
        assertTrue(saved.updatedAt > 0)
        val loaded = service.getLinks(userId).single()
        assertEquals(link.copy(updatedAt = loaded.updatedAt), loaded)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.setLink(
                    userId,
                    HueUserLink(bridgeId, enabled = true)
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.setLink(
                    userId,
                    HueUserLink(UUID.randomUUID(), enabled = false)
                )
            }
        }
        assertTrue(service.removeLink(userId, bridgeId))
        assertTrue(service.getLinks(userId).isEmpty())
        assertEquals(1, service.listBridges(userId).size)
        assertTrue(service.removeBridge(userId, bridgeId))
        assertTrue(service.listBridges(userId).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a bridge belongs to the user who paired it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val second = insertUser("second")
        val bridgeId = bridge()

        assertTrue(service.listBridges(second).isEmpty())
        assertNull(service.bridge(second, bridgeId))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.setLink(second, HueUserLink(bridgeId, true, listOf(light("l1", "Desk")))) }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.listTargets(second, bridgeId) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.test(
                    second,
                    bridgeId,
                    listOf(light("l1", "Desk"))
                )
            }
        }
        assertFalse(service.removeBridge(second, bridgeId))

        assertEquals(1, service.listBridges(userId).size)
        assertNotNull(service.bridge(userId, bridgeId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `two users pair the same bridge into separate rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val second = insertUser("second")
        val pairApi = mockk<HueBridgeApi>(relaxed = true)
        var attempts = 0
        coEvery { pairApi.pair(any()) } answers {
            if (++attempts < 2) null else HuePairSuccess(
                "app-key",
                "client-key"
            )
        }
        coEvery { pairApi.bridge() } returns ClipBridge("uuid", "001788FFFE0000AA")
        pairingApi = pairApi
        coEvery { delay(PAIRING_POLL_MS) } coAnswers { delay(50L) }

        val first = service.beginPairing(userId, "192.0.2.20")
        assertEquals(1, service.activePairings(userId).size)
        assertTrue(service.activePairings(second).isEmpty())
        assertNotNull(service.pairingSession(userId, "192.0.2.20"))
        assertNull(service.pairingSession(second, "192.0.2.20"))
        first.job?.join()

        assertEquals(HuePairingState.PAIRED, service.startPairing(second, "192.0.2.20").toList().last().state)
        val mine = service.listBridges(userId).single()
        val theirs = service.listBridges(second).single()
        assertEquals("001788fffe0000aa", mine.bridgeId)
        assertEquals(mine.bridgeId, theirs.bridgeId)
        assertNotEquals(mine.id, theirs.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `now playing drives enabled links and stops turn lights off`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(
            userId,
            HueUserLink(bridgeId, true, listOf(light("l1", "Desk"), light("l2", "Shelf")), onStop = HueStopMode.OFF)
        )
        val other = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song(songId, coverId))
        coEvery { imageService.byId(coverId) } returns Image(
            coverId,
            "p",
            "h",
            "o",
            palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()),
            primaryColor = 0xFFE01020.toInt()
        )
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns mapOf(
            songId to SongAudioData(
                energy = 0.9,
                bpm = 120.0
            )
        )

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
    fun `rooms are expanded into their color lights and switched off as a group`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        coEvery { api.lights() } returns listOf(
            colorLight("l1", "Desk", "d1"),
            colorLight("l2", "Shelf", "d2"),
            ClipLight("plug", ClipMetadata("Plug"), owner = ClipResourceRef("d3", "device")),
        )
        coEvery { api.rooms() } returns listOf(
            ClipGroup(
                "r1",
                ClipMetadata("Living"),
                children = listOf(
                    ClipResourceRef("d1", "device"),
                    ClipResourceRef("d2", "device"),
                    ClipResourceRef("d3", "device")
                ),
                services = listOf(ClipResourceRef("g1", "grouped_light")),
            ),
        )
        coEvery { api.zones() } returns emptyList()
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(HueTarget(HueTargetType.ROOM, "r1", "Living", "g1")),
                onStop = HueStopMode.OFF
            )
        )
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, 0))
        awaitSent(2)
        delay(100)
        assertEquals(setOf("l1", "l2"), sent.map { it.first }.toSet())
        assertEquals(2, sent.mapNotNull { it.second.color?.xy }.distinct().size)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 2, 0))
        awaitSent(3)
        assertEquals("g1" to false, sent.last().first to sent.last().second.on?.on)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a gradient light receives one point per palette color`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        coEvery { api.lights() } returns listOf(colorLight("l1", "Strip", "d1", points = 3))
        coEvery { api.rooms() } returns emptyList()
        coEvery { api.zones() } returns emptyList()
        service.setLink(userId, HueUserLink(bridgeId, true, listOf(light("l1", "Strip"))))
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt(), 0xFF20E030.toInt()))

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, 0))
        awaitSent(1)
        val update = sent.single().second
        assertNull(update.color)
        assertEquals(3, update.gradient?.points?.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `scene mode recalls the configured scenes after the grace period`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(HueTarget(HueTargetType.ROOM, "r1", "Living", "g1")),
                onStop = HueStopMode.SCENE,
                stopScenes = listOf(
                    HueScene("s1", "Relax", HueTargetType.ROOM, "r1", "Living"),
                    HueScene("s2", "Read", HueTargetType.ZONE, "z1", "Desk"),
                ),
            ),
        )
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song(songId, null))
        coEvery { audioAnalysisService.getAudioDataBatch(any()) } returns emptyMap<UUID, SongAudioData>()

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, 0))
        awaitSent(1)
        assertTrue(recalled.isEmpty())

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 2, 0))
        delay(50)
        assertTrue(recalled.isEmpty())

        awaitRecalled(2)
        assertEquals(
            setOf(
                "s1" to SceneRecallUpdate(ClipSceneRecall("active", 400)),
                "s2" to SceneRecallUpdate(ClipSceneRecall("active", 400))
            ),
            recalled.toSet(),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLink rejects scene mode without scenes and dedupes scenes per group`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.setLink(
                    userId,
                    HueUserLink(bridgeId, true, listOf(light("l1", "Desk")), onStop = HueStopMode.SCENE)
                )
            }
        }
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(HueTarget(HueTargetType.ROOM, "r1", "Living", "g1")),
                onStop = HueStopMode.SCENE,
                stopScenes = listOf(
                    HueScene("s1", "Relax", HueTargetType.ROOM, "r1", "Living"),
                    HueScene("s2", "Chill", HueTargetType.ROOM, "r1", "Living"),
                ),
            ),
        )
        val loaded = service.getLinks(userId).single()
        assertEquals(1, loaded.stopScenes.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLink rejects more than one entertainment area`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.setLink(
                    userId,
                    HueUserLink(bridgeId, true, listOf(area(), area("0b216bc8-1d1a-4a2f-8b8c-4d5e6f708193", "Kitchen")))
                )
            }
        }
        assertNotNull(service.setLink(userId, HueUserLink(bridgeId, true, listOf(area(), light("l3", "Lamp")))))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a resume of the same song within the grace period re-applies the colors`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(userId, HueUserLink(bridgeId, true, listOf(light("l1", "Desk")), onStop = HueStopMode.OFF))
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song(songId, null))
        coEvery { audioAnalysisService.getAudioDataBatch(any()) } returns emptyMap<UUID, SongAudioData>()

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, 0))
        awaitSent(1)
        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 2, 0))
        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 3, 0))
        awaitSent(2)
        delay(250)
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.second.on?.on == true })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ambient motion keeps sending rotated frames until playback stops`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        mockkObject(HueLightScore)
        every { HueLightScore.build(null, null, any(), SLOW_MOTION_INTERVAL_MS, any()) } answers {
            HueLightScore.build(
                null,
                null,
                thirdArg(),
                400L,
                arg(4)
            )
        }
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(light("l1", "Desk"), light("l2", "Shelf")),
                motion = HueMotionMode.SLOW,
                latencyMs = 0
            )
        )
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song(songId, coverId))
        coEvery { imageService.byId(coverId) } returns Image(
            coverId,
            "p",
            "h",
            "o",
            palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()),
            primaryColor = 0xFFE01020.toInt()
        )
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns emptyMap()

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitSent(6)
        assertEquals(1, awaitRunningMotions())
        val firstColors = sent.take(2).map { it.second.color!!.xy }
        val laterColors = sent.drop(2).take(2).map { it.second.color!!.xy }
        assertEquals(firstColors.reversed(), laterColors)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 2, System.currentTimeMillis()))
        delay(500)
        assertEquals(0, runningMotions())
        val count = sent.size
        delay(300)
        assertEquals(count, sent.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `tempo motion follows the beat grid and playback reports`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(light("l1", "Desk"), light("l2", "Shelf")),
                motion = HueMotionMode.TEMPO,
                latencyMs = 0
            )
        )
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        val song = song(songId, coverId)
        every { song.duration } returns 60_000L
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song)
        coEvery { imageService.byId(coverId) } returns Image(
            coverId,
            "p",
            "h",
            "o",
            palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()),
            primaryColor = 0xFFE01020.toInt()
        )
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns emptyMap()
        coEvery { audioAnalysisService.getAudioTimeline(songId) } returns SongAudioTimeline(
            songId,
            beatsMs = List(120) { it * 500 })

        val startedAt = System.currentTimeMillis()
        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, startedAt))
        awaitSent(6)
        assertEquals(1, awaitRunningMotions())
        coVerify(exactly = 1) { audioAnalysisService.getAudioTimeline(songId) }

        service.onNowPlaying(
            HookEvent.NowPlayingChanged(
                userId,
                songId,
                2,
                System.currentTimeMillis(),
                positionMs = System.currentTimeMillis() - startedAt
            )
        )
        assertEquals(1, awaitRunningMotions())
        coVerify(exactly = 1) { songService.byIds(listOf(songId), userId) }

        service.onNowPlaying(
            HookEvent.NowPlayingChanged(
                userId,
                songId,
                3,
                System.currentTimeMillis(),
                positionMs = 30_000,
                playing = false
            )
        )
        delay(200)
        val paused = sent.size
        delay(700)
        assertEquals(paused, sent.size)
        assertEquals(1, awaitRunningMotions())

        service.onNowPlaying(
            HookEvent.NowPlayingChanged(
                userId,
                songId,
                4,
                System.currentTimeMillis(),
                positionMs = 30_000,
                playing = true
            )
        )
        awaitSent(paused + 2)
        assertEquals(1, awaitRunningMotions())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `bass motion dims below the loudness floor on beats without a kick`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(light("l1", "Desk"), light("l2", "Shelf")),
                motion = HueMotionMode.BASS,
                latencyMs = 0
            )
        )
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        val song = song(songId, coverId)
        every { song.duration } returns 60_000L
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song)
        coEvery { imageService.byId(coverId) } returns Image(
            coverId,
            "p",
            "h",
            "o",
            palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()),
            primaryColor = 0xFFE01020.toInt()
        )
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns emptyMap()
        val bass = List(600) { index ->
            val ms = index * 100
            if ((ms / 500) % 4 == 0 && ms % 500 < 150) -6f else -60f
        }
        coEvery { audioAnalysisService.getAudioTimeline(songId) } returns
                SongAudioTimeline(songId, beatsMs = List(120) { it * 500 }, envelopeHz = 10, bassEnvelopeDb = bass)

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitSent(6)
        assertEquals(1, awaitRunningMotions())
        coVerify(exactly = 1) { audioAnalysisService.getAudioTimeline(songId) }

        val base = HuePaletteMapper.brightness(HueIntensity.MEDIUM, SongAudioData.DEFAULT_ENERGY, null)
        val dimmed = awaitDimmed(base * 0.55)
        assertTrue(dimmed.all { it >= base * 0.29 }, dimmed.toString())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `bass motion with band levels dims between kicks`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(light("l1", "Desk"), light("l2", "Shelf")),
                motion = HueMotionMode.BASS,
                latencyMs = 0
            )
        )
        val songId = UUID.randomUUID()
        val coverId = UUID.randomUUID()
        val song = song(songId, coverId)
        every { song.duration } returns 60_000L
        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(song)
        coEvery { imageService.byId(coverId) } returns Image(
            coverId,
            "p",
            "h",
            "o",
            palette = listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()),
            primaryColor = 0xFFE01020.toInt()
        )
        coEvery { audioAnalysisService.getAudioDataBatch(listOf(songId)) } returns emptyMap()
        val kickLevels = List(3_000) { index ->
            val ms = index * 20
            val beatIndex = ms / 500
            val withinBeat = ms % 500
            if (beatIndex % 4 == 0 && withinBeat < 60) -20f else -40f
        }
        val subLevels = List(3_000) { -50f }
        val loudnessLevels = List(600) { -30f + (it % 5) * 2f }
        coEvery { audioAnalysisService.getAudioTimeline(songId) } returns SongAudioTimeline(
            songId,
            beatsMs = List(120) { it * 500 },
            envelopeHz = 10,
            envelopeDb = loudnessLevels,
            bandHz = 50,
            bands = listOf(
                SongAudioBand(AudioBand.SUB, 20, 60, subLevels),
                SongAudioBand(AudioBand.KICK, 60, 130, kickLevels),
            ),
        )

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitSent(6)
        assertEquals(1, awaitRunningMotions())
        coVerify(exactly = 1) { audioAnalysisService.getAudioTimeline(songId) }

        val base = HuePaletteMapper.brightness(HueIntensity.MEDIUM, SongAudioData.DEFAULT_ENERGY, null)
        val dimmed = awaitDimmed(base * 0.55)
        assertTrue(dimmed.all { it >= base * 0.29 }, dimmed.toString())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an entertainment area streams while a song plays and stops afterwards`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        entertainmentBridge()
        val stream = FakeStream()
        streamTarget = stream
        service.setLink(
            userId,
            HueUserLink(
                bridgeId,
                true,
                listOf(area(), light("l1", "Desk"), light("l3", "Lamp")),
                onStop = HueStopMode.OFF
            )
        )
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitFrames(stream, 3)
        awaitSent(1)
        assertTrue(stream.started)
        assertEquals(1, runningStreams())
        assertEquals(listOf(areaId to true), streaming.toList())
        assertEquals(listOf("l3"), sent.map { it.first })

        val nextSong = UUID.randomUUID()
        playingSong(nextSong, UUID.randomUUID(), listOf(0xFF20E030.toInt(), 0xFFE0A010.toInt()))
        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, nextSong, 2, System.currentTimeMillis()))
        awaitSent(2)
        assertEquals(1, runningStreams())
        assertFalse(stream.closed)
        assertEquals(listOf(areaId to true), streaming.toList())

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, null, 3, System.currentTimeMillis()))
        awaitStreaming(2)
        assertEquals(areaId to false, streaming.last())
        assertTrue(stream.closed)
        assertEquals(0, runningStreams())
        awaitSent(5)
        assertEquals(setOf("l1", "l2", "l3"), sent.drop(2).map { it.first }.toSet())
        assertTrue(sent.drop(2).all { it.second.on?.on == false })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a foreign streamer is reported and the remaining lights keep their colors`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        entertainmentBridge(active = true)
        val stream = FakeStream()
        streamTarget = stream
        service.setLink(userId, HueUserLink(bridgeId, true, listOf(area())))
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        delay(200)
        assertEquals(0, runningStreams())
        assertFalse(stream.started)
        assertTrue(streaming.isEmpty())
        assertTrue(sent.isEmpty())
        assertTrue(service.listBridges(userId).single().lastError?.contains("already streamed") == true)

        service.setLink(userId, HueUserLink(bridgeId, true, listOf(area(), light("l3", "Lamp"))))
        val nextSong = UUID.randomUUID()
        playingSong(nextSong, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))
        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, nextSong, 2, System.currentTimeMillis()))
        awaitSent(1)
        assertEquals(listOf("l3"), sent.map { it.first })
        assertEquals(0, runningStreams())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failed handshake releases the entertainment area`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        entertainmentBridge()
        val stream = FakeStream(failStart = true)
        streamTarget = stream
        service.setLink(userId, HueUserLink(bridgeId, true, listOf(area())))
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitStreaming(2)
        assertEquals(listOf(areaId to true, areaId to false), streaming.toList())
        assertTrue(stream.closed)
        assertFalse(stream.started)
        assertEquals(0, runningStreams())
        assertNotNull(service.listBridges(userId).single().lastError)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `tempo motion varies the streamed channel colors`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        entertainmentBridge()
        val stream = FakeStream()
        streamTarget = stream
        service.setLink(
            userId,
            HueUserLink(bridgeId, true, listOf(area()), motion = HueMotionMode.TEMPO, latencyMs = 0)
        )
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))
        coEvery { audioAnalysisService.getAudioTimeline(songId) } returns SongAudioTimeline(
            songId,
            beatsMs = List(120) { it * 500 })

        service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
        awaitFrames(stream, 20)
        assertEquals(1, runningStreams())
        val payloads = stream.frames.map { it.drop(20) }.distinct()
        assertTrue(payloads.size > 1, "expected varying channel data, got ${payloads.size} distinct payloads")
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
        coEvery { api.rooms() } returns listOf(
            ClipGroup(
                "r1",
                ClipMetadata("Living"),
                services = listOf(ClipResourceRef("g1", "grouped_light"))
            )
        )
        coEvery { api.zones() } returns emptyList()
        val targets = service.listTargets(userId, bridgeId)
        assertEquals(
            listOf(
                HueTarget(HueTargetType.ROOM, "r1", "Living", "g1"),
                HueTarget(HueTargetType.LIGHT, "l1", "Desk")
            ), targets
        )
        assertTrue(service.test(userId, bridgeId, targets))
        awaitSent(2)
        assertEquals(setOf("g1", "l1"), sent.map { it.first }.toSet())
        assertNotNull(service.listBridges(userId).single().lastSeen)
        assertFalse(service.test(userId, bridgeId, emptyList()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `entertainment areas are offered as targets`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        entertainmentBridge()
        val targets = service.listTargets(userId, bridgeId)
        assertEquals(area(), targets.last())
        assertEquals(listOf("l1", "l2", "l3"), targets.filter { it.type == HueTargetType.LIGHT }.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listScenes maps scenes to their rooms and zones`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val bridgeId = bridge()
        coEvery { api.lights() } returns emptyList()
        coEvery { api.rooms() } returns listOf(
            ClipGroup(
                "r1",
                ClipMetadata("Living"),
                services = listOf(ClipResourceRef("g1", "grouped_light"))
            )
        )
        coEvery { api.zones() } returns listOf(
            ClipGroup(
                "z1",
                ClipMetadata("Desk"),
                services = listOf(ClipResourceRef("g2", "grouped_light"))
            )
        )
        coEvery { api.scenes() } returns listOf(
            ClipScene("s1", ClipMetadata("Relax"), ClipResourceRef("r1", "room")),
            ClipScene("s2", ClipMetadata("Read"), ClipResourceRef("z1", "zone")),
            ClipScene("s3", ClipMetadata("Orphan"), ClipResourceRef("nope", "room")),
            ClipScene("s4", ClipMetadata("NoGroup")),
        )

        assertEquals(
            listOf(
                HueScene("s2", "Read", HueTargetType.ZONE, "z1", "Desk"),
                HueScene("s1", "Relax", HueTargetType.ROOM, "r1", "Living"),
            ),
            service.listScenes(userId, bridgeId),
        )
        service.listScenes(userId, bridgeId)
        coVerify(exactly = 1) { api.scenes() }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.listScenes(
                    userId,
                    UUID.randomUUID()
                )
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `pairing waits for the button and stores the bridge`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val pairApi = mockk<HueBridgeApi>(relaxed = true)
        var attempts = 0
        coEvery { pairApi.pair(any()) } answers {
            if (++attempts < 2) null else HuePairSuccess(
                "app-key",
                "client-key"
            )
        }
        coEvery { pairApi.bridge() } returns ClipBridge("uuid", "001788FFFE0000AA")
        pairingApi = pairApi
        coEvery { delay(PAIRING_POLL_MS) } coAnswers { delay(50L) }

        val states = service.startPairing(userId, "192.0.2.20").toList()
        assertEquals(HuePairingState.PAIRED, states.last().state)
        assertTrue(states.any { it.state == HuePairingState.WAITING_FOR_BUTTON })
        val stored = service.listBridges(userId).single()
        assertEquals("001788fffe0000aa", stored.bridgeId)
        assertEquals("192.0.2.20", stored.ip)
        assertEquals(stored, states.last().bridge)
        assertTrue(service.activePairings(userId).isEmpty())
    }

    private suspend fun changeDuringHandler(dialect: DbDialect, change: suspend (UUID) -> Unit) {
        setup(dialect)
        val bridgeId = bridge()
        mockkObject(HueLightScore)
        every { HueLightScore.build(null, null, any(), SLOW_MOTION_INTERVAL_MS, any()) } answers {
            HueLightScore.build(
                null,
                null,
                thirdArg(),
                400L,
                arg(4)
            )
        }
        service.setLink(
            userId,
            HueUserLink(bridgeId, true, listOf(light("l1", "Desk")), motion = HueMotionMode.SLOW, latencyMs = 0)
        )
        val songId = UUID.randomUUID()
        playingSong(songId, UUID.randomUUID(), listOf(0xFFE01020.toInt(), 0xFF1030E0.toInt()))
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        coEvery { api.lights() } coAnswers {
            reached.complete(Unit)
            gate.await()
            emptyList()
        }
        try {
            coroutineScope {
                val handler = launch(Dispatchers.Default) {
                    service.onNowPlaying(HookEvent.NowPlayingChanged(userId, songId, 1, System.currentTimeMillis()))
                }
                reached.await()
                val changed = launch(Dispatchers.Default) { change(bridgeId) }
                withTimeoutOrNull(500) { changed.join() }
                gate.complete(Unit)
                handler.join()
                changed.join()
            }
        } finally {
            gate.complete(Unit)
        }
        delay(300)
        val count = sent.size
        delay(900)
        assertEquals(0, runningMotions())
        assertEquals(count, sent.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a link changed while a song starts leaves no motion of the old link`(dialect: DbDialect) = runBlocking {
        changeDuringHandler(dialect) { bridgeId ->
            service.setLink(userId, HueUserLink(bridgeId, true, listOf(light("l2", "Shelf"))))
        }
        assertEquals(listOf(light("l2", "Shelf")), service.getLinks(userId).single().targets)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a link removed while a song starts leaves no motion`(dialect: DbDialect) = runBlocking {
        changeDuringHandler(dialect) { bridgeId -> assertTrue(service.removeLink(userId, bridgeId)) }
        assertTrue(service.getLinks(userId).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a bridge removed while a song starts leaves no motion`(dialect: DbDialect) = runBlocking {
        changeDuringHandler(dialect) { bridgeId -> assertTrue(service.removeBridge(userId, bridgeId)) }
        assertTrue(service.listBridges(userId).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `two concurrent pairing requests for one bridge share one pairing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val pairApi = mockk<HueBridgeApi>(relaxed = true)
        val polls = AtomicInteger()
        coEvery { pairApi.pair(any()) } coAnswers {
            polls.incrementAndGet()
            awaitCancellation()
        }
        pairingApi = pairApi
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = AtomicBoolean()
        every { MutableStateFlow(ofType<HuePairingStatus>()) } answers {
            if (held.compareAndSet(false, true)) {
                entered.countDown()
                release.await()
            }
            callOriginal()
        }
        try {
            val first = async(Dispatchers.IO) { service.beginPairing(userId, "192.0.2.20") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { service.beginPairing(userId, "192.0.2.20") }
            withTimeoutOrNull(500) { second.join() }
            release.countDown()
            assertSame(first.await(), second.await())
        } finally {
            release.countDown()
        }
        delay(200)
        assertEquals(1, polls.get())
        assertEquals(1, service.activePairings(userId).size)
    }
}
