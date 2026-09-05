package dev.dertyp.services.cover

import dev.dertyp.data.CoverGenerationOptions
import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverInfo
import dev.dertyp.data.CoverStyle
import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.data.ImageSource
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.JobStatus
import dev.dertyp.services.ImageService
import dev.dertyp.services.Service
import dev.dertyp.services.cover.render.CoverRenderSpec
import dev.dertyp.services.cover.render.CoverRenderer
import dev.dertyp.services.cover.render.CoverTypography
import dev.dertyp.services.jobs.JobService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import javax.imageio.ImageIO

class CoverGenerationService(
    private val imageService: ImageService,
    private val packService: CoverAssetPackService,
    private val collector: CoverSourceCollector,
    private val jobService: JobService,
    private val config: CoverConfig,
) : Service() {
    data class RenderedBytes(val bytes: ByteArray, val style: CoverStyle, val seed: Long)

    data class TargetRow(
        val target: CoverTarget,
        val name: String,
        val creator: UUID,
        val imageId: UUID?,
        val imageSource: ImageSource?,
        val coverStyle: CoverStyle?,
        val coverSeed: Long?,
    )

    override suspend fun startService() {
        imageService.generatedImageRecoverer = { recover(it) }
    }

    fun options(): CoverGenerationOptions = CoverGenerationOptions(
        styles = CoverStyle.entries,
        packs = packService.packs(includeNsfw = config.nsfwPacks).map { it.info() },
        nsfwEnabled = config.nsfwPacks,
    )

    val nsfwEnabled: Boolean get() = config.nsfwPacks

    suspend fun row(target: CoverTarget): TargetRow? = dbQuery {
        when (target.type) {
            CoverTargetType.PLAYLIST -> UserPlaylistTable
                .select(UserPlaylistTable.name, UserPlaylistTable.creator, UserPlaylistTable.imageId, UserPlaylistTable.imageSource, UserPlaylistTable.coverStyle, UserPlaylistTable.coverSeed)
                .where { UserPlaylistTable.id eq target.id }
                .singleOrNull()?.let {
                    TargetRow(
                        target, it[UserPlaylistTable.name], it[UserPlaylistTable.creator].value, it[UserPlaylistTable.imageId]?.value,
                        it[UserPlaylistTable.imageSource], it[UserPlaylistTable.coverStyle], it[UserPlaylistTable.coverSeed],
                    )
                }
            CoverTargetType.COLLECTION -> CollectionTable
                .select(CollectionTable.name, CollectionTable.creator, CollectionTable.imageId, CollectionTable.imageSource, CollectionTable.coverStyle, CollectionTable.coverSeed)
                .where { CollectionTable.id eq target.id }
                .singleOrNull()?.let {
                    TargetRow(
                        target, it[CollectionTable.name], it[CollectionTable.creator].value, it[CollectionTable.imageId]?.value,
                        it[CollectionTable.imageSource], it[CollectionTable.coverStyle], it[CollectionTable.coverSeed],
                    )
                }
        }
    }

    suspend fun coverInfo(target: CoverTarget): CoverInfo {
        val row = row(target) ?: throw IllegalArgumentException("Unknown ${target.type.name.lowercase()} ${target.id}")
        return CoverInfo(target, row.imageId, row.imageSource, row.coverStyle, row.coverSeed)
    }

    suspend fun render(target: CoverTarget, params: CoverGenerationParams): RenderedBytes {
        val context = collector.collect(target) ?: throw IllegalArgumentException("Unknown ${target.type.name.lowercase()} ${target.id}")
        val seed = params.seed ?: stableSeed(target, context.coverImageIds)
        val pack = packService.select(CoverTagDeriver.tags(context), seed, params.allowNsfw, params.pack)
        val random = Random(seed xor PACK_SALT)

        val tiles = ArrayList<BufferedImage>()
        for (imageId in context.coverImageIds.take(CoverSourceCollector.MAX_TILES)) {
            val bytes = runCatching { imageService.getImageData(imageId, TILE_SIZE) }.getOrNull() ?: continue
            decode(bytes)?.let(tiles::add)
        }

        return withContext(Dispatchers.IO) {
            val background = pack.backgrounds.takeIf { it.isNotEmpty() }?.let { readImage(it[random.nextInt(it.size)]) }
            val overlay = pack.overlays.takeIf { it.isNotEmpty() && random.nextInt(10) < 7 }?.let { readImage(it[random.nextInt(it.size)]) }
            val font = pack.fonts.firstOrNull()?.let { readFont(it) }
            val spec = CoverRenderSpec(
                seed = seed,
                style = params.style,
                tiles = tiles,
                palette = context.palette,
                background = background,
                overlay = overlay,
                title = context.title.takeIf { params.includeTitle },
                font = font,
            )
            val rendered = CoverRenderer.render(spec)
            RenderedBytes(CoverRenderer.encodeJpeg(rendered.image), rendered.style, seed)
        }
    }

    suspend fun preview(target: CoverTarget, params: CoverGenerationParams): ByteArray = render(target, params).bytes

    suspend fun apply(target: CoverTarget, params: CoverGenerationParams): UUID {
        val rendered = render(target, params)
        val imageId = imageService.createImage(rendered.bytes, originOf(target))
        dbQuery {
            when (target.type) {
                CoverTargetType.PLAYLIST -> UserPlaylistTable.update({ UserPlaylistTable.id eq target.id }) {
                    it[UserPlaylistTable.imageId] = EntityID(imageId, ImageTable)
                    it[imageSource] = ImageSource.GENERATED
                    it[coverStyle] = params.style
                    it[coverSeed] = params.seed
                }
                CoverTargetType.COLLECTION -> CollectionTable.update({ CollectionTable.id eq target.id }) {
                    it[CollectionTable.imageId] = EntityID(imageId, ImageTable)
                    it[imageSource] = ImageSource.GENERATED
                    it[coverStyle] = params.style
                    it[coverSeed] = params.seed
                }
            }
        }
        return imageId
    }

    suspend fun autoGenerate(target: CoverTarget): UUID? {
        if (!config.autoGenerate) return null
        val row = row(target) ?: return null
        if (row.imageSource == ImageSource.USER) return null
        return apply(target, CoverGenerationParams(style = row.coverStyle ?: CoverStyle.AUTO, seed = row.coverSeed))
    }

    suspend fun reset(target: CoverTarget): Boolean {
        val row = row(target) ?: return false
        if (row.imageSource != ImageSource.USER) return false
        dbQuery {
            when (target.type) {
                CoverTargetType.PLAYLIST -> UserPlaylistTable.update({ UserPlaylistTable.id eq target.id }) {
                    it[imageId] = null
                    it[imageSource] = null
                    it[coverStyle] = null
                    it[coverSeed] = null
                }
                CoverTargetType.COLLECTION -> CollectionTable.update({ CollectionTable.id eq target.id }) {
                    it[imageId] = null
                    it[imageSource] = null
                    it[coverStyle] = null
                    it[coverSeed] = null
                }
            }
        }
        enqueueAuto(target, row.name, row.creator)
        return true
    }

    fun enqueueAuto(target: CoverTarget, title: String, user: UUID?): JobService.Job? {
        if (!config.autoGenerate) return null
        val pending = jobService.jobsOf(JOB_KIND).firstOrNull { it.payload == target && it.info.status == JobStatus.PENDING }
        if (pending != null) return pending
        return jobService.enqueue(JOB_KIND, title, user, target.type.name.lowercase(), payload = target) {
            log("Generating cover for ${target.type.name.lowercase()} $title")
            val imageId = autoGenerate(target)
            log(if (imageId != null) "Cover $imageId set" else "Skipped: cover is user-set or generation disabled")
        }
    }

    fun enqueueMissing(user: UUID?, params: CoverGenerationParams): JobService.Job =
        jobService.enqueue(JOB_KIND, "Generate missing covers", user, user?.toString() ?: "all users", payload = user) {
            val targets = missingTargets(user)
            log("${targets.size} playlist(s)/collection(s) without cover")
            targets.forEachIndexed { index, target ->
                if (!isActive()) return@enqueue
                progress(index.toDouble() / targets.size, "${index + 1}/${targets.size}")
                runCatching { apply(target, params.copy(allowNsfw = params.allowNsfw)) }
                    .onFailure { log("Failed ${target.type.name.lowercase()} ${target.id}: ${it.message}") }
            }
            progress(1.0, "done")
        }

    suspend fun missingTargets(creator: UUID?): List<CoverTarget> = dbQuery {
        val playlists = UserPlaylistTable.select(UserPlaylistTable.id)
            .where { UserPlaylistTable.imageId.isNull() }
            .apply { if (creator != null) andWhere { UserPlaylistTable.creator eq creator } }
            .map { CoverTarget(CoverTargetType.PLAYLIST, it[UserPlaylistTable.id].value) }
        val collections = CollectionTable.select(CollectionTable.id)
            .where { CollectionTable.imageId.isNull() }
            .apply { if (creator != null) andWhere { CollectionTable.creator eq creator } }
            .map { CoverTarget(CoverTargetType.COLLECTION, it[CollectionTable.id].value) }
        playlists + collections
    }

    private suspend fun recover(origin: String): ByteArray? {
        val target = parseOrigin(origin) ?: return null
        val row = row(target) ?: return null
        return runCatching {
            render(target, CoverGenerationParams(style = row.coverStyle ?: CoverStyle.AUTO, seed = row.coverSeed)).bytes
        }.onFailure { logger.warn("Could not regenerate cover for $origin: ${it.message}") }.getOrNull()
    }

    private fun decode(bytes: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()

    private fun readImage(path: Path): BufferedImage? =
        runCatching { ImageIO.read(path.toFile()) }
            .onFailure { logger.warn("Could not read cover asset $path: ${it.message}") }
            .getOrNull()

    private fun readFont(path: Path): Font? =
        runCatching { Font.createFont(Font.TRUETYPE_FONT, path.toFile()) }
            .onFailure { logger.warn("Could not read cover font $path: ${it.message}") }
            .getOrNull() ?: CoverTypography.bundledFont

    companion object {
        const val JOB_KIND = "cover"
        private const val TILE_SIZE = 512
        private const val PACK_SALT = 0x5EEDL

        fun originOf(target: CoverTarget): String =
            "${ImageService.GENERATED_ORIGIN_PREFIX}${target.type.name.lowercase()}:${target.id}"

        fun parseOrigin(origin: String): CoverTarget? {
            val body = origin.removePrefix(ImageService.GENERATED_ORIGIN_PREFIX)
            val (typeName, id) = body.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
            val type = CoverTargetType.entries.firstOrNull { it.name.equals(typeName, ignoreCase = true) } ?: return null
            return runCatching { CoverTarget(type, UUID.fromString(id)) }.getOrNull()
        }

        fun stableSeed(target: CoverTarget, coverIds: List<UUID>): Long {
            var seed = target.id.mostSignificantBits xor target.id.leastSignificantBits
            for (id in coverIds) {
                seed = seed * 31 + (id.mostSignificantBits xor id.leastSignificantBits)
            }
            return seed
        }
    }
}
