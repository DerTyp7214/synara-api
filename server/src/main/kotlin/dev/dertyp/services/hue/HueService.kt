package dev.dertyp.services.hue

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.data.HueBridgeInfo
import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HuePairingState
import dev.dertyp.data.HuePairingStatus
import dev.dertyp.data.HueStatus
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
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
import kotlinx.coroutines.launch
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
    private val targetsCache = ConcurrentHashMap<UUID, Pair<Long, List<HueTarget>>>()
    private val gamutCache = ConcurrentHashMap<UUID, Map<String, HueColor.Gamut>>()

    private val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = changeFlow.asSharedFlow()

    override suspend fun startService() {
        hooks.on<HookEvent.NowPlayingChanged> { onNowPlaying(it) }
    }

    override suspend fun stopService() {
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

    suspend fun setLink(userId: UUID, link: HueUserLink): HueUserLink {
        bridge(link.bridgeId) ?: throw IllegalArgumentException("Unknown bridge ${link.bridgeId}")
        if (link.enabled && link.targets.isEmpty()) throw IllegalArgumentException("At least one target is required")
        val now = System.currentTimeMillis()
        dbQuery {
            HueUserLinkTable.upsert(HueUserLinkTable.userId, HueUserLinkTable.bridgeId) {
                it[HueUserLinkTable.userId] = EntityID(userId, dev.dertyp.db.UserTable)
                it[bridgeId] = EntityID(link.bridgeId, HueBridgeTable)
                it[enabled] = link.enabled
                it[targets] = ApplicationScope.json.encodeToString(ListSerializer(HueTarget.serializer()), link.targets)
                it[intensity] = link.intensity.name
                it[transitionMode] = link.transitionMode.name
                it[transitionMs] = link.transitionMs
                it[onStop] = link.onStop.name
                it[updatedAt] = now
            }
        }
        changeFlow.tryEmit(Unit)
        return link.copy(updatedAt = now)
    }

    suspend fun removeLink(userId: UUID, bridgeId: UUID): Boolean {
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
        val previous = lastGeneration[event.userId]
        if (previous != null && event.generation < previous) return
        lastGeneration[event.userId] = event.generation

        val links = getLinks(event.userId).filter { it.enabled && it.targets.isNotEmpty() }
        if (links.isEmpty()) return

        val songId = event.songId
        if (songId == null) {
            lastSong.remove(event.userId)
            links.forEach { link -> stop(event.userId, link) }
            return
        }
        if (lastSong[event.userId] == songId) return

        val song = songService.byIds(listOf(songId), event.userId).firstOrNull() ?: return
        val coverId = song.coverId ?: song.album?.coverId
        val image = coverId?.let { imageService.byId(it) }
        val audio = audioAnalysisService.getAudioDataBatch(listOf(songId))[songId]

        val firstSong = lastSong.put(event.userId, songId) == null
        links.forEach { link ->
            val row = bridge(link.bridgeId) ?: return@forEach
            val runtime = runtime(row)
            if (firstSong && link.onStop == HueStopMode.RESTORE) snapshot(event.userId, link, runtime.client)
            val result = HuePaletteMapper.map(image?.palette ?: emptyList(), image?.primaryColor, audio, link, gamutCache[link.bridgeId] ?: emptyMap())
            runtime.queue.submitAll(result.commands)
            currentColors[event.userId] = result.colors
        }
        lastCommandAt[event.userId] = System.currentTimeMillis()
    }

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
                HueCommand(
                    target,
                    LightUpdate(
                        on = light.on,
                        dimming = light.dimming,
                        color = light.color?.xy?.let { ClipColorUpdate(it) },
                        dynamics = ClipDynamics(link.transitionMs),
                    ),
                )
            }
        }.onFailure { logger.warn("Could not snapshot Hue lights: ${it.message}") }.getOrDefault(emptyList())
        if (commands.isNotEmpty()) snapshots[userId to link.bridgeId] = commands
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
        intensity = enumOr(row[HueUserLinkTable.intensity], HueIntensity.MEDIUM),
        transitionMode = enumOr(row[HueUserLinkTable.transitionMode], HueTransitionMode.FIXED),
        transitionMs = row[HueUserLinkTable.transitionMs],
        onStop = enumOr(row[HueUserLinkTable.onStop], HueStopMode.KEEP),
        updatedAt = row[HueUserLinkTable.updatedAt],
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        private val PAIRING_TIMEOUT = 30.seconds
        private val PAIRING_POLL = 2.seconds
        private val TARGETS_TTL = 5.minutes
    }
}
