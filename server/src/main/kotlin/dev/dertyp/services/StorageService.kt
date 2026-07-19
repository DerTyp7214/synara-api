package dev.dertyp.services

import dev.dertyp.core.getTotalSize
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.import.ImportBackend
import io.ktor.server.application.ApplicationEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StorageCategory { TOTAL, IMAGES, ANIMATED_IMAGES }

class StorageService(environment: ApplicationEnvironment) : IStorageService, IServerStorageService, Service() {
    override val tracksPath =
        environment.config.propertyOrNull("audio.tracks")?.getString()?.removeSuffix("/")
    override val albumsPath =
        environment.config.propertyOrNull("audio.albums")?.getString()?.removeSuffix("/")
    override val playlistsPath =
        environment.config.propertyOrNull("audio.playlists")?.getString()?.removeSuffix("/")
    override val customAudioPath =
        environment.config.property("audio.custom").getString().removeSuffix("/")
    override val imagesPath =
        environment.config.property("data.images").getString().removeSuffix("/")
    override val animatedImagesPath =
        environment.config.property("data.animated-images").getString().removeSuffix("/")
    override val secondaryTracksPaths = try {
        environment.config.propertyOrNull("audio.secondary-tracks")?.getList()?.map {
            it.removeSuffix("/")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val caches = mapOf(
        StorageCategory.TOTAL to CachedSize(::computeTotalStorage),
        StorageCategory.IMAGES to CachedSize(::computeImagesStorage),
        StorageCategory.ANIMATED_IMAGES to CachedSize(::computeAnimatedImagesStorage),
    )

    override fun forImporter(backend: ImportBackend): IServerStorageService =
        ImporterStorageService(this, backend)

    fun invalidate(category: StorageCategory) {
        caches.getValue(category).markDirty()
    }

    override suspend fun getTotalStorage(): Long = caches.getValue(StorageCategory.TOTAL).get()

    suspend fun getImagesStorage(): Long = caches.getValue(StorageCategory.IMAGES).get()

    suspend fun getAnimatedImagesStorage(): Long = caches.getValue(StorageCategory.ANIMATED_IMAGES).get()

    override suspend fun startService() {
        caches.values.forEach { cache ->
            try {
                cache.recompute()
            } catch (e: Throwable) {
                logger.error("Failed to compute storage size", e)
            }
        }
    }

    suspend fun recomputeAll(): Map<StorageCategory, Long> =
        caches.mapValues { (_, cache) -> cache.recompute() }

    override suspend fun stopService() {
        scope.cancel()
    }

    private fun computeTotalStorage(): Long {
        val pathsToMeasure = (
                listOfNotNull(
                    tracksPath,
                    albumsPath,
                    playlistsPath
                ).map { File(it).parentFile } +
                        secondaryTracksPaths.map { File(it) } +
                        listOf(File(customAudioPath)))
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
    override val secondaryTracksPaths: List<String> get() = delegate.secondaryTracksPaths

    override fun forImporter(backend: ImportBackend): IServerStorageService =
        delegate.forImporter(backend)
}
