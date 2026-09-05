package dev.dertyp.services.cover

import dev.dertyp.core.ApplicationScope
import dev.dertyp.services.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.streams.asSequence

class CoverAssetPackService(private val config: CoverConfig) : Service() {
    @Volatile
    private var loaded: List<CoverAssetPack> = listOf(CoverAssetPack.builtin())

    override suspend fun startService() {
        reload()
    }

    suspend fun reload(): List<CoverAssetPack> = withContext(Dispatchers.IO) {
        val root = Path.of(config.assetsPath)
        val packs = ArrayList<CoverAssetPack>()
        packs += CoverAssetPack.builtin()
        if (root.exists() && root.isDirectory()) {
            root.listDirectoryEntries().filter { it.isDirectory() }.sorted().forEach { dir ->
                val manifestFile = dir.resolve(MANIFEST_NAME)
                if (!manifestFile.isRegularFile()) return@forEach
                val pack = runCatching { load(dir, manifestFile) }
                    .onFailure { logger.warn("Skipping cover asset pack at $dir: ${it.message}") }
                    .getOrNull() ?: return@forEach
                if (packs.any { it.id == pack.id }) {
                    logger.warn("Skipping cover asset pack at $dir: duplicate id ${pack.id}")
                    return@forEach
                }
                packs += pack
            }
        }
        loaded = packs
        logger.info("Loaded ${packs.size - 1} cover asset pack(s) from $root")
        packs
    }

    fun packs(includeNsfw: Boolean): List<CoverAssetPack> =
        loaded.filter { includeNsfw || !it.nsfw }

    fun nsfwAllowed(requested: Boolean): Boolean = config.nsfwPacks && requested

    fun select(tags: Set<String>, seed: Long, allowNsfw: Boolean, packId: String?): CoverAssetPack {
        val nsfw = nsfwAllowed(allowNsfw)
        if (packId != null) {
            val pack = loaded.firstOrNull { it.id == packId } ?: throw IllegalArgumentException("Unknown cover asset pack: $packId")
            if (pack.nsfw && !nsfw) throw IllegalArgumentException("NSFW cover asset packs are not enabled")
            return pack
        }
        val normalized = tags.map { it.lowercase() }.toSet()
        val random = Random(seed)
        val candidates = packs(nsfw)
        val scored = candidates.map { pack -> pack to (pack.tags intersect normalized).size }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        if (best == 0 && top.any { it.isBuiltin }) {
            val real = top.filter { !it.isBuiltin }
            if (real.isEmpty()) return top.first { it.isBuiltin }
            return if (random.nextInt(3) == 0) top.first { it.isBuiltin } else real[random.nextInt(real.size)]
        }
        return top[random.nextInt(top.size)]
    }

    private fun load(dir: Path, manifestFile: Path): CoverAssetPack {
        val manifest = ApplicationScope.json.decodeFromString<CoverPackManifest>(manifestFile.readText())
        require(manifest.id.matches(ID_PATTERN)) { "invalid pack id '${manifest.id}'" }
        require(manifest.id != CoverAssetPack.BUILTIN_ID) { "pack id '${manifest.id}' is reserved" }
        return CoverAssetPack(
            manifest = manifest,
            dir = dir,
            backgrounds = resolve(dir, manifest.backgrounds),
            overlays = resolve(dir, manifest.overlays),
            fonts = resolve(dir, manifest.fonts),
        )
    }

    private fun resolve(dir: Path, globs: List<String>): List<Path> {
        if (globs.isEmpty()) return emptyList()
        val matchers = globs.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        return Files.walk(dir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .filter { file -> matchers.any { it.matches(dir.relativize(file)) } }
                .sorted()
                .toList()
        }
    }

    companion object {
        const val MANIFEST_NAME = "pack.json"
        private val ID_PATTERN = Regex("[a-z0-9._-]+")
    }
}
