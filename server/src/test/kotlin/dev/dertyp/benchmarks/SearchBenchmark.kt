package dev.dertyp.benchmarks

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.google.gson.Gson
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.TestRedis
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableSong
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime

object SearchBenchmark {

    enum class Backend {
        SQLite, PostgreSQL, Redis
    }

    private lateinit var songService: SongService
    private lateinit var redisSearchService: RedisSearchService
    private lateinit var searchIndexWorker: SearchIndexWorker
    private lateinit var database: Database

    private val userId = UUID.randomUUID()
    private val sizes = listOf(1000, 5000, 25000, 100000)
    private val NUM_RUNS = mapOf(
        1000 to 15,
        5000 to 10,
        25000 to 5,
        100000 to 2
    )

    private val queries = listOf(
        "Song 500",
        "Art",
        "Artist 5",
        "Song -Artist1",
        "Artist -Song1",
        "nonexistentterm"
    )

    private val allTables = arrayOf(
        UserTable, SongTable, SongVariantTable, AlbumTable, ArtistTable, SongArtistTable, AlbumArtistTable,
        SongMusicBrainzTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable, ArtistAliasTable,
        ImageTable, GenreTable, SongGenreTable, AlbumGenreTable, ArtistGenreTable,
        ArtistMemberTable, SearchIndexQueueTable, MBRecordingTable, MBReleaseTable,
        MBArtistTable, MBArtistAliasTable, UserSongTable, FollowedArtistTable,
        ArtistSplitAliasTable, ImageMetadataTable, ProviderEnrichmentCheckTable,
        SongProviderTable, AlbumProviderTable, SongAudioDataTable, SyncedLyricsTable,
        PlaylistSongTable, UserPlaylistSongTable, PlaylistTable, UserPlaylistTable,
        PersonTable, SongComposerTable, SongLyricistTable, SongProducerTable,
        TranscodedSongTable
    )

    private object UI {
        private var lastLinesCount = 0

        data class StepState(
            val name: String,
            var status: Status = Status.PENDING,
            var duration: Duration? = null,
            var detail: String = ""
        )

        enum class Status { PENDING, ACTIVE, DONE }

        private val steps = mutableListOf<StepState>()

        fun resetSteps() {
            steps.clear()
            steps.add(StepState("Infrastructure"))
            steps.add(StepState("Data Generation"))
            steps.add(StepState("Injection/Indexing"))
            steps.add(StepState("Capturing RAM"))
            steps.add(StepState("Warmup Search"))
            steps.add(StepState("Queries"))
            steps.add(StepState("Cleanup"))
        }

        fun updateStep(name: String, status: Status, duration: Duration? = null, detail: String = "") {
            steps.find { it.name == name }?.let {
                it.status = status
                if (duration != null) it.duration = duration
                it.detail = detail
            }
        }

        fun draw(size: Int, backend: Backend, run: Int, realOut: PrintStream) {
            val oldOut = System.out
            System.setOut(realOut)
            try {
                if (lastLinesCount > 0) {
                    print("\u001b[${lastLinesCount}A")
                }

                val out = StringBuilder()
                out.append("\u001b[1m=========================================================\u001b[0m\n")
                out.append(" \u001b[1;34mSEARCH BENCHMARK PROGRESS\u001b[0m\n")
                out.append("=========================================================\n")
                out.append("Dataset:   \u001b[36m%-10d\u001b[0m [%d/%d]\n".format(size, sizes.indexOf(size) + 1, sizes.size))
                out.append("Backend:   \u001b[35m%-10s\u001b[0m [%d/%d]\n".format(backend.name, Backend.entries.indexOf(backend) + 1, Backend.entries.size))
                out.append("Iteration: \u001b[32m%d/%-10d\u001b[0m\n".format(run, NUM_RUNS[size]))
                out.append("---------------------------------------------------------\n")

                steps.forEach { step ->
                    val (icon, color) = when (step.status) {
                        Status.DONE -> "\u001b[32m✓\u001b[0m" to ""
                        Status.ACTIVE -> "\u001b[33m▶\u001b[0m" to "\u001b[1;33m"
                        Status.PENDING -> "\u001b[2m○\u001b[0m" to "\u001b[2m"
                    }
                    
                    val label = "$color${step.name}\u001b[0m"
                    val time = step.duration?.let { " | \u001b[2m$it\u001b[0m" } ?: ""
                    val detailText = if (step.detail.isNotEmpty()) " (\u001b[33m${step.detail}\u001b[0m)" else ""
                    
                    out.append("$icon %-30s%s%s\u001b[K\n".format(label, time, detailText))
                }
                out.append("=========================================================\n")
                out.append("\u001b[J")

                val finalStr = out.toString()
                print(finalStr)
                lastLinesCount = finalStr.count { it == '\n' }
            } finally {
                System.setOut(oldOut)
            }
        }

        fun clearTerminal(realOut: PrintStream) {
            val oldOut = System.out
            System.setOut(realOut)
            print("\u001b[H\u001b[2J")
            System.out.flush()
            System.setOut(oldOut)
            lastLinesCount = 0
        }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).level = Level.OFF
        (LoggerFactory.getLogger("org.testcontainers") as Logger).level = Level.OFF
        (LoggerFactory.getLogger("Exposed") as Logger).level = Level.OFF
        (LoggerFactory.getLogger("dev.dertyp") as Logger).level = Level.OFF

        val originalOut = System.out
        val nullOut = PrintStream(object : OutputStream() {
            override fun write(b: Int) {}
        })
        System.setOut(nullOut)

        UI.clearTerminal(originalOut)

        val resultFile = File("benchmark_results.md")
        resultFile.writeText("# Search Performance Benchmark Results\n")

        for (runs in NUM_RUNS) {
            resultFile.appendText("- Dataset Size: ${runs.key} (${runs.value} runs)\n")
        }
        
        val reports = mutableListOf<AggregatedReport>()
        for (size in sizes) {
            resultFile.appendText("## Dataset Size: $size\n\n")
            resultFile.appendText("| Backend | Init Time (Avg) | Avg Query Time (Mean) | Index RAM |\n")
            resultFile.appendText("| :--- | :--- | :--- | :--- |\n")

            val sizeReports = mutableListOf<AggregatedReport>()
            for (backend in Backend.entries) {
                val runResults = mutableListOf<SingleRunResult>()
                
                for (i in 1..NUM_RUNS[size]!!) {
                    runResults.add(runSingleBenchmark(backend, size, i, originalOut))
                }
                
                val agg = aggregate(backend.name, size, runResults)
                reports.add(agg)
                sizeReports.add(agg)
                
                val ramStr = if (backend == Backend.Redis) "${agg.avgRamUsageMb} MB" else "N/A"
                resultFile.appendText("| ${backend.name} | ${agg.avgInitTime} | ${agg.avgQueryTime} | $ramStr |\n")
            }
            
            resultFile.appendText("\n### Detailed Query Statistics ($size)\n\n")
            resultFile.appendText("| Query | Backend | Mean Time | Min | Max | Total Found |\n")
            resultFile.appendText("| :--- | :--- | :--- | :--- | :--- | :--- |\n")
            
            for (query in queries) {
                for (report in sizeReports) {
                    val stats = report.queryStats.find { it.query == query }
                    if (stats != null) {
                        resultFile.appendText("| `${query}` | ${report.backend} | ${stats.mean} | ${stats.min} | ${stats.max} | ${stats.count} |\n")
                    }
                }
                resultFile.appendText("| | | | | | |\n")
            }
            resultFile.appendText("\n---\n\n")
        }

        appendFinalAnalysis(resultFile, reports)
        originalOut.println("\n\u001b[1;32mBenchmark complete! Check benchmark_results.md\u001b[0m")
    }

    private fun aggregate(backend: String, size: Int, runs: List<SingleRunResult>): AggregatedReport {
        val validRuns = runs.filter { it.initTime != Duration.ZERO }
        if (validRuns.isEmpty()) return AggregatedReport(backend, size, Duration.ZERO, emptyList(), Duration.ZERO, 0)

        val avgInit = validRuns.map { it.initTime.inWholeNanoseconds }.average().toLong().nanoseconds
        val avgRam = validRuns.map { it.ramUsageMb }.average().toLong()
        
        val queryStats = queries.map { query ->
            val runResults = validRuns.mapNotNull { run -> run.queryResults.find { it.query == query } }
            val count = runResults.firstOrNull()?.count ?: 0
            QueryStats(
                query = query,
                mean = runResults.map { it.mean.inWholeNanoseconds }.average().toLong().nanoseconds,
                min = (runResults.minOfOrNull { it.min.inWholeNanoseconds } ?: 0).nanoseconds,
                max = (runResults.maxOfOrNull { it.max.inWholeNanoseconds } ?: 0).nanoseconds,
                count = count
            )
        }
        
        val totalAvgQuery = queryStats.map { it.mean.inWholeNanoseconds }.average().toLong().nanoseconds
        
        return AggregatedReport(backend, size, avgInit, queryStats, totalAvgQuery, avgRam)
    }

    private suspend fun runSingleBenchmark(backend: Backend, size: Int, runIdx: Int, realOut: PrintStream): SingleRunResult {
        UI.resetSteps()
        fun draw() = UI.draw(size, backend, runIdx, realOut)

        UI.updateStep("Infrastructure", UI.Status.ACTIVE)
        draw()
        val infraTime = measureTime {
            setupKoin(backend)
            setupDatabase(backend)
        }
        UI.updateStep("Infrastructure", UI.Status.DONE, infraTime)
        
        UI.updateStep("Data Generation", UI.Status.ACTIVE)
        draw()
        val songs = generateData(size)
        UI.updateStep("Data Generation", UI.Status.DONE)

        UI.updateStep("Injection/Indexing", UI.Status.ACTIVE)
        draw()

        val injectionStart = System.currentTimeMillis()
        @OptIn(DelicateCoroutinesApi::class)
        val timerJob = GlobalScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - injectionStart).milliseconds
                UI.updateStep("Injection/Indexing", UI.Status.ACTIVE, detail = "Injecting... $elapsed")
                draw()
                delay(100.milliseconds)
            }
        }

        val initTime = measureTime {
            try {
                songService.createBatch(songs)
            } finally {
                timerJob.cancelAndJoin()
            }
            
            if (backend == Backend.PostgreSQL || backend == Backend.Redis) {
                UI.updateStep("Injection/Indexing", UI.Status.ACTIVE, detail = "Enqueuing...")
                draw()
                enqueueAllForIndexing()
                
                var totalProcessed = 0
                while (true) {
                    val processed = searchIndexWorker.processBatch()
                    if (processed == 0) break
                    totalProcessed += processed
                    UI.updateStep("Injection/Indexing", UI.Status.ACTIVE, detail = "Indexing: $totalProcessed/$size")
                    draw()
                }
            }
        }
        UI.updateStep("Injection/Indexing", UI.Status.DONE, initTime)

        UI.updateStep("Capturing RAM", UI.Status.ACTIVE)
        draw()
        val ramUsage = if (backend == Backend.Redis) redisSearchService.getMemoryUsage() else 0L
        UI.updateStep("Capturing RAM", UI.Status.DONE)

        val queryResults = mutableListOf<QueryResult>()
        UI.updateStep("Warmup Search", UI.Status.ACTIVE)
        draw()
        songService.rankedSearch(0, 50, "Warmup", true, userId)
        UI.updateStep("Warmup Search", UI.Status.DONE)
        
        UI.updateStep("Queries", UI.Status.ACTIVE)
        draw()
        val runsPerQuery = 5
        for (query in queries) {
            UI.updateStep("Queries", UI.Status.ACTIVE, detail = query)
            draw()
            
            val times = mutableListOf<Long>()
            var count = 0
            repeat(runsPerQuery) {
                val time = measureTime {
                    val result = songService.rankedSearch(0, 50, query, true, userId)
                    count = result.total
                }
                times.add(time.inWholeNanoseconds)
            }

            queryResults.add(QueryResult(
                query = query,
                mean = times.average().toLong().nanoseconds,
                min = times.minOrNull()?.nanoseconds ?: 0.nanoseconds,
                max = times.maxOrNull()?.nanoseconds ?: 0.nanoseconds,
                count = count
            ))
        }
        UI.updateStep("Queries", UI.Status.DONE, queryResults.sumOf { it.mean.inWholeNanoseconds }.nanoseconds)

        UI.updateStep("Cleanup", UI.Status.ACTIVE)
        draw()
        val tearDownTime = measureTime { tearDown() }
        UI.updateStep("Cleanup", UI.Status.DONE, tearDownTime)
        draw()

        return SingleRunResult(initTime, queryResults, ramUsage)
    }

    private fun appendFinalAnalysis(file: File, reports: List<AggregatedReport>) {
        val sb = StringBuilder()
        sb.append("## Scaling Analysis (Mean Latency)\n\n")
        sb.append("| Backend | 1k -> 100k Latency Increase |\n")
        sb.append("| :--- | :--- |\n")
        
        for (backend in Backend.entries) {
            val small = reports.find { it.backend == backend.name && it.datasetSize == sizes.first() }
            val big = reports.find { it.backend == backend.name && it.datasetSize == sizes.last() }
            
            if (small != null && big != null && small.avgQueryTime != Duration.ZERO) {
                val growth = big.avgQueryTime.inWholeNanoseconds.toDouble() / small.avgQueryTime.inWholeNanoseconds.toDouble()
                sb.append("| ${backend.name} | %.2fx growth (for 100x data) |\n".format(growth))
            }
        }
        
        file.appendText(sb.toString())
    }

    private fun setupKoin(backend: Backend) {
        stopKoin()
        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<LinkResolverService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single {
                    val storageService = mockk<StorageService>(relaxed = true)
                    every { storageService.imagesPath } returns "bench_images"
                    storageService
                }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { mockk<LibraryMergeService>(relaxed = true) }
                single { Gson() }

                val redisConfig = RedisCacheProvider.Config().apply {
                    host = if (backend == Backend.Redis) TestRedis.host else "none"
                    port = TestRedis.port
                    useRedisSearch = (backend == Backend.Redis)
                    indexPrefix = "bench_${UUID.randomUUID().toString().take(8)}"
                }
                single { redisConfig }

                if (backend == Backend.Redis) {
                    single { RedisCacheProvider(get()) }
                } else {
                    single { mockk<RedisCacheProvider>(relaxed = true) }
                }

                single { RedisSearchService() }
                single { SearchIndexWorker() }
                single { AlbumService(get()) }
                single { ArtistService(get()) }
                single { SongService(get()) }
                single { ImageService(get(), get()) }
            })
        }

        songService = GlobalContext.get().get()
        redisSearchService = GlobalContext.get().get()
        searchIndexWorker = GlobalContext.get().get()

        if (backend == Backend.Redis) {
            redisSearchService.initIndex()
        }
    }

    private fun setupDatabase(backend: Backend) {
        val dialect = if (backend == Backend.SQLite) DbDialect.SQLITE else DbDialect.POSTGRES
        database = TestDatabase.connect(dialect, "bench")
        TransactionManager.defaultDatabase = database
        
        transaction(database) {
            SchemaUtils.create(*allTables)
            UserTable.insert { row ->
                row[UserTable.id] = userId
                row[UserTable.username] = "benchuser"
                row[UserTable.passwordHash] = ""
            }
        }
    }

    private fun tearDown() {
        try {
            GlobalContext.get().getOrNull<RedisCacheProvider>()?.jedis?.close()
        } catch (_: Exception) {}
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun generateData(count: Int): List<InsertableSong> {
        val albumsPerArtist = 5
        val songsPerAlbum = 10
        val artistCount = (count / (albumsPerArtist * songsPerAlbum)).coerceAtLeast(1)
        
        val songs = mutableListOf<InsertableSong>()
        for (i in 1..count) {
            val artistIndex = (i / (albumsPerArtist * songsPerAlbum)) % artistCount
            val albumIndex = (i / songsPerAlbum) % (artistCount * albumsPerArtist)
            
            val artistName = "Artist$artistIndex"
            val albumName = "Album$albumIndex"
            
            songs.add(InsertableSong(
                title = "Song $i",
                artists = listOf(artistName),
                album = InsertableAlbum(albumName, listOf(artistName)),
                duration = 180000,
                explicit = i % 10 == 0,
                path = "/music/$artistName/$albumName/song_$i.flac"
            ))
        }
        return songs
    }

    private suspend fun enqueueAllForIndexing() = dbQuery {
        val songIds = SongTable.selectAll().map { it[SongTable.id].value }
        SearchIndexQueueTable.batchInsert(songIds) { songId ->
            this[SearchIndexQueueTable.entityId] = songId
            this[SearchIndexQueueTable.entityType] = SearchIndexEntityType.SONG
        }
    }

    data class AggregatedReport(
        val backend: String,
        val datasetSize: Int,
        val avgInitTime: Duration,
        val queryStats: List<QueryStats>,
        val avgQueryTime: Duration,
        val avgRamUsageMb: Long
    )

    data class QueryStats(
        val query: String,
        val mean: Duration,
        val min: Duration,
        val max: Duration,
        val count: Int
    )

    data class SingleRunResult(
        val initTime: Duration,
        val queryResults: List<QueryResult>,
        val ramUsageMb: Long
    )

    data class QueryResult(
        val query: String,
        val mean: Duration,
        val min: Duration,
        val max: Duration,
        val count: Int
    )
}
