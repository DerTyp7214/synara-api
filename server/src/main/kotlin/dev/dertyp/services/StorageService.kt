package dev.dertyp.services

import dev.dertyp.config.ServerConfig
import dev.dertyp.core.getTotalSize
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.import.ImportBackend
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StorageCategory { TOTAL, IMAGES, ANIMATED_IMAGES, PODCASTS }

class StorageService(config: ServerConfig) : IStorageService, IServerStorageService, Service() {
    private val paths = config.library

    override val tracksPath = paths.tracks?.removeSuffix("/")
    override val albumsPath = paths.albums?.removeSuffix("/")
    override val playlistsPath = paths.playlists?.removeSuffix("/")
    override val customAudioPath = paths.customAudio.removeSuffix("/")
    override val imagesPath = paths.images.removeSuffix("/")
    override val animatedImagesPath = paths.animatedImages.removeSuffix("/")
    override val podcastLibraryPath = paths.podcastLibrary.removeSuffix("/")
    override val podcastImportsPath = paths.podcastImports.removeSuffix("/")
    override val secondaryTracksPaths = paths.secondaryTracks.map { it.removeSuffix("/") }

    private val caches = mapOf(
        StorageCategory.TOTAL to CachedSize(::computeTotalStorage),
        StorageCategory.IMAGES to CachedSize(::computeImagesStorage),
        StorageCategory.ANIMATED_IMAGES to CachedSize(::computeAnimatedImagesStorage),
        StorageCategory.PODCASTS to CachedSize(::computePodcastStorage),
    )

    override fun forImporter(backend: ImportBackend): IServerStorageService =
        ImporterStorageService(this, backend)

    fun invalidate(category: StorageCategory) {
        caches.getValue(category).markDirty()
    }

    override suspend fun getTotalStorage(): Long = caches.getValue(StorageCategory.TOTAL).get()

    suspend fun getImagesStorage(): Long = caches.getValue(StorageCategory.IMAGES).get()

    suspend fun getAnimatedImagesStorage(): Long = caches.getValue(StorageCategory.ANIMATED_IMAGES).get()

    suspend fun getPodcastStorage(): Long = caches.getValue(StorageCategory.PODCASTS).get()

    override suspend fun startService() {
        caches.values.forEach { cache ->
            try {
                cache.recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Failed to compute storage size", e)
            }
        }
    }

    suspend fun recomputeAll(): Map<StorageCategory, Long> =
        caches.mapValues { (_, cache) -> cache.recompute() }

    private fun computeTotalStorage(): Long {
        val pathsToMeasure = (
                listOfNotNull(
                    tracksPath,
                    albumsPath,
                    playlistsPath
                ).map { File(it).parentFile } +
                        secondaryTracksPaths.map { File(it) } +
                        listOf(File(customAudioPath)) +
                        podcastRoots())
            .filterNotNull()
            .map { it.absoluteFile }
            .distinctBy { it.path }

        val rootPaths = pathsToMeasure.filter { p ->
            pathsToMeasure.none { other ->
                other != p && p.path.startsWith(other.path + File.separator)
            }
        }

        return rootPaths.sumOf { it.getTotalSize() }
    }

    private fun computeImagesStorage(): Long = File(imagesPath).getTotalSize()

    private fun computeAnimatedImagesStorage(): Long = File(animatedImagesPath).getTotalSize()

    private fun podcastRoots(): List<File> =
        listOf(File(podcastLibraryPath).absoluteFile, File(podcastImportsPath).absoluteFile).distinctBy { it.path }

    private fun computePodcastStorage(): Long = podcastRoots().sumOf { it.getTotalSize() }

    private inner class CachedSize(private val compute: () -> Long) {
        private val value = AtomicLong(UNSET)
        private val dirty = AtomicBoolean(true)
        private val lastComputedAt = AtomicLong(0)
        private val refreshing = AtomicBoolean(false)
        private val computeMutex = Mutex()

        fun markDirty() {
            dirty.set(true)
        }

        suspend fun get(): Long {
            if (value.get() == UNSET) return computeMutex.withLock {
                if (value.get() != UNSET) value.get() else recomputeLocked()
            }

            val minIntervalElapsed =
                System.nanoTime() - lastComputedAt.get() >= MIN_RECOMPUTE_INTERVAL.inWholeNanoseconds
            if (dirty.get() && minIntervalElapsed && refreshing.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        recompute()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.error("Failed to refresh storage size", e)
                    } finally {
                        refreshing.set(false)
                    }
                }
            }
            return value.get()
        }

        suspend fun recompute(): Long = computeMutex.withLock { recomputeLocked() }

        private fun recomputeLocked(): Long {
            dirty.set(false)
            val computed = compute()
            value.set(computed)
            lastComputedAt.set(System.nanoTime())
            return computed
        }
    }

    companion object {
        private const val UNSET = Long.MIN_VALUE
        private val MIN_RECOMPUTE_INTERVAL = 1.minutes
    }
}

class ImporterStorageService(
    private val delegate: IServerStorageService,
    private val backend: ImportBackend
) : IServerStorageService {
    private fun pluginPath(path: String?): String? {
        if (path == null) return null
        val file = File(path)
        val parent = file.parentFile ?: return null
        return File(parent, "${backend.id}/${file.name}").absolutePath
    }

    override val tracksPath: String? get() = pluginPath(delegate.tracksPath)
    override val albumsPath: String? get() = pluginPath(delegate.albumsPath)
    override val playlistsPath: String? get() = pluginPath(delegate.playlistsPath)
    override val customAudioPath: String get() = delegate.customAudioPath
    override val imagesPath: String get() = delegate.imagesPath
    override val animatedImagesPath: String get() = delegate.animatedImagesPath
    override val podcastLibraryPath: String get() = delegate.podcastLibraryPath
    override val podcastImportsPath: String get() = delegate.podcastImportsPath
    override val secondaryTracksPaths: List<String> get() = delegate.secondaryTracksPaths

    override fun forImporter(backend: ImportBackend): IServerStorageService =
        delegate.forImporter(backend)
}
