package dev.dertyp.services.hue

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.data.HueBridgeInfo
import dev.dertyp.data.HueMotionMode
import dev.dertyp.data.HuePairingState
import dev.dertyp.data.HuePairingStatus
import dev.dertyp.data.HueScene
import dev.dertyp.data.HueStatus
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.SongAudioData
import dev.dertyp.db.HueBridgeTable
import dev.dertyp.db.HueUserLinkTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import dev.dertyp.services.AudioAnalysisService
import dev.dertyp.services.ImageService
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.utils.HueColor
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HueService : Service() {
    private val hooks by inject<HookBus>()
    private val songService by inject<SongService>()
    private val imageService by inject<ImageService>()
    private val audioAnalysisService by inject<AudioAnalysisService>()
    private val discoveryService by inject<HueDiscoveryService>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class BridgeRow(
        val id: UUID,
        val userId: UUID,
        val bridgeId: String,
        val ip: String,
        val name: String,
        val modelId: String?,
        val applicationKey: String,
        val clientKey: String?,
        val certFingerprint: String?,
        val lastSeen: Long?,
        val lastError: String?,
    ) {
        fun info() = HueBridgeInfo(id, bridgeId, ip, name, modelId, lastSeen, lastError)
    }

    class PairingSession(
        val userId: UUID,
        val ip: String,
        val state: MutableStateFlow<HuePairingStatus>,
        var job: Job? = null,
    )

    internal data class BridgeCatalog(
        val profiles: Map<String, LightProfile>,
        val names: Map<String, String>,
        val members: Map<String, List<String>>,
    )

    private class BridgeRuntime(val client: HueBridgeApi, val queue: HueCommandQueue)

    internal var clientFactory: (BridgeRow) -> HueBridgeApi = { row ->
        HueBridgeClient(row.ip, row.bridgeId, row.applicationKey, row.certFingerprint) { fingerprint ->
            scope.launch { dbQuery { HueBridgeTable.update({ HueBridgeTable.id eq row.id }) { it[certFingerprint] = fingerprint } } }
        }
    }

    internal var streamFactory: (BridgeRow, HueEntertainmentArea) -> HueEntertainmentStream = { row, _ ->
        HueDtlsStream(
            row.ip,
            row.applicationKey,
            row.clientKey ?: throw HueBridgeException("Bridge has no client key, re-pair the bridge"),
        )
    }

    private val runtimes = ConcurrentHashMap<UUID, BridgeRuntime>()
    private val pairings = ConcurrentHashMap<Pair<UUID, String>, PairingSession>()
    private val lastGeneration = ConcurrentHashMap<UUID, Long>()
    private val lastSong = ConcurrentHashMap<UUID, UUID>()
    private val lastCommandAt = ConcurrentHashMap<UUID, Long>()
    private val currentColors = ConcurrentHashMap<UUID, List<Int>>()
    private val animations = ConcurrentHashMap<Pair<UUID, UUID>, Job>()
    private val streams = ConcurrentHashMap<Pair<UUID, UUID>, HueEntertainmentSession>()
    private val pendingStops = ConcurrentHashMap<UUID, Job>()
    private val clocks = ConcurrentHashMap<UUID, MutableStateFlow<PlaybackClock>>()
    private val userLocks = ConcurrentHashMap<UUID, Mutex>()
    private val scoreCache: Cache<Pair<UUID, LevelSource>, LightScore> = Caffeine.newBuilder().maximumSize(256).build()

    internal var motionIntervalOverride: Long? = null
    internal var stopGraceMs: Long = STOP_GRACE.inWholeMilliseconds
    private val targetsCache = ConcurrentHashMap<UUID, Pair<Long, List<HueTarget>>>()
    private val scenesCache = ConcurrentHashMap<UUID, Pair<Long, List<HueScene>>>()
    private val catalogs = ConcurrentHashMap<UUID, BridgeCatalog>()
    private val areaCache = ConcurrentHashMap<UUID, List<HueEntertainmentArea>>()

    private val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = changeFlow.asSharedFlow()

    override suspend fun startService() {
        hooks.on<HookEvent.NowPlayingChanged> { onNowPlaying(it) }
    }

    override suspend fun stopService() {
        withTimeoutOrNull(STREAM_SHUTDOWN_TIMEOUT_MS) {
            streams.keys.toList().forEach { key -> stopStream(key.first, key.second) }
        }
        streams.values.forEach { it.close() }
        streams.clear()
        animations.values.forEach { it.cancel() }
        animations.clear()
        pendingStops.values.forEach { it.cancel() }
        pendingStops.clear()
        clocks.clear()
        userLocks.clear()
        runtimes.values.forEach { it.queue.close(); it.client.close() }
        runtimes.clear()
        scope.cancel()
    }

    suspend fun discover(userId: UUID, force: Boolean = false): List<HueBridgeCandidate> {
        val paired = bridges(userId)
        return discoveryService.discover(force = force).map { candidate ->
            candidate.copy(paired = paired.any { it.ip == candidate.ip || (candidate.bridgeId != null && it.bridgeId.equals(candidate.bridgeId, true)) })
        }
    }

    fun cachedDiscovery(userId: UUID): List<HueBridgeCandidate> = discoveryService.cached().map { it.copy(paired = false) }

    suspend fun bridges(userId: UUID): List<BridgeRow> = dbQuery {
        HueBridgeTable.selectAll().where { HueBridgeTable.userId eq userId }.orderBy(HueBridgeTable.name).map(::mapBridge)
    }

    suspend fun bridge(userId: UUID, id: UUID): BridgeRow? = dbQuery {
        HueBridgeTable.selectAll()
            .where { HueBridgeTable.id eq id }
            .andWhere { HueBridgeTable.userId eq userId }
            .singleOrNull()
            ?.let(::mapBridge)
    }

    suspend fun listBridges(userId: UUID): List<HueBridgeInfo> = bridges(userId).map { it.info() }

    fun pairingSession(userId: UUID, ip: String): PairingSession? =
        pairings[userId to ip]?.takeIf { it.job?.isActive == true }

    fun activePairings(userId: UUID): List<PairingSession> =
        pairings.values.filter { it.userId == userId && it.job?.isActive == true }

    fun beginPairing(userId: UUID, ip: String): PairingSession {
        val normalized = ip.trim()
        require(normalized.isNotEmpty()) { "Bridge IP is required" }
        val key = userId to normalized
        pairings[key]?.takeIf { it.job?.isActive == true }?.let { return it }
        val session = PairingSession(userId, normalized, MutableStateFlow(HuePairingStatus(HuePairingState.CONNECTING)))
        session.job = scope.launch { runPairing(session) }
        pairings[key] = session
        return session
    }

    fun startPairing(userId: UUID, ip: String): Flow<HuePairingStatus> {
        val session = beginPairing(userId, ip)
        return session.state.transformWhile { status ->
            emit(status)
            status.state == HuePairingState.CONNECTING || status.state == HuePairingState.WAITING_FOR_BUTTON
        }
    }

    internal var pairingClientFactory: (String, (String) -> Unit) -> HueBridgeApi = { ip, onFingerprint ->
        HueBridgeClient(ip, null, null, null, onFingerprint)
    }

    internal var authenticatedClientFactory: (String, String, String?) -> HueBridgeApi = { ip, key, fingerprint ->
        HueBridgeClient(ip, null, key, fingerprint)
    }

    internal var pairingPoll: Long = PAIRING_POLL.inWholeMilliseconds

    private suspend fun runPairing(session: PairingSession) {
        var fingerprint: String? = null
        val client = pairingClientFactory(session.ip) { fingerprint = it }
        try {
            val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT.inWholeMilliseconds
            while (System.currentTimeMillis() < deadline) {
                val success = client.pair(deviceType())
                if (success == null) {
                    session.state.value = HuePairingStatus(HuePairingState.WAITING_FOR_BUTTON)
                    changeFlow.tryEmit(Unit)
                    delay(pairingPoll)
                    continue
                }
                val authenticated = authenticatedClientFactory(session.ip, success.username, fingerprint)
                val bridge = try {
                    authenticated.bridge()
                } finally {
                    authenticated.close()
                }
                val hardwareId = (bridge.bridgeId ?: bridge.id).lowercase()
                val row = upsertBridge(session.userId, hardwareId, session.ip, success, fingerprint)
                runtimes.remove(row.id)?.let { it.queue.close(); it.client.close() }
                session.state.value = HuePairingStatus(HuePairingState.PAIRED, bridge = row.info())
                changeFlow.tryEmit(Unit)
                return
            }
            session.state.value = HuePairingStatus(HuePairingState.TIMEOUT)
        } catch (e: Exception) {
            logger.warn("Hue pairing with ${session.ip} failed: ${e.message}")
            session.state.value = HuePairingStatus(HuePairingState.ERROR, e.message)
        } finally {
            client.close()
            changeFlow.tryEmit(Unit)
        }
    }

    private suspend fun upsertBridge(
        userId: UUID,
        hardwareId: String,
        ip: String,
        success: HuePairSuccess,
        fingerprint: String?,
    ): BridgeRow {
        val now = System.currentTimeMillis()
        val owner = EntityID(userId, UserTable)
        val id = dbQuery {
            val existing = HueBridgeTable.selectAll()
                .where { HueBridgeTable.userId eq userId }
                .andWhere { HueBridgeTable.bridgeId eq hardwareId }
                .singleOrNull()
            if (existing != null) {
                val id = existing[HueBridgeTable.id].value
                HueBridgeTable.update({ HueBridgeTable.id eq id }) {
                    it[HueBridgeTable.ip] = ip
                    it[applicationKey] = success.username
                    it[clientKey] = success.clientkey
                    it[certFingerprint] = fingerprint
                    it[lastSeen] = now
                    it[lastError] = null
                }
                id
            } else {
                HueBridgeTable.insertAndGetId {
                    it[bridgeId] = hardwareId
                    it[HueBridgeTable.ip] = ip
                    it[name] = "Hue Bridge ${hardwareId.takeLast(6).uppercase()}"
                    it[applicationKey] = success.username
                    it[clientKey] = success.clientkey
                    it[certFingerprint] = fingerprint
                    it[HueBridgeTable.userId] = owner
                    it[createdAt] = now
                    it[lastSeen] = now
                }.value
            }
        }
        return bridge(userId, id) ?: throw IllegalStateException("Bridge $id vanished")
    }

    suspend fun removeBridge(userId: UUID, id: UUID): Boolean {
        val owner = userId
        val removed = dbQuery {
            HueBridgeTable.deleteWhere { (HueBridgeTable.id eq id) and (HueBridgeTable.userId eq owner) } > 0
        }
        if (!removed) return false
        stopStream(userId, id)
        animations.keys.filter { it.second == id }.forEach { key -> animations.remove(key)?.cancel() }
        runtimes.remove(id)?.let { it.queue.close(); it.client.close() }
        targetsCache.remove(id)
        scenesCache.remove(id)
        catalogs.remove(id)
        areaCache.remove(id)
        changeFlow.tryEmit(Unit)
        return true
    }

    suspend fun listTargets(userId: UUID, bridgeId: UUID, force: Boolean = false): List<HueTarget> {
        val now = System.currentTimeMillis()
        targetsCache[bridgeId]?.let { (at, targets) -> if (!force && now - at < TARGETS_TTL.inWholeMilliseconds) return targets }
        val row = bridge(userId, bridgeId) ?: throw IllegalArgumentException("Unknown bridge $bridgeId")
        val client = runtime(row).client
        val targets = try {
            val lights = client.lights()
            val rooms = client.rooms()
            val zones = client.zones()
            val configurations = client.entertainmentConfigurations()
            val services = client.entertainmentServices()
            val colorLights = lights.filter { it.color != null }
            catalogs[bridgeId] = buildCatalog(colorLights, rooms, zones)
            val areas = HueEntertainmentMap.build(configurations, services, lights)
            areaCache[bridgeId] = areas
            rooms.map { HueTarget(HueTargetType.ROOM, it.id, it.metadata?.name ?: "Room", it.groupedLightId) } +
                zones.map { HueTarget(HueTargetType.ZONE, it.id, it.metadata?.name ?: "Zone", it.groupedLightId) } +
                colorLights.map { HueTarget(HueTargetType.LIGHT, it.id, lightName(it)) } +
                areas.map { HueTarget(HueTargetType.ENTERTAINMENT, it.id, it.name) }
        } catch (e: Exception) {
            recordError(row.id, e)
            throw e
        }
        markSeen(row.id)
        targetsCache[bridgeId] = now to targets
        return targets
    }

    private fun buildCatalog(colorLights: List<ClipLight>, rooms: List<ClipGroup>, zones: List<ClipGroup>): BridgeCatalog {
        val byDevice = colorLights.groupBy { it.owner?.rid }
        val byId = colorLights.associateBy { it.id }
        val profiles = colorLights.associate { light ->
            val gamut = light.color?.gamut
            val red = gamut?.red
            val green = gamut?.green
            val blue = gamut?.blue
            val mapped = if (red == null || green == null || blue == null) null
            else HueColor.Gamut(HueColor.Xy(red.x, red.y), HueColor.Xy(green.x, green.y), HueColor.Xy(blue.x, blue.y))
            light.id to LightProfile(mapped, light.gradient?.pointsCapable)
        }
        val names = colorLights.associate { it.id to lightName(it) }
        val members = rooms.associate { room ->
            room.id to room.children.filter { it.rtype == "device" }
                .flatMap { child -> byDevice[child.rid].orEmpty() }
                .sortedBy { lightName(it) }
                .map { it.id }
        } + zones.associate { zone ->
            zone.id to zone.children.filter { it.rtype == "light" }
                .mapNotNull { child -> byId[child.rid] }
                .sortedBy { lightName(it) }
                .map { it.id }
        }
        return BridgeCatalog(profiles, names, members)
    }

    private fun lightName(light: ClipLight): String = light.metadata?.name ?: "Light"

    internal fun expandTargets(bridgeId: UUID, targets: List<HueTarget>): List<HueTarget> {
        val catalog = catalogs[bridgeId] ?: return targets
        val expanded = ArrayList<HueTarget>(targets.size)
        targets.forEach { target ->
            val members = if (target.type == HueTargetType.ROOM || target.type == HueTargetType.ZONE) {
                catalog.members[target.id].orEmpty()
            } else {
                emptyList()
            }
            if (members.isEmpty()) expanded += target
            else members.forEach { id -> expanded += HueTarget(HueTargetType.LIGHT, id, catalog.names[id] ?: id) }
        }
        return expanded.distinctBy { it.id }
    }

    private suspend fun warmCatalog(userId: UUID, bridgeId: UUID) {
        if (catalogs.containsKey(bridgeId)) return
        runCatching { listTargets(userId, bridgeId) }
    }

    suspend fun listScenes(userId: UUID, bridgeId: UUID, force: Boolean = false): List<HueScene> {
        val now = System.currentTimeMillis()
        scenesCache[bridgeId]?.let { (at, scenes) -> if (!force && now - at < TARGETS_TTL.inWholeMilliseconds) return scenes }
        val row = bridge(userId, bridgeId) ?: throw IllegalArgumentException("Unknown bridge $bridgeId")
        val client = runtime(row).client
        val targets = listTargets(userId, bridgeId, force)
        val groups = targets.filter { it.type == HueTargetType.ROOM || it.type == HueTargetType.ZONE }.associateBy { it.id }
        val scenes = try {
            client.scenes().mapNotNull { scene ->
                val ref = scene.group ?: return@mapNotNull null
                val group = groups[ref.rid] ?: return@mapNotNull null
                val type = when (ref.rtype) {
                    "room" -> HueTargetType.ROOM
                    "zone" -> HueTargetType.ZONE
                    else -> return@mapNotNull null
                }
                HueScene(scene.id, scene.metadata?.name ?: "Scene", type, group.id, group.name)
            }.sortedWith(compareBy({ it.groupName }, { it.name }))
        } catch (e: Exception) {
            recordError(row.id, e)
            throw e
        }
        markSeen(row.id)
        scenesCache[bridgeId] = now to scenes
        return scenes
    }

    suspend fun getLinks(userId: UUID): List<HueUserLink> = dbQuery {
        HueUserLinkTable.selectAll().where { HueUserLinkTable.userId eq userId }.map(::mapLink)
    }

    suspend fun setLink(userId: UUID, requested: HueUserLink): HueUserLink {
        bridge(userId, requested.bridgeId) ?: throw IllegalArgumentException("Unknown bridge ${requested.bridgeId}")
        if (requested.enabled && requested.targets.isEmpty()) throw IllegalArgumentException("At least one target is required")
        if (requested.targets.count { it.type == HueTargetType.ENTERTAINMENT } > 1) {
            throw IllegalArgumentException("At most one entertainment area per bridge")
        }
        if (requested.enabled && requested.onStop == HueStopMode.SCENE && requested.stopScenes.isEmpty()) throw IllegalArgumentException("At least one scene is required")
        val now = System.currentTimeMillis()
        val link = requested.copy(
            latencyMs = requested.latencyMs.coerceIn(0, MAX_LATENCY_MS),
            stopScenes = requested.stopScenes.distinctBy { it.groupType to it.groupId },
        )
        val owner = EntityID(userId, UserTable)
        dbQuery {
            HueUserLinkTable.upsert(HueUserLinkTable.userId, HueUserLinkTable.bridgeId) {
                it[HueUserLinkTable.userId] = owner
                it[bridgeId] = EntityID(link.bridgeId, HueBridgeTable)
                it[enabled] = link.enabled
                it[targets] = ApplicationScope.json.encodeToString(ListSerializer(HueTarget.serializer()), link.targets)
                it[intensity] = link.intensity
                it[transitionMode] = link.transitionMode
                it[transitionMs] = link.transitionMs
                it[onStop] = link.onStop
                it[stopScenes] = ApplicationScope.json.encodeToString(ListSerializer(HueScene.serializer()), link.stopScenes)
                it[motion] = link.motion
                it[latencyMs] = link.latencyMs
                it[updatedAt] = now
            }
        }
        cancelMotion(userId, link.bridgeId)
        stopStream(userId, link.bridgeId)
        changeFlow.tryEmit(Unit)
        return link.copy(updatedAt = now)
    }

    suspend fun removeLink(userId: UUID, bridgeId: UUID): Boolean {
        cancelMotion(userId, bridgeId)
        stopStream(userId, bridgeId)
        val removed = dbQuery {
            HueUserLinkTable.deleteWhere { (HueUserLinkTable.userId eq userId) and (HueUserLinkTable.bridgeId eq bridgeId) } > 0
        }
        if (removed) changeFlow.tryEmit(Unit)
        return removed
    }

    suspend fun test(userId: UUID, bridgeId: UUID, targets: List<HueTarget>): Boolean {
        val row = bridge(userId, bridgeId) ?: throw IllegalArgumentException("Unknown bridge $bridgeId")
        if (targets.isEmpty()) return false
        warmCatalog(userId, bridgeId)
        val expanded = expandTargets(bridgeId, targets.filter { it.type != HueTargetType.ENTERTAINMENT })
        if (expanded.isEmpty()) return false
        val result = HuePaletteMapper.test(expanded, catalogs[bridgeId]?.profiles ?: emptyMap())
        runtime(row).queue.submitAll(result.commands)
        lastCommandAt[userId] = System.currentTimeMillis()
        currentColors[userId] = result.colors
        return true
    }

    suspend fun status(userId: UUID): HueStatus {
        val linkedBridges = getLinks(userId).map { it.bridgeId }.toSet()
        val error = bridges(userId).filter { it.id in linkedBridges }.firstNotNullOfOrNull { it.lastError }
        return HueStatus(lastCommandAt[userId], error, currentColors[userId] ?: emptyList())
    }

    internal suspend fun onNowPlaying(event: HookEvent.NowPlayingChanged) {
        userLocks.getOrPut(event.userId) { Mutex() }.withLock { handleNowPlaying(event) }
    }

    private suspend fun handleNowPlaying(event: HookEvent.NowPlayingChanged) {
        val previous = lastGeneration[event.userId]
        if (previous != null && event.generation < previous) return
        lastGeneration[event.userId] = event.generation

        val songId = event.songId
        val reported = PlaybackClock(event.positionMs, event.startedAt, event.playing)
        if (songId != null && lastSong[event.userId] == songId && !pendingStops.containsKey(event.userId) && clocks.containsKey(event.userId)) {
            updateClock(event.userId, reported)
            return
        }

        val links = getLinks(event.userId).filter { it.enabled && it.targets.isNotEmpty() }
        if (links.isEmpty()) return

        if (songId == null) {
            cancelMotion(event.userId)
            clocks.remove(event.userId)
            pendingStops.remove(event.userId)?.cancel()
            pendingStops[event.userId] = scope.launch {
                delay(stopGraceMs)
                pendingStops.remove(event.userId)
                lastSong.remove(event.userId)
                links.forEach { link -> stop(event.userId, link) }
            }
            return
        }
        pendingStops.remove(event.userId)?.cancel()
        cancelMotion(event.userId)

        val song = songService.byIds(listOf(songId), event.userId).firstOrNull() ?: return
        val coverId = song.coverId ?: song.album?.coverId
        val image = coverId?.let { imageService.byId(it) }
        val audio = audioAnalysisService.getAudioDataBatch(listOf(songId))[songId]
        val sources = if (motionIntervalOverride == null) links.mapNotNull { levelSource(it.motion) }.toSet() else emptySet()
        val scores = if (sources.isEmpty()) emptyMap() else timelineScores(songId, audio, song.duration, sources)

        lastSong[event.userId] = songId
        clocks[event.userId] = MutableStateFlow(reported)
        links.forEach { link ->
            val row = bridge(event.userId, link.bridgeId) ?: return@forEach
            val runtime = runtime(row)
            warmCatalog(event.userId, link.bridgeId)
            val profiles = catalogs[link.bridgeId]?.profiles ?: emptyMap()
            val energy = audio?.energy ?: SongAudioData.DEFAULT_ENERGY
            val base = HuePaletteMapper.brightness(link.intensity, energy, audio?.loudness)
            val score = lightScore(link, scores, song.duration)
            val streamArea = link.targets.firstOrNull { it.type == HueTargetType.ENTERTAINMENT }
                ?.let { area(event.userId, link.bridgeId, it.id) }
            val session = streamArea?.let { ensureStream(event.userId, row, it, base, score.levelFloor) }
            val targets = expandTargets(link.bridgeId, clipTargets(link, streamArea))
            val result = HuePaletteMapper.map(image?.palette ?: emptyList(), image?.primaryColor, audio, link, targets, profiles)
            runtime.queue.submitAll(result.commands)
            val palette = result.palette.ifEmpty {
                HuePaletteMapper.pickColors(
                    listOfNotNull(image?.primaryColor) + (image?.palette ?: emptyList()),
                    energy,
                    audio?.valence ?: SongAudioData.DEFAULT_VALENCE,
                )
            }
            var streamColors: List<Int> = emptyList()
            if (session != null) {
                val now = System.currentTimeMillis()
                session.update {
                    setBaseBrightness(base)
                    setPalette(palette, 0, now, link.transitionMs)
                    streamColors = currentColors()
                }
            }
            currentColors[event.userId] = result.colors + streamColors
            if (link.motion != HueMotionMode.OFF) {
                startMotion(event.userId, link, runtime, targets, palette, audio, score, song.duration, session)
            }
        }
        val streamed = links.filter { link -> link.targets.any { it.type == HueTargetType.ENTERTAINMENT } }.map { it.bridgeId }.toSet()
        streams.keys.filter { it.first == event.userId && it.second !in streamed }.forEach { stopStream(event.userId, it.second) }
        lastCommandAt[event.userId] = System.currentTimeMillis()
    }

    internal fun activeStreams(): Int = streams.values.count { it.isActive }

    private suspend fun area(userId: UUID, bridgeId: UUID, id: String): HueEntertainmentArea? {
        areaCache[bridgeId]?.firstOrNull { it.id == id }?.let { return it }
        runCatching { listTargets(userId, bridgeId) }
        return areaCache[bridgeId]?.firstOrNull { it.id == id }
    }

    private fun clipTargets(link: HueUserLink, area: HueEntertainmentArea?): List<HueTarget> = link.targets.filter { target ->
        when (target.type) {
            HueTargetType.ENTERTAINMENT -> false
            HueTargetType.LIGHT -> area == null || target.id !in area.lightIds
            else -> true
        }
    }

    private suspend fun ensureStream(
        userId: UUID,
        row: BridgeRow,
        area: HueEntertainmentArea,
        brightness: Int,
        floor: Double,
    ): HueEntertainmentSession? {
        val key = userId to row.id
        streams[key]?.takeIf { it.isActive && it.area.id == area.id }?.let { return it }
        stopStream(userId, row.id)
        val client = runtime(row).client
        val configuration = runCatching { client.entertainmentConfigurations().firstOrNull { it.id == area.id } }.getOrNull()
        if (configuration?.active == true) {
            recordError(row.id, HueBridgeException("Entertainment area ${area.name} is already streamed by another app"))
            return null
        }
        var created: HueEntertainmentStream? = null
        val stream = try {
            client.setEntertainmentStreaming(area.id, true)
            val opened = streamFactory(row, area)
            created = opened
            opened.start()
            opened
        } catch (e: Exception) {
            runCatching { created?.close() }
            runCatching { client.setEntertainmentStreaming(area.id, false) }
            recordError(row.id, e)
            return null
        }
        val renderer = HueEntertainmentRenderer(area.orderedChannelIds, floor, brightness)
        val session = HueEntertainmentSession(
            area = area,
            stream = stream,
            renderer = renderer,
            onError = { error ->
                scope.launch {
                    recordError(row.id, error)
                    stopStream(userId, row.id)
                }
            },
        )
        session.launch(scope)
        streams[key] = session
        markSeen(row.id)
        return session
    }

    private suspend fun stopStream(userId: UUID, bridgeId: UUID? = null) {
        val keys = streams.keys.filter { it.first == userId && (bridgeId == null || it.second == bridgeId) }
        keys.forEach { key ->
            val session = streams.remove(key) ?: return@forEach
            session.close()
            val client = runtimes[key.second]?.client ?: return@forEach
            runCatching { client.setEntertainmentStreaming(session.area.id, false) }
        }
    }

    private fun expandForStop(link: HueUserLink): HueUserLink {
        if (link.targets.none { it.type == HueTargetType.ENTERTAINMENT }) return link
        val areas = areaCache[link.bridgeId].orEmpty()
        val names = catalogs[link.bridgeId]?.names.orEmpty()
        val targets = link.targets.flatMap { target ->
            if (target.type != HueTargetType.ENTERTAINMENT) listOf(target)
            else areas.firstOrNull { it.id == target.id }?.lightIds.orEmpty()
                .sortedBy { names[it] ?: it }
                .map { id -> HueTarget(HueTargetType.LIGHT, id, names[id] ?: id) }
        }
        return link.copy(targets = targets.distinctBy { it.type to it.id })
    }

    private fun updateClock(userId: UUID, reported: PlaybackClock) {
        val flow = clocks[userId] ?: return
        val current = flow.value
        val now = System.currentTimeMillis()
        val drift = abs(current.positionAt(now) - reported.positionAt(now))
        if (current.playing == reported.playing && drift < CLOCK_TOLERANCE_MS) return
        flow.value = reported
    }

    private suspend fun timelineScores(
        songId: UUID,
        audio: SongAudioData?,
        durationMs: Long,
        sources: Set<LevelSource>,
    ): Map<LevelSource, LightScore> {
        val cached = sources.mapNotNull { source -> scoreCache.getIfPresent(songId to source)?.let { source to it } }.toMap()
        val missing = sources - cached.keys
        if (missing.isEmpty()) return cached
        val timeline = runCatching { audioAnalysisService.getAudioTimeline(songId) }.getOrNull()
        val built = missing.associateWith { source ->
            val score = HueLightScore.build(timeline, audio?.bpm, durationMs, HuePaletteMapper.barMs(audio?.bpm), source)
            if (timeline != null) scoreCache.put(songId to source, score)
            score
        }
        return cached + built
    }

    private fun levelSource(motion: HueMotionMode): LevelSource? = when (motion) {
        HueMotionMode.TEMPO -> LevelSource.LOUDNESS
        HueMotionMode.BASS -> LevelSource.BASS
        HueMotionMode.OFF, HueMotionMode.SLOW -> null
    }

    private fun lightScore(link: HueUserLink, scores: Map<LevelSource, LightScore>, durationMs: Long): LightScore {
        val override = motionIntervalOverride
        if (override != null) return HueLightScore.build(null, null, durationMs, override)
        return levelSource(link.motion)?.let { scores[it] } ?: HueLightScore.build(null, null, durationMs, SLOW_MOTION_INTERVAL)
    }

    private fun beatDivisor(targets: List<HueTarget>, score: LightScore): Int? {
        val lights = targets.count { it.type == HueTargetType.LIGHT }
        if (lights == 0) return null
        val load = lights * score.beatsPerSecond
        return when {
            load <= MAX_LIGHT_COMMANDS_PER_SECOND -> 1
            load / 2 <= MAX_LIGHT_COMMANDS_PER_SECOND -> 2
            else -> null
        }
    }

    private fun cadence(targets: List<HueTarget>, score: LightScore): (Keyframe) -> Boolean {
        val divisor = beatDivisor(targets, score)
        return { keyframe ->
            when (keyframe.kind) {
                KeyframeKind.DOWNBEAT, KeyframeKind.SECTION -> true
                KeyframeKind.BEAT -> divisor != null && (keyframe.index - score.downbeatPhase).mod(divisor) == 0
            }
        }
    }

    private fun startMotion(
        userId: UUID,
        link: HueUserLink,
        runtime: BridgeRuntime,
        targets: List<HueTarget>,
        palette: List<Int>,
        audio: SongAudioData?,
        score: LightScore,
        durationMs: Long,
        session: HueEntertainmentSession?,
    ) {
        val key = userId to link.bridgeId
        animations.remove(key)?.cancel()
        if (palette.isEmpty()) return
        val clock = clocks[userId] ?: return
        val base = HuePaletteMapper.brightness(link.intensity, audio?.energy ?: SongAudioData.DEFAULT_ENERGY, audio?.loudness)
        val profiles = catalogs[link.bridgeId]?.profiles ?: emptyMap()
        val lightTargets = targets.filter { it.type == HueTargetType.LIGHT }
        val clipCadence = cadence(targets, score)
        val everyKeyframe: (Keyframe) -> Boolean = { true }
        val schedulerCadence = if (session == null) clipCadence else everyKeyframe
        val beatMs = score.beatMs
        animations[key] = scope.launch {
            var step = 0
            HueMotionScheduler(clock, score, durationMs, link.latencyMs, schedulerCadence) { keyframe, nextAtMs ->
                step += when (keyframe.kind) {
                    KeyframeKind.SECTION -> 2
                    KeyframeKind.DOWNBEAT -> 1
                    KeyframeKind.BEAT -> 0
                }
                val brightness = (base * HuePaletteMapper.levelFactor(keyframe.level, score.levelFloor)).roundToInt().coerceIn(1, 100)
                val beat = keyframe.kind == KeyframeKind.BEAT
                val cap = if (beat) MAX_BEAT_TRANSITION_MS else MAX_MOTION_TRANSITION_MS
                val transition = ((nextAtMs ?: (keyframe.atMs + cap)) - keyframe.atMs).coerceIn(0, cap)
                var streamColors: List<Int> = emptyList()
                if (session != null) {
                    val now = System.currentTimeMillis()
                    val fade = if (beatMs == null) transition else STREAM_ROTATE_FADE_MS
                    session.update {
                        if (!beat) setPalette(palette, step, now, fade)
                        if (beatMs != null) {
                            pulse(
                                keyframe.level,
                                now,
                                beatMs.coerceIn(HueEntertainmentRenderer.MIN_DECAY_MS, HueEntertainmentRenderer.MAX_DECAY_MS),
                            )
                        }
                        streamColors = currentColors()
                    }
                }
                if (clipCadence(keyframe)) {
                    val frame = HuePaletteMapper.frame(palette, if (beat) lightTargets else targets, step, brightness, transition, profiles)
                    runtime.queue.submitAll(frame.commands)
                    if (!beat) currentColors[userId] = frame.colors + streamColors
                    lastCommandAt[userId] = System.currentTimeMillis()
                }
            }.run()
        }
    }

    private fun cancelMotion(userId: UUID, bridgeId: UUID? = null) {
        animations.keys.filter { it.first == userId && (bridgeId == null || it.second == bridgeId) }.forEach { key ->
            animations.remove(key)?.cancel()
        }
    }

    internal fun activeMotions(): Int = animations.values.count { it.isActive }

    private suspend fun stop(userId: UUID, link: HueUserLink) {
        val row = bridge(userId, link.bridgeId) ?: return
        stopStream(userId, link.bridgeId)
        val runtime = runtime(row)
        val commands = HuePaletteMapper.stop(expandForStop(link))
        if (commands.isNotEmpty()) {
            runtime.queue.submitAll(commands)
            lastCommandAt[userId] = System.currentTimeMillis()
        }
    }

    private fun runtime(row: BridgeRow): BridgeRuntime = runtimes.getOrPut(row.id) {
        val client = clientFactory(row)
        val queue = HueCommandQueue(
            api = client,
            scope = scope,
            onSent = { scope.launch { markSeen(row.id) } },
            onError = { scope.launch { recordError(row.id, it) } },
        )
        BridgeRuntime(client, queue)
    }

    private suspend fun markSeen(id: UUID) = dbQuery {
        HueBridgeTable.update({ HueBridgeTable.id eq id }) {
            it[lastSeen] = System.currentTimeMillis()
            it[lastError] = null
        }
    }

    private suspend fun recordError(id: UUID, error: Throwable) {
        logger.warn("Hue bridge $id: ${error.message}")
        dbQuery { HueBridgeTable.update({ HueBridgeTable.id eq id }) { it[lastError] = error.message?.take(500) ?: error::class.simpleName } }
    }

    private fun deviceType(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("server")
            .replace(Regex("[^A-Za-z0-9_-]"), "").take(19).ifEmpty { "server" }
        return "synara#$host"
    }

    private fun mapBridge(row: ResultRow) = BridgeRow(
        id = row[HueBridgeTable.id].value,
        userId = row[HueBridgeTable.userId]!!.value,
        bridgeId = row[HueBridgeTable.bridgeId],
        ip = row[HueBridgeTable.ip],
        name = row[HueBridgeTable.name],
        modelId = row[HueBridgeTable.modelId],
        applicationKey = row[HueBridgeTable.applicationKey],
        clientKey = row[HueBridgeTable.clientKey],
        certFingerprint = row[HueBridgeTable.certFingerprint],
        lastSeen = row[HueBridgeTable.lastSeen],
        lastError = row[HueBridgeTable.lastError],
    )

    private fun mapLink(row: ResultRow) = HueUserLink(
        bridgeId = row[HueUserLinkTable.bridgeId].value,
        enabled = row[HueUserLinkTable.enabled],
        targets = runCatching { ApplicationScope.json.decodeFromString(ListSerializer(HueTarget.serializer()), row[HueUserLinkTable.targets]) }.getOrDefault(emptyList()),
        intensity = row[HueUserLinkTable.intensity],
        transitionMode = row[HueUserLinkTable.transitionMode],
        transitionMs = row[HueUserLinkTable.transitionMs],
        onStop = runCatching { row[HueUserLinkTable.onStop] }.getOrDefault(HueStopMode.KEEP),
        motion = row[HueUserLinkTable.motion],
        updatedAt = row[HueUserLinkTable.updatedAt],
        latencyMs = row[HueUserLinkTable.latencyMs],
        stopScenes = runCatching { ApplicationScope.json.decodeFromString(ListSerializer(HueScene.serializer()), row[HueUserLinkTable.stopScenes]) }.getOrDefault(emptyList()),
    )

    companion object {
        private val PAIRING_TIMEOUT = 30.seconds
        private val PAIRING_POLL = 2.seconds
        private const val SLOW_MOTION_INTERVAL = 8_000L
        private const val CLOCK_TOLERANCE_MS = 250L
        private const val MAX_LIGHT_COMMANDS_PER_SECOND = 8.0
        private const val MAX_BEAT_TRANSITION_MS = 1_500
        private const val MAX_MOTION_TRANSITION_MS = 10_000
        private const val STREAM_ROTATE_FADE_MS = 300
        private const val STREAM_SHUTDOWN_TIMEOUT_MS = 2_000L
        const val MAX_LATENCY_MS = 1_000
        private val STOP_GRACE = 3.seconds
        private val TARGETS_TTL = 5.minutes
    }
}
