package dev.dertyp.services.lyrics

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.cleanTitle
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.fullName
import dev.dertyp.data.EntityType
import dev.dertyp.data.SongLyricsInfo
import dev.dertyp.db.LyricsCandidateStatus
import dev.dertyp.db.LyricsCandidateTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncedLyricsTable
import dev.dertyp.serializers.AppCbor
import dev.dertyp.services.EntityEventPublisher
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.services.lyrics.parse.*
import dev.dertyp.services.lyrics.providers.*
import dev.dertyp.services.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.Date
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
class LyricsCandidateService : Service() {
    private val songService by inject<SongService>()
    private val registry by inject<LyricsProviderRegistry>()
    private val sidecar by inject<SidecarLyricsProvider>()
    private val entityEvents by inject<EntityEventPublisher>()

    private class Entry(
        val id: UUID,
        val songId: UUID?,
        val provider: String,
        val syncLevel: LyricsSyncLevel,
        val instrumental: Boolean,
        val match: LyricsMatch,
        val durationGapSec: Int?,
        val fetchedAt: Long,
        val plain: Lazy<String>,
        val lyrics: Lazy<SyncedLyrics?>,
        val sourceId: UUID? = null,
        val rankProvider: String = provider,
    )

    private class StoredRow(
        val id: UUID,
        val status: LyricsCandidateStatus,
        val syncLevel: LyricsSyncLevel,
        val fetchedAt: Long,
    )

    private class SongState(
        val songId: UUID,
        val entries: List<Entry>,
        val rows: Map<String, StoredRow>,
        val preferred: UUID?,
    ) {
        val defaultId: UUID? get() = preferred?.takeIf { id -> entries.any { it.id == id } } ?: entries.firstOrNull()?.id
    }

    private class Answer(
        val provider: String,
        val parsed: ParsedLyrics?,
        val match: LyricsMatch,
        val durationGapSec: Int?,
        val failed: Boolean = false,
    )

    private enum class AskMode { NEW, UPGRADE, REFRESH }

    fun getLyricsProviders(): List<LyricsProviderInfo> = registry.enabled().map(registry::info)

    suspend fun getLyrics(songId: UUID, candidateId: UUID? = null): LyricsCandidate? {
        val infos = providerInfos()
        return dbQuery {
            val state = loadState(songId, infos) ?: return@dbQuery null
            val entry = state.entries.firstOrNull { it.id == (candidateId ?: state.defaultId) } ?: return@dbQuery null
            toCandidate(entry, state, infos, includeContent = true)
        }
    }

    suspend fun getLyricsCandidates(songId: UUID, includeContent: Boolean = false): LyricsSearchResult {
        val infos = providerInfos()
        return dbQuery {
            val state = loadState(songId, infos) ?: throw IllegalArgumentException("Song not found")
            result(state, infos, includeContent)
        }
    }

    suspend fun getLyricsCandidate(candidateId: UUID): LyricsCandidate? {
        val infos = providerInfos()
        return dbQuery {
            val storedSongId = LyricsCandidateTable.select(LyricsCandidateTable.songId)
                .where { LyricsCandidateTable.id eq candidateId }
                .singleOrNull()?.get(LyricsCandidateTable.songId)?.value
            val songIds = storedSongId?.let(::listOf) ?: VIRTUAL_PROVIDERS.map { virtualId(candidateId, it) }
            songIds.firstNotNullOfOrNull { songId ->
                val state = loadState(songId, infos) ?: return@firstNotNullOfOrNull null
                state.entries.firstOrNull { it.id == candidateId }?.let { toCandidate(it, state, infos, includeContent = true) }
            }
        }
    }

    suspend fun lyricsInfoFor(songIds: Collection<UUID>): Map<UUID, SongLyricsInfo> {
        if (songIds.isEmpty()) return emptyMap()
        val infos = providerInfos()
        return dbQuery {
            songIds.distinct().chunked(INFO_CHUNK).flatMap { chunk -> infoChunk(chunk, infos) }.toMap()
        }
    }

    suspend fun findLyrics(
        songId: UUID,
        providers: List<String>? = null,
        refresh: Boolean = false,
        priority: HttpClientPriority = HttpClientPriority.HIGH,
        timeout: Duration? = LOOKUP_TIMEOUT,
    ): LyricsSearchResult {
        val request = requestFor(songId) ?: throw IllegalArgumentException("Song not found")
        val plan = askPlan(songId, providers, refresh)
        val (answers, pending) = askAll(plan.keys.toList(), request, priority, timeout)
        return store(songId, answers, plan, pending)
    }

    suspend fun findLyricsByQuery(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long? = null,
        providers: List<String>? = null,
    ): LyricsSearchResult {
        val request = LyricsRequest(
            title = title.cleanTitle(),
            artist = artist,
            album = album,
            durationSec = durationMs?.let { (it / 1000.0).roundToInt() },
        )
        val asked = selectProviders(providers).filterNot { it.id == SIDECAR }
        val (answers, pending) = askAll(asked, request, HttpClientPriority.HIGH, LOOKUP_TIMEOUT)
        val infos = providerInfos()
        val now = System.currentTimeMillis()
        val entries = answers.mapNotNull { entryOf(it, null, now) }.sortedWith(rankOrder(infos))
        val state = SongState(UUID(0, 0), entries, emptyMap(), null)
        return LyricsSearchResult(
            candidates = entries.mapIndexed { index, entry -> toCandidate(entry, state, infos, true, index + 1) },
            defaultCandidateId = entries.firstOrNull()?.id,
            pending = pending,
        )
    }

    fun streamLyrics(songId: UUID, providers: List<String>? = null): Flow<LyricsCandidate> = channelFlow {
        val request = requestFor(songId) ?: throw IllegalArgumentException("Song not found")
        val infos = providerInfos()
        val plan = askPlan(songId, providers, refresh = false)
        dbQuery { loadState(songId, infos) }?.let { state ->
            state.entries.filterNot { it.provider in plan.keys.map(LyricsProvider::id) }
                .forEach { send(toCandidate(it, state, infos, includeContent = true)) }
        }
        val collected = mutableListOf<Answer>()
        val pending = askEach(plan.keys.toList(), request, HttpClientPriority.HIGH, LOOKUP_TIMEOUT) { answer ->
            collected += answer
            val entry = entryOf(answer, songId, System.currentTimeMillis()) ?: return@askEach
            send(toCandidate(entry, SongState(songId, emptyList(), emptyMap(), null), infos, true, 0))
        }
        store(songId, collected, plan, pending)
    }

    suspend fun setSyncedLyrics(songId: UUID, lyrics: SyncedLyrics?): LyricsSearchResult {
        if (lyrics != null) {
            if (lyrics.lines.isEmpty()) throw IllegalArgumentException("Lyrics have no lines")
            LyricsAssembler.validate(lyrics)?.let { throw IllegalArgumentException(it) }
        }
        val infos = providerInfos()
        return dbQuery {
            requireSong(songId)
            val existing = LyricsCandidateTable.select(LyricsCandidateTable.id)
                .where { LyricsCandidateTable.songId eq songId }
                .andWhere { LyricsCandidateTable.provider eq MANUAL }
                .singleOrNull()?.get(LyricsCandidateTable.id)?.value
            if (lyrics == null) {
                if (existing != null) {
                    clearPin(songId, existing)
                    LyricsCandidateTable.deleteWhere { LyricsCandidateTable.id eq existing }
                }
            } else {
                val level = LyricsAssembler.syncLevel(lyrics)
                writeRow(
                    songId, MANUAL, existing, LyricsCandidateStatus.FOUND, level, false,
                    LyricsMatch.VERIFIED, null, lyrics, LyricsAssembler.plainText(lyrics)
                )
            }
            refreshUncensored(songId, infos)
            val state = loadState(songId, infos)!!
            persistRanks(state)
            entityEvents.updated(EntityType.SONG, listOf(songId))
            result(state, infos, includeContent = false)
        }
    }

    suspend fun importSyncedLyrics(songId: UUID, file: LyricsImport): LyricsSearchResult {
        val parsed = when (file.format) {
            LyricsImportFormat.LRC -> EnhancedLrcParser.parse(file.content)
            LyricsImportFormat.TTML -> TtmlParser.parse(file.content)
            LyricsImportFormat.LYRICSFILE -> LyricsFileParser.parse(file.content)
        }
        val lyrics = parsed?.lyrics ?: throw IllegalArgumentException("The content has no synced lyrics")
        return setSyncedLyrics(songId, lyrics)
    }

    suspend fun setPreferredLyrics(songId: UUID, candidateId: UUID?): LyricsSearchResult {
        val infos = providerInfos()
        return dbQuery {
            requireSong(songId)
            if (candidateId != null) {
                val stored = LyricsCandidateTable.select(LyricsCandidateTable.id)
                    .where { LyricsCandidateTable.id eq candidateId }
                    .andWhere { LyricsCandidateTable.songId eq songId }
                    .andWhere { LyricsCandidateTable.status eq LyricsCandidateStatus.FOUND }
                    .any()
                if (!stored) throw IllegalArgumentException("Candidate $candidateId is not a stored candidate of song $songId")
            }
            SongTable.update({ SongTable.id eq songId }) {
                it[preferredLyricsCandidate] = candidateId
            }
            entityEvents.updated(EntityType.SONG, listOf(songId))
            result(loadState(songId, infos)!!, infos, includeContent = false)
        }
    }

    private suspend fun requestFor(songId: UUID): LyricsRequest? {
        val song = songService.byId(songId) ?: return null
        return LyricsRequest(
            title = song.title.cleanTitle(),
            artist = song.artists.joinToString(", ") { it.name },
            album = song.album?.fullName,
            durationSec = song.duration.takeIf { it > 0 }?.let { (it / 1000.0).roundToInt() },
            songPath = song.path,
        )
    }

    private fun selectProviders(filter: List<String>?): List<LyricsProvider> =
        (listOf(sidecar) + registry.enabled()).filter { filter == null || it.id in filter }

    suspend fun songsDue(): List<UUID> {
        val providers = registry.enabled().map { it.id }
        if (providers.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val songIds = dbQuery { SongTable.select(SongTable.id).orderBy(SongTable.inserted).map { it[SongTable.id].value } }
        return songIds.chunked(INFO_CHUNK).flatMap { chunk ->
            val rows = dbQuery { storedRows(chunk) }
            chunk.filter { songId -> providers.any { askMode(it, rows[songId]?.get(it), refresh = false, now) != null } }
        }
    }

    private suspend fun askPlan(songId: UUID, filter: List<String>?, refresh: Boolean): Map<LyricsProvider, AskMode> {
        val rows = dbQuery { storedRows(listOf(songId)) }[songId].orEmpty()
        val now = System.currentTimeMillis()
        return selectProviders(filter).mapNotNull { provider ->
            askMode(provider.id, rows[provider.id], refresh, now)?.let { provider to it }
        }.toMap()
    }

    private fun askMode(provider: String, row: StoredRow?, refresh: Boolean, now: Long): AskMode? = when {
        refresh || provider == SIDECAR -> AskMode.REFRESH
        row == null -> AskMode.NEW
        row.status == LyricsCandidateStatus.ERROR ->
            AskMode.NEW.takeIf { row.fetchedAt <= now - ERROR_RETRY_AFTER.inWholeMilliseconds }
        row.fetchedAt > now - RETRY_AFTER.inWholeMilliseconds -> null
        row.status == LyricsCandidateStatus.MISS -> AskMode.NEW
        row.syncLevel == LyricsSyncLevel.LINE -> AskMode.UPGRADE
        else -> null
    }

    private suspend fun askAll(
        providers: List<LyricsProvider>,
        request: LyricsRequest,
        priority: HttpClientPriority,
        timeout: Duration?,
    ): Pair<List<Answer>, List<String>> {
        val answers = mutableListOf<Answer>()
        val pending = askEach(providers, request, priority, timeout) { answers += it }
        return answers to pending
    }

    private suspend fun askEach(
        providers: List<LyricsProvider>,
        request: LyricsRequest,
        priority: HttpClientPriority,
        timeout: Duration?,
        onAnswer: suspend (Answer) -> Unit,
    ): List<String> = coroutineScope {
        val results = Channel<Answer>(Channel.UNLIMITED)
        val jobs = providers.map { provider ->
            launch { results.send(ask(provider, request, priority)) }
        }
        val done = mutableSetOf<String>()
        suspend fun receiveAll() = repeat(providers.size) {
            val answer = results.receive()
            done += answer.provider
            onAnswer(answer)
        }
        if (timeout == null) receiveAll() else withTimeoutOrNull(timeout) { receiveAll() }
        jobs.forEach { it.cancel() }
        providers.map { it.id }.filterNot { it in done }
    }

    private suspend fun ask(provider: LyricsProvider, request: LyricsRequest, priority: HttpClientPriority): Answer {
        val result = try {
            provider.fetch(request, priority)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Lyrics provider ${provider.id} failed: ${e.message}")
            return Answer(provider.id, null, LyricsMatch.PROVIDER, null, failed = true)
        }
        return when (result) {
            is ProviderResult.Error -> Answer(provider.id, null, LyricsMatch.PROVIDER, null, failed = true)
            is ProviderResult.Miss -> Answer(provider.id, null, LyricsMatch.PROVIDER, null)
            is ProviderResult.Found -> answerOf(provider.id, result, request)
        }
    }

    private fun answerOf(provider: String, found: ProviderResult.Found, request: LyricsRequest): Answer {
        val parsed = parsePayload(found.payload, request)?.takeIf { !it.isEmpty() }
            ?: return Answer(provider, null, LyricsMatch.PROVIDER, null)
        val title = found.trackTitle ?: parsed.title
        val artist = found.trackArtist ?: parsed.artist
        val durationSec = found.durationSec ?: parsed.durationSec
        val gap = TrackMatcher.durationGapSec(request.durationSec, durationSec)
        if (title == null || artist == null) {
            val match = if (found.verifiedMetadata) LyricsMatch.VERIFIED else LyricsMatch.PROVIDER
            return Answer(provider, parsed, match, gap)
        }
        val match = TrackMatcher.verify(request.title, request.artist, request.durationSec, title, artist, durationSec)
            ?: return Answer(provider, null, LyricsMatch.PROVIDER, null)
        return Answer(provider, parsed, match, gap)
    }

    private fun parsePayload(payload: LyricsPayload, request: LyricsRequest): ParsedLyrics? = when (payload) {
        is LyricsPayload.Ttml -> TtmlParser.parse(payload.xml)
        is LyricsPayload.Lrc -> EnhancedLrcParser.parse(payload.text)
        is LyricsPayload.Plain -> EnhancedLrcParser.parse(payload.text)
        is LyricsPayload.Krc -> KrcParser.parse(payload.bytes, containsCjk(request.title + request.artist))
        is LyricsPayload.LyricsPlusJson -> LyricsPlusParser.parse(payload.json)
        is LyricsPayload.TimedRows -> LyricsAssembler.assemble(
            payload.rows.map { RawLine(start = it.startMs.milliseconds, end = it.endMs?.milliseconds, text = it.text) }
        )
        is LyricsPayload.Instrumental -> ParsedLyrics(null, "", LyricsSyncLevel.PLAIN, instrumental = true)
        is LyricsPayload.LyricsFile -> LyricsFileParser.parse(payload.yaml)
    }

    private fun ParsedLyrics.isEmpty(): Boolean = lyrics == null && plain.isBlank() && !instrumental

    private suspend fun store(
        songId: UUID,
        answers: List<Answer>,
        plan: Map<LyricsProvider, AskMode>,
        pending: List<String>,
    ): LyricsSearchResult {
        val modes = plan.mapKeys { it.key.id }
        val infos = providerInfos()
        return dbQuery {
            requireSong(songId)
            val rows = storedRows(listOf(songId))[songId].orEmpty()
            var changed = false
            for (answer in answers) {
                val row = rows[answer.provider]
                val parsed = answer.parsed
                val mode = modes[answer.provider] ?: AskMode.NEW
                if (answer.failed) {
                    if (answer.provider == SIDECAR) continue
                    if (row == null || row.status == LyricsCandidateStatus.MISS) {
                        writeRow(
                            songId, answer.provider, row?.id, LyricsCandidateStatus.ERROR, LyricsSyncLevel.PLAIN, false,
                            LyricsMatch.PROVIDER, null, null, null
                        )
                    } else {
                        touchRow(row.id)
                    }
                    continue
                }
                if (answer.provider == SIDECAR && parsed == null) {
                    if (row != null) {
                        clearPin(songId, row.id)
                        LyricsCandidateTable.deleteWhere { LyricsCandidateTable.id eq row.id }
                        changed = true
                    }
                    continue
                }
                val replace = when {
                    row == null || row.status == LyricsCandidateStatus.ERROR -> true
                    parsed == null -> false
                    mode == AskMode.UPGRADE -> parsed.syncLevel == LyricsSyncLevel.WORD
                    else -> true
                }
                if (!replace) {
                    touchRow(row!!.id)
                    continue
                }
                changed = true
                writeRow(
                    songId,
                    answer.provider,
                    row?.id,
                    if (parsed == null) LyricsCandidateStatus.MISS else LyricsCandidateStatus.FOUND,
                    parsed?.syncLevel ?: LyricsSyncLevel.PLAIN,
                    parsed?.instrumental ?: false,
                    answer.match,
                    answer.durationGapSec,
                    parsed?.lyrics,
                    parsed?.plain,
                )
            }
            if (refreshUncensored(songId, infos)) changed = true
            val state = loadState(songId, infos)!!
            persistRanks(state)
            if (changed) entityEvents.updated(EntityType.SONG, listOf(songId))
            result(state, infos, includeContent = false, pending = pending)
        }
    }

    private fun entryOf(answer: Answer, songId: UUID?, fetchedAt: Long): Entry? {
        val parsed = answer.parsed ?: return null
        return Entry(
            id = UUID.randomUUID(),
            songId = songId,
            provider = answer.provider,
            syncLevel = parsed.syncLevel,
            instrumental = parsed.instrumental,
            match = answer.match,
            durationGapSec = answer.durationGapSec,
            fetchedAt = fetchedAt,
            plain = lazyOf(parsed.plain),
            lyrics = lazyOf(parsed.lyrics),
        )
    }

    private fun requireSong(songId: UUID) {
        if (SongTable.select(SongTable.id).where { SongTable.id eq songId }.empty()) {
            throw IllegalArgumentException("Song not found")
        }
    }

    private fun storedRows(songIds: Collection<UUID>): Map<UUID, Map<String, StoredRow>> =
        LyricsCandidateTable.select(
            LyricsCandidateTable.id,
            LyricsCandidateTable.songId,
            LyricsCandidateTable.provider,
            LyricsCandidateTable.status,
            LyricsCandidateTable.syncLevel,
            LyricsCandidateTable.fetchedAt,
        ).where { LyricsCandidateTable.songId inList songIds }
            .groupBy { it[LyricsCandidateTable.songId].value }
            .mapValues { (_, rows) ->
                rows.associate {
                    it[LyricsCandidateTable.provider] to StoredRow(
                        it[LyricsCandidateTable.id].value,
                        it[LyricsCandidateTable.status],
                        it[LyricsCandidateTable.syncLevel],
                        it[LyricsCandidateTable.fetchedAt],
                    )
                }
            }

    private fun writeRow(
        songId: UUID,
        provider: String,
        existing: UUID?,
        status: LyricsCandidateStatus,
        syncLevel: LyricsSyncLevel,
        instrumental: Boolean,
        match: LyricsMatch,
        durationGapSec: Int?,
        lyrics: SyncedLyrics?,
        plain: String?,
        source: UUID? = null,
    ): UUID {
        val content = lyrics?.let { AppCbor.encodeToByteArray(it) }
        val now = System.currentTimeMillis()
        if (existing != null) {
            LyricsCandidateTable.update({ LyricsCandidateTable.id eq existing }) {
                it[LyricsCandidateTable.status] = status
                it[LyricsCandidateTable.syncLevel] = syncLevel
                it[LyricsCandidateTable.instrumental] = instrumental
                it[LyricsCandidateTable.match] = match
                it[LyricsCandidateTable.durationGapSec] = durationGapSec
                it[LyricsCandidateTable.content] = content
                it[LyricsCandidateTable.plain] = plain
                it[sourceCandidate] = source
                it[fetchedAt] = now
            }
            return existing
        } else {
            val id = UUID.randomUUID()
            LyricsCandidateTable.insert {
                it[LyricsCandidateTable.id] = id
                it[LyricsCandidateTable.songId] = songId
                it[LyricsCandidateTable.provider] = provider
                it[LyricsCandidateTable.status] = status
                it[LyricsCandidateTable.syncLevel] = syncLevel
                it[LyricsCandidateTable.instrumental] = instrumental
                it[LyricsCandidateTable.match] = match
                it[LyricsCandidateTable.durationGapSec] = durationGapSec
                it[LyricsCandidateTable.content] = content
                it[LyricsCandidateTable.plain] = plain
                it[sourceCandidate] = source
                it[fetchedAt] = now
            }
            return id
        }
    }

    suspend fun refreshUncensored(songIds: Collection<UUID>) {
        val infos = providerInfos()
        songIds.distinct().chunked(UNCENSOR_CHUNK).forEach { chunk ->
            dbQuery {
                val changed = chunk.filter { refreshUncensored(it, infos) }
                changed.forEach { songId -> loadState(songId, infos)?.let(::persistRanks) }
                if (changed.isNotEmpty()) entityEvents.updated(EntityType.SONG, changed)
            }
        }
    }

    private fun refreshUncensored(songId: UUID, infos: Map<String, LyricsProviderInfo>): Boolean {
        val candidates = LyricsCandidateTable.select(LyricsCandidateTable.provider, LyricsCandidateTable.plain)
            .where { LyricsCandidateTable.songId eq songId }
            .andWhere { LyricsCandidateTable.status eq LyricsCandidateStatus.FOUND }
            .map { it[LyricsCandidateTable.provider] to it[LyricsCandidateTable.plain] }
        val relevant = candidates.any { (provider, plain) ->
            provider == UNCENSORED || (provider !in NEVER_UNCENSORED && plain != null && LyricsUncensor.hasMask(plain))
        }
        if (!relevant) return false
        val state = loadState(songId, infos) ?: return false
        val existing = state.entries.firstOrNull { it.provider == UNCENSORED }
        val target = state.entries.firstOrNull {
            it.provider !in NEVER_UNCENSORED && !it.instrumental && LyricsUncensor.hasMask(it.plain.value)
        }
        val restored = target?.let { source ->
            val donors = state.entries.filter { it.id != source.id && it.provider != UNCENSORED }
                .map { UncensorDonor(it.lyrics.value, it.plain.value) }
            val lyrics = source.lyrics.value
            if (lyrics != null) LyricsUncensor.uncensor(lyrics, donors)?.let { it to LyricsAssembler.plainText(it) }
            else LyricsUncensor.uncensorPlain(source.plain.value, donors)?.let { null to it }
        }
        if (target == null || restored == null) {
            if (existing == null) return false
            clearPin(songId, existing.id)
            LyricsCandidateTable.deleteWhere { LyricsCandidateTable.id eq existing.id }
            return true
        }
        val (lyrics, plain) = restored
        val unchanged = existing != null &&
            existing.sourceId == target.id &&
            existing.syncLevel == target.syncLevel &&
            existing.match == target.match &&
            existing.durationGapSec == target.durationGapSec &&
            existing.plain.value == plain &&
            existing.lyrics.value == lyrics
        val id = existing?.takeIf { unchanged }?.id ?: writeRow(
            songId, UNCENSORED, existing?.id, LyricsCandidateStatus.FOUND, target.syncLevel, false,
            target.match, target.durationGapSec, lyrics, plain, target.id
        )
        val movePin = existing == null && state.preferred == target.id
        if (movePin) {
            SongTable.update({ SongTable.id eq songId }) {
                it[preferredLyricsCandidate] = id
            }
        }
        return !unchanged || movePin
    }

    private fun clearPin(songId: UUID, candidateId: UUID) {
        SongTable.update({ SongTable.id eq songId and (SongTable.preferredLyricsCandidate eq candidateId) }) {
            it[preferredLyricsCandidate] = null
        }
    }

    private fun touchRow(id: UUID) {
        LyricsCandidateTable.update({ LyricsCandidateTable.id eq id }) {
            it[fetchedAt] = System.currentTimeMillis()
        }
    }

    private fun persistRanks(state: SongState) {
        val ranks = state.entries.mapIndexed { index, entry -> entry.id to index + 1 }.toMap()
        val rows = LyricsCandidateTable.select(LyricsCandidateTable.id, LyricsCandidateTable.rank)
            .where { LyricsCandidateTable.songId eq state.songId }
            .map { it[LyricsCandidateTable.id].value to it[LyricsCandidateTable.rank] }
        for ((id, current) in rows) {
            val rank = ranks[id] ?: 0
            if (rank != current) {
                LyricsCandidateTable.update({ LyricsCandidateTable.id eq id }) {
                    it[LyricsCandidateTable.rank] = rank
                }
            }
        }
    }

    private fun loadState(songId: UUID, infos: Map<String, LyricsProviderInfo>): SongState? {
        val song = SongTable.select(SongTable.lyrics, SongTable.preferredLyricsCandidate, SongTable.inserted)
            .where { SongTable.id eq songId }
            .singleOrNull() ?: return null
        val rows = LyricsCandidateTable.selectAll().where { LyricsCandidateTable.songId eq songId }.toList()
        val providers = rows.associate { it[LyricsCandidateTable.id].value to it[LyricsCandidateTable.provider] }
        val stored = rows.filter { it[LyricsCandidateTable.status] == LyricsCandidateStatus.FOUND }.map { row ->
            val content = row[LyricsCandidateTable.content]
            val provider = row[LyricsCandidateTable.provider]
            val sourceId = row[LyricsCandidateTable.sourceCandidate]?.value
            Entry(
                id = row[LyricsCandidateTable.id].value,
                songId = songId,
                provider = provider,
                syncLevel = row[LyricsCandidateTable.syncLevel],
                instrumental = row[LyricsCandidateTable.instrumental],
                match = row[LyricsCandidateTable.match],
                durationGapSec = row[LyricsCandidateTable.durationGapSec],
                fetchedAt = row[LyricsCandidateTable.fetchedAt],
                plain = lazyOf(row[LyricsCandidateTable.plain].orEmpty()),
                lyrics = lazy { content?.let { AppCbor.decodeFromByteArray<SyncedLyrics>(it) } },
                sourceId = sourceId,
                rankProvider = rankProvider(provider, sourceId, providers),
            )
        }
        val whisperx = SyncedLyricsTable.select(SyncedLyricsTable.content)
            .where { SyncedLyricsTable.songId eq songId }
            .singleOrNull()?.get(SyncedLyricsTable.content)
            ?.let { whisperxEntry(songId, it, song[SongTable.inserted]) }
        val own = songEntry(songId, song[SongTable.lyrics], song[SongTable.inserted])
        val entries = (stored + listOfNotNull(whisperx, own)).sortedWith(rankOrder(infos))
        val storedRows = rows.associate {
            it[LyricsCandidateTable.provider] to StoredRow(
                it[LyricsCandidateTable.id].value,
                it[LyricsCandidateTable.status],
                it[LyricsCandidateTable.syncLevel],
                it[LyricsCandidateTable.fetchedAt],
            )
        }
        return SongState(songId, entries, storedRows, song[SongTable.preferredLyricsCandidate]?.value)
    }

    private fun infoChunk(chunk: List<UUID>, infos: Map<String, LyricsProviderInfo>): List<Pair<UUID, SongLyricsInfo>> {
        val songs = SongTable.select(SongTable.id, SongTable.lyrics, SongTable.preferredLyricsCandidate, SongTable.inserted)
            .where { SongTable.id inList chunk }
            .toList()
        val rows = LyricsCandidateTable.select(
            LyricsCandidateTable.id,
            LyricsCandidateTable.songId,
            LyricsCandidateTable.provider,
            LyricsCandidateTable.syncLevel,
            LyricsCandidateTable.instrumental,
            LyricsCandidateTable.match,
            LyricsCandidateTable.durationGapSec,
            LyricsCandidateTable.fetchedAt,
            LyricsCandidateTable.sourceCandidate,
        ).where { LyricsCandidateTable.songId inList chunk }
            .andWhere { LyricsCandidateTable.status eq LyricsCandidateStatus.FOUND }
            .toList()
        val providers = rows.associate { it[LyricsCandidateTable.id].value to it[LyricsCandidateTable.provider] }
        val stored = rows.groupBy({ it[LyricsCandidateTable.songId].value }) { row ->
            val provider = row[LyricsCandidateTable.provider]
            val sourceId = row[LyricsCandidateTable.sourceCandidate]?.value
            Entry(
                id = row[LyricsCandidateTable.id].value,
                songId = row[LyricsCandidateTable.songId].value,
                provider = provider,
                syncLevel = row[LyricsCandidateTable.syncLevel],
                instrumental = row[LyricsCandidateTable.instrumental],
                match = row[LyricsCandidateTable.match],
                durationGapSec = row[LyricsCandidateTable.durationGapSec],
                fetchedAt = row[LyricsCandidateTable.fetchedAt],
                plain = lazyOf(""),
                lyrics = lazyOf(null),
                sourceId = sourceId,
                rankProvider = rankProvider(provider, sourceId, providers),
            )
        }
        val whisperx = SyncedLyricsTable.select(SyncedLyricsTable.songId)
            .where { SyncedLyricsTable.songId inList chunk }
            .andWhere { SyncedLyricsTable.content.isNotNull() }
            .map { it[SyncedLyricsTable.songId].value }
            .toSet()
        return songs.map { song ->
            val songId = song[SongTable.id].value
            val inserted = song[SongTable.inserted]
            val virtual = listOfNotNull(
                if (songId in whisperx) whisperxEntry(songId, null, inserted) else null,
                songEntry(songId, song[SongTable.lyrics], inserted),
            )
            val entries = (stored[songId].orEmpty() + virtual).sortedWith(rankOrder(infos))
            val state = SongState(songId, entries, emptyMap(), song[SongTable.preferredLyricsCandidate]?.value)
            val default = entries.firstOrNull { it.id == state.defaultId }
            songId to SongLyricsInfo(
                available = entries.isNotEmpty(),
                syncLevel = default?.syncLevel,
                levels = entries.map { it.syncLevel }.toSet(),
                instrumental = default?.instrumental ?: false,
                defaultCandidateId = default?.id,
                defaultProvider = default?.provider,
                candidateCount = entries.size,
            )
        }
    }

    private fun whisperxEntry(songId: UUID, content: ByteArray?, fetchedAt: Long): Entry {
        val lyrics = lazy { content?.let { AppCbor.decodeFromByteArray<SyncedLyrics>(it) } }
        return Entry(
            id = virtualId(songId, WHISPERX),
            songId = songId,
            provider = WHISPERX,
            syncLevel = LyricsSyncLevel.WORD,
            instrumental = false,
            match = LyricsMatch.VERIFIED,
            durationGapSec = null,
            fetchedAt = fetchedAt,
            plain = lazy { lyrics.value?.let(LyricsAssembler::plainText).orEmpty() },
            lyrics = lyrics,
        )
    }

    private fun songEntry(songId: UUID, text: String, fetchedAt: Long): Entry? {
        if (text.isBlank()) return null
        val parsed = EnhancedLrcParser.parse(text)
        return Entry(
            id = virtualId(songId, SONG),
            songId = songId,
            provider = SONG,
            syncLevel = parsed?.syncLevel ?: LyricsSyncLevel.PLAIN,
            instrumental = parsed?.instrumental ?: false,
            match = LyricsMatch.PROVIDER,
            durationGapSec = null,
            fetchedAt = fetchedAt,
            plain = lazyOf(parsed?.plain?.takeIf { it.isNotBlank() } ?: text.trim()),
            lyrics = lazyOf(parsed?.lyrics),
        )
    }

    private fun rankProvider(provider: String, sourceId: UUID?, providers: Map<UUID, String>): String =
        if (provider == UNCENSORED) sourceId?.let(providers::get) ?: provider else provider

    private fun rankOrder(infos: Map<String, LyricsProviderInfo>): Comparator<Entry> = compareBy<Entry>(
        {
            when (it.rankProvider) {
                MANUAL -> 0
                SIDECAR -> 1
                else -> 2
            }
        },
        { -rankLevel(it).ordinal },
        { if (it.provider == WHISPERX) 1 else 0 },
        { it.match.ordinal },
        { infos[it.rankProvider]?.priority ?: Int.MAX_VALUE },
        { it.durationGapSec?.let(::abs) ?: Int.MAX_VALUE },
        { if (it.provider == UNCENSORED) 0 else 1 },
    )

    private fun rankLevel(entry: Entry): LyricsSyncLevel =
        if (entry.instrumental && entry.syncLevel == LyricsSyncLevel.PLAIN) LyricsSyncLevel.LINE else entry.syncLevel

    private fun providerInfos(): Map<String, LyricsProviderInfo> {
        val enabled = registry.infos()
        val virtual = listOf(
            LyricsProviderInfo(SIDECAR, sidecar.name, sidecar.url, enabled = true, priority = -1),
            LyricsProviderInfo(MANUAL, "Manual", null, enabled = true, priority = enabled.size),
            LyricsProviderInfo(WHISPERX, "WhisperX", null, enabled = true, priority = enabled.size + 1),
            LyricsProviderInfo(SONG, "Song", null, enabled = true, priority = enabled.size + 2),
            LyricsProviderInfo(UNCENSORED, "Uncensored", null, enabled = true, priority = enabled.size + 3),
        )
        return (enabled + virtual).associateBy { it.id }
    }

    private fun result(
        state: SongState,
        infos: Map<String, LyricsProviderInfo>,
        includeContent: Boolean,
        pending: List<String> = emptyList(),
    ) = LyricsSearchResult(
        candidates = state.entries.map { toCandidate(it, state, infos, includeContent) },
        defaultCandidateId = state.defaultId,
        pending = pending,
    )

    private fun toCandidate(
        entry: Entry,
        state: SongState,
        infos: Map<String, LyricsProviderInfo>,
        includeContent: Boolean,
        rank: Int = state.entries.indexOfFirst { it.id == entry.id } + 1,
    ) = LyricsCandidate(
        id = entry.id,
        songId = entry.songId,
        provider = infos[entry.provider]
            ?: LyricsProviderInfo(entry.provider, entry.provider, null, enabled = false, priority = Int.MAX_VALUE),
        syncLevel = entry.syncLevel,
        instrumental = entry.instrumental,
        match = entry.match,
        durationGapSec = entry.durationGapSec,
        rank = rank,
        preferred = state.preferred == entry.id,
        fetchedAt = Date(entry.fetchedAt),
        plain = entry.plain.value,
        lyrics = if (includeContent) entry.lyrics.value else null,
    )

    companion object {
        const val MANUAL = "manual"
        const val SIDECAR = SidecarLyricsProvider.ID
        const val WHISPERX = "whisperx"
        const val SONG = "song"
        const val UNCENSORED = "uncensored"
        private val VIRTUAL_PROVIDERS = listOf(WHISPERX, SONG)
        private val NEVER_UNCENSORED = setOf(MANUAL, SIDECAR, UNCENSORED, WHISPERX, SONG)
        private const val UNCENSOR_CHUNK = 200
        private val RETRY_AFTER = 14.days
        private val ERROR_RETRY_AFTER = 3.days
        private val LOOKUP_TIMEOUT = 12.seconds
        private const val INFO_CHUNK = 1000

        private fun mask(provider: String): UUID = UUID.nameUUIDFromBytes("lyrics_candidate:$provider".toByteArray())

        fun virtualId(songId: UUID, provider: String): UUID {
            val mask = mask(provider)
            return UUID(songId.mostSignificantBits xor mask.mostSignificantBits, songId.leastSignificantBits xor mask.leastSignificantBits)
        }
    }
}
