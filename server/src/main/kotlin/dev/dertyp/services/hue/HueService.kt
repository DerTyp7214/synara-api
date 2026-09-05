package dev.dertyp.services.hue

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.data.HueBridgeInfo
import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HueMotionMode
import dev.dertyp.data.HuePairingState
import dev.dertyp.data.HuePairingStatus
import dev.dertyp.data.HueStatus
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.SongAudioData
import dev.dertyp.db.HueBridgeTable
import dev.dertyp.db.HueUserLinkTable
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
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

    class PairingSession(val ip: String, val state: MutableStateFlow<HuePairingStatus>, var job: Job? = null)

    private class BridgeRuntime(val client: HueBridgeApi, val queue: HueCommandQueue)

    internal var clientFactory: (BridgeRow) -> HueBridgeApi = { row ->
        HueBridgeClient(row.ip, row.bridgeId, row.applicationKey, row.certFingerprint) { fingerprint ->
            scope.launch { dbQuery { HueBridgeTable.update({ HueBridgeTable.id eq row.id }) { it[certFingerprint] = fingerprint } } }
        }
    }

    private val runtimes = ConcurrentHashMap<UUID, BridgeRuntime>()
    private val pairings = ConcurrentHashMap<String, PairingSession>()
    private val lastGeneration = ConcurrentHashMap<UUID, Long>()
    private val lastSong = ConcurrentHashMap<UUID, UUID>()
    private val lastCommandAt = ConcurrentHashMap<UUID, Long>()
    private val currentColors = ConcurrentHashMap<UUID, List<Int>>()
    private val snapshots = ConcurrentHashMap<Pair<UUID, UUID>, List<HueCommand>>()
    private val animations = ConcurrentHashMap<Pair<UUID, UUID>, Job>()
    private val pendingStops = ConcurrentHashMap<UUID, Job>()
    private val clocks = ConcurrentHashMap<UUID, MutableStateFlow<PlaybackClock>>()
    private val userLocks = ConcurrentHashMap<UUID, Mutex>()
    private val scoreCache: Cache<UUID, LightScore> = Caffeine.newBuilder().maximumSize(256).build()

    internal var motionIntervalOverride: Long? = null
    internal var stopGraceMs: Long = STOP_GRACE.inWholeMilliseconds
    private val targetsCache = ConcurrentHashMap<UUID, Pair<Long, List<HueTarget>>>()
    private val gamutCache = ConcurrentHashMap<UUID, Map<String, HueColor.Gamut>>()

    private val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = changeFlow.asSharedFlow()

    override suspend fun startService() {
        hooks.on<HookEvent.NowPlayingChanged> { onNowPlaying(it) }
    }

    override suspend fun stopService() {
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

    suspend fun discover(force: Boolean = false): List<HueBridgeCandidate> {
        val paired = bridges()
        return discoveryService.discover(force = force).map { candidate ->
            candidate.copy(paired = paired.any { it.ip == candidate.ip || (candidate.bridgeId != null && it.bridgeId.equals(candidate.bridgeId, true)) })
        }
    }

    fun cachedDiscovery(): List<HueBridgeCandidate> = discoveryService.cached()

    suspend fun bridges(): List<BridgeRow> = dbQuery { HueBridgeTable.selectAll().orderBy(HueBridgeTable.name).map(::mapBridge) }

    suspend fun bridge(id: UUID): BridgeRow? = dbQuery { HueBridgeTable.selectAll().where { HueBridgeTable.id eq id }.singleOrNull()?.let(::mapBridge) }

    suspend fun listBridges(): List<HueBridgeInfo> = bridges().map { it.info() }

    fun pairingSession(ip: String): PairingSession? = pairings[ip]?.takeIf { it.job?.isActive == true }

    fun activePairings(): List<PairingSession> = pairings.values.filter { it.job?.isActive == true }

    fun beginPairing(userId: UUID?, ip: String): PairingSession {
        val normalized = ip.trim()
        require(normalized.isNotEmpty()) { "Bridge IP is required" }
        pairings[normalized]?.takeIf { it.job?.isActive == true }?.let { return it }
        val session = PairingSession(normalized, MutableStateFlow(HuePairingStatus(HuePairingState.CONNECTING)))
        session.job = scope.launch { runPairing(userId, session) }
        pairings[normalized] = session
        return session
    }

    fun startPairing(userId: UUID?, ip: String): Flow<HuePairingStatus> {
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

    private suspend fun runPairing(userId: UUID?, session: PairingSession) {
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
                val row = upsertBridge(hardwareId, session.ip, success, fingerprint, userId)
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

    private suspend fun upsertBridge(hardwareId: String, ip: String, success: HuePairSuccess, fingerprint: String?, userId: UUID?): BridgeRow {
        val now = System.currentTimeMillis()
        val id = dbQuery {
            val existing = HueBridgeTable.selectAll().where { HueBridgeTable.bridgeId eq hardwareId }.singleOrNull()
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
                    it[createdBy] = userId?.let { u -> EntityID(u, dev.dertyp.db.UserTable) }
                    it[createdAt] = now
                    it[lastSeen] = now
                }.value
            }
        }
        return bridge(id) ?: throw IllegalStateException("Bridge $id vanished")
    }

    suspend fun removeBridge(id: UUID): Boolean {
        animations.keys.filter { it.second == id }.forEach { key -> animations.remove(key)?.cancel() }
        runtimes.remove(id)?.let { it.queue.close(); it.client.close() }
        targetsCache.remove(id)
        val removed = dbQuery { HueBridgeTable.deleteWhere { HueBridgeTable.id eq id } > 0 }
        if (removed) changeFlow.tryEmit(Unit)
        return removed
    }

    suspend fun listTargets(bridgeId: UUID, force: Boolean = false): List<HueTarget> {
        val now = System.currentTimeMillis()
        targetsCache[bridgeId]?.let { (at, targets) -> if (!force && now - at < TARGETS_TTL.inWholeMilliseconds) return targets }
        val row = bridge(bridgeId) ?: throw IllegalArgumentException("Unknown bridge $bridgeId")
        val client = runtime(row).client
        val targets = try {
            val lights = client.lights()
            val rooms = client.rooms()
            val zones = client.zones()
            gamutCache[bridgeId] = lights.mapNotNull { light ->
                val gamut = light.color?.gamut ?: return@mapNotNull null
                val (r, g, b) = Triple(gamut.red, gamut.green, gamut.blue)
                if (r == null || g == null || b == null) null
                else light.id to HueColor.Gamut(HueColor.Xy(r.x, r.y), HueColor.Xy(g.x, g.y), HueColor.Xy(b.x, b.y))
            }.toMap()
            rooms.map { HueTarget(HueTargetType.ROOM, it.id, it.metadata?.name ?: "Room", it.groupedLightId) } +
                zones.map { HueTarget(HueTargetType.ZONE, it.id, it.metadata?.name ?: "Zone", it.groupedLightId) } +
                lights.filter { it.color != null }.map { HueTarget(HueTargetType.LIGHT, it.id, it.metadata?.name ?: "Light") }
        } catch (e: Exception) {
            recordError(row.id, e)
            throw e
        }
        markSeen(row.id)
        targetsCache[bridgeId] = now to targets
        return targets
    }

    suspend fun getLinks(userId: UUID): List<HueUserLink> = dbQuery {
        HueUserLinkTable.selectAll().where { HueUserLinkTable.userId eq userId }.map(::mapLink)
    }

    suspend fun setLink(userId: UUID, requested: HueUserLink): HueUserLink {
        bridge(requested.bridgeId) ?: throw IllegalArgumentException("Unknown bridge ${requested.bridgeId}")
        if (requested.enabled && requested.targets.isEmpty()) throw IllegalArgumentException("At least one target is required")
        val now = System.currentTimeMillis()
        val link = requested.copy(latencyMs = requested.latencyMs.coerceIn(0, MAX_LATENCY_MS))
        dbQuery {
            HueUserLinkTable.upsert(HueUserLinkTable.userId, HueUserLinkTable.bridgeId) {
                it[HueUserLinkTable.userId] = EntityID(userId, dev.dertyp.db.UserTable)
                it[bridgeId] = EntityID(link.bridgeId, HueBridgeTable)
                it[enabled] = link.enabled
                it[targets] = ApplicationScope.json.encodeToString(ListSerializer(HueTarget.serializer()), link.targets)
                it[intensity] = link.intensity
                it[transitionMode] = link.transitionMode
                it[transitionMs] = link.transitionMs
                it[onStop] = link.onStop
                it[motion] = link.motion
                it[latencyMs] = link.latencyMs
                it[updatedAt] = now
            }
        }
        cancelMotion(userId, link.bridgeId)
        changeFlow.tryEmit(Unit)
        return link.copy(updatedAt = now)
    }

    suspend fun removeLink(userId: UUID, bridgeId: UUID): Boolean {
        cancelMotion(userId, bridgeId)
        val removed = dbQuery {
            HueUserLinkTable.deleteWhere { (HueUserLinkTable.userId eq userId) and (HueUserLinkTable.bridgeId eq bridgeId) } > 0
        }
        if (removed) changeFlow.tryEmit(Unit)
        return removed
    }

    suspend fun test(userId: UUID, bridgeId: UUID, targets: List<HueTarget>): Boolean {
        val row = bridge(bridgeId) ?: throw IllegalArgumentException("Unknown bridge $bridgeId")
        if (targets.isEmpty()) return false
        val result = HuePaletteMapper.test(targets, gamutCache[bridgeId] ?: emptyMap())
        runtime(row).queue.submitAll(result.commands)
        lastCommandAt[userId] = System.currentTimeMillis()
        currentColors[userId] = result.colors
        return true
    }

    suspend fun status(userId: UUID): HueStatus {
        val linkedBridges = getLinks(userId).map { it.bridgeId }.toSet()
        val error = bridges().filter { it.id in linkedBridges }.firstNotNullOfOrNull { it.lastError }
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
        val tempo = if (motionIntervalOverride == null && links.any { it.motion == HueMotionMode.TEMPO }) {
            tempoScore(songId, audio, song.duration)
        } else null

        lastSong[event.userId] = songId
        clocks[event.userId] = MutableStateFlow(reported)
        links.forEach { link ->
            val row = bridge(link.bridgeId) ?: return@forEach
            val runtime = runtime(row)
            if (link.onStop == HueStopMode.RESTORE && !snapshots.containsKey(event.userId to link.bridgeId)) {
                snapshot(event.userId, link, runtime.client)
            }
            val result = HuePaletteMapper.map(image?.palette ?: emptyList(), image?.primaryColor, audio, link, gamutCache[link.bridgeId] ?: emptyMap())
            runtime.queue.submitAll(result.commands)
            currentColors[event.userId] = result.colors
            if (link.motion != HueMotionMode.OFF) {
                startMotion(event.userId, link, runtime, result.palette, audio, lightScore(link, tempo, song.duration), song.duration)
            }
        }
        lastCommandAt[event.userId] = System.currentTimeMillis()
    }

    private fun updateClock(userId: UUID, reported: PlaybackClock) {
        val flow = clocks[userId] ?: return
        val current = flow.value
        val now = System.currentTimeMillis()
        val drift = abs(current.positionAt(now) - reported.positionAt(now))
        if (current.playing == reported.playing && drift < CLOCK_TOLERANCE_MS) return
        flow.value = reported
    }

    private suspend fun tempoScore(songId: UUID, audio: SongAudioData?, durationMs: Long): LightScore {
        scoreCache.getIfPresent(songId)?.let { return it }
        val timeline = runCatching { audioAnalysisService.getAudioTimeline(songId) }.getOrNull()
        val score = HueLightScore.build(timeline, audio?.bpm, durationMs, HuePaletteMapper.barMs(audio?.bpm))
        if (timeline != null) scoreCache.put(songId, score)
        return score
    }

    private fun lightScore(link: HueUserLink, tempo: LightScore?, durationMs: Long): LightScore {
        val override = motionIntervalOverride
        if (override != null) return HueLightScore.build(null, null, durationMs, override)
        return tempo?.takeIf { link.motion == HueMotionMode.TEMPO } ?: HueLightScore.build(null, null, durationMs, SLOW_MOTION_INTERVAL)
    }

    private fun beatDivisor(link: HueUserLink, score: LightScore): Int? {
        val lights = link.targets.count { it.type == HueTargetType.LIGHT }
        if (lights == 0) return null
        val load = lights * score.beatsPerSecond
        return when {
            load <= MAX_LIGHT_COMMANDS_PER_SECOND -> 1
            load / 2 <= MAX_LIGHT_COMMANDS_PER_SECOND -> 2
            else -> null
        }
    }

    private fun cadence(link: HueUserLink, score: LightScore): (Keyframe) -> Boolean {
        val divisor = beatDivisor(link, score)
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
        palette: List<Int>,
        audio: SongAudioData?,
        score: LightScore,
        durationMs: Long,
    ) {
        val key = userId to link.bridgeId
        animations.remove(key)?.cancel()
        if (palette.isEmpty()) return
        val clock = clocks[userId] ?: return
        val base = HuePaletteMapper.brightness(link.intensity, audio?.energy ?: SongAudioData.DEFAULT_ENERGY, audio?.loudness)
        val gamuts = gamutCache[link.bridgeId] ?: emptyMap()
        val lightTargets = link.targets.filter { it.type == HueTargetType.LIGHT }
        animations[key] = scope.launch {
            var step = 0
            HueMotionScheduler(clock, score, durationMs, link.latencyMs, cadence(link, score)) { keyframe, nextAtMs ->
                step += when (keyframe.kind) {
                    KeyframeKind.SECTION -> 2
                    KeyframeKind.DOWNBEAT -> 1
                    KeyframeKind.BEAT -> 0
                }
                val brightness = (base * HuePaletteMapper.levelFactor(keyframe.level)).roundToInt().coerceIn(1, 100)
                val beat = keyframe.kind == KeyframeKind.BEAT
                val cap = if (beat) MAX_BEAT_TRANSITION_MS else MAX_MOTION_TRANSITION_MS
                val transition = ((nextAtMs ?: (keyframe.atMs + cap)) - keyframe.atMs).coerceIn(0, cap)
                val frame = HuePaletteMapper.frame(palette, if (beat) lightTargets else link.targets, step, brightness, transition, gamuts)
                runtime.queue.submitAll(frame.commands)
                if (!beat) currentColors[userId] = frame.colors
                lastCommandAt[userId] = System.currentTimeMillis()
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
        val row = bridge(link.bridgeId) ?: return
        val runtime = runtime(row)
        val commands = when (link.onStop) {
            HueStopMode.RESTORE -> snapshots.remove(userId to link.bridgeId) ?: emptyList()
            else -> HuePaletteMapper.stop(link)
        }
        if (commands.isNotEmpty()) {
            runtime.queue.submitAll(commands)
            lastCommandAt[userId] = System.currentTimeMillis()
        }
    }

    private suspend fun snapshot(userId: UUID, link: HueUserLink, client: HueBridgeApi) {
        val commands = runCatching {
            val lights = client.lights().associateBy { it.id }
            link.targets.filter { it.type == HueTargetType.LIGHT }.mapNotNull { target ->
                val light = lights[target.id] ?: return@mapNotNull null
                HueCommand(target, restoreUpdate(light, link.transitionMs))
            }
        }.onFailure { logger.warn("Could not snapshot Hue lights: ${it.message}") }.getOrDefault(emptyList())
        if (commands.isNotEmpty()) snapshots[userId to link.bridgeId] = commands
    }

    internal fun restoreUpdate(light: ClipLight, transitionMs: Int): LightUpdate {
        val on = light.on?.on ?: true
        if (!on) return LightUpdate(on = ClipOn(false))
        val temperature = light.colorTemperature?.takeIf { it.mirekValid == true }?.mirek
        return LightUpdate(
            on = ClipOn(true),
            dimming = light.dimming,
            color = if (temperature == null) light.color?.xy?.let { ClipColorUpdate(it) } else null,
            colorTemperature = temperature?.let { ClipColorTemperatureUpdate(it) },
            dynamics = ClipDynamics(transitionMs),
        )
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
        onStop = row[HueUserLinkTable.onStop],
        motion = row[HueUserLinkTable.motion],
        updatedAt = row[HueUserLinkTable.updatedAt],
        latencyMs = row[HueUserLinkTable.latencyMs],
    )

    companion object {
        private val PAIRING_TIMEOUT = 30.seconds
        private val PAIRING_POLL = 2.seconds
        private const val SLOW_MOTION_INTERVAL = 8_000L
        private const val CLOCK_TOLERANCE_MS = 250L
        private const val MAX_LIGHT_COMMANDS_PER_SECOND = 8.0
        private const val MAX_BEAT_TRANSITION_MS = 1_500
        private const val MAX_MOTION_TRANSITION_MS = 10_000
        const val MAX_LATENCY_MS = 1_000
        private val STOP_GRACE = 3.seconds
        private val TARGETS_TTL = 5.minutes
    }
}
