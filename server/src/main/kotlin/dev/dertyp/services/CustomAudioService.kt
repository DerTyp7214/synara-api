package dev.dertyp.services

import dev.dertyp.AudioUtils
import dev.dertyp.Indexer
import dev.dertyp.audio.AudioConfig
import dev.dertyp.audio.LosslessFormat
import dev.dertyp.data.CustomMetadata
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.*

class CustomAudioRpcService(
    private val customAudioService: CustomAudioService
) : ICustomAudioService {
    override suspend fun uploadCustomAudio(
        @LogParam("size") fileData: ByteArray,
        fileName: String,
        metadata: CustomMetadata?
    ): UUID? {
        return customAudioService.uploadCustomAudio(fileData, fileName, metadata)
    }
}

class CustomAudioService(
    private val indexer: Indexer,
    private val storageService: StorageService,
    private val audioConfig: AudioConfig = AudioConfig(),
) : Service() {

    init {
        File(storageService.customAudioPath).mkdirs()
    }

    suspend fun uploadCustomAudio(
        fileData: ByteArray,
        fileName: String,
        metadata: CustomMetadata?
    ): UUID? = withContext(Dispatchers.IO) {
        logger.info("Starting custom audio upload for file: $fileName (${fileData.size} bytes)")
        val uuid = UUID.randomUUID()
        val tempFile = File.createTempFile("upload-", fileName.split("/").last())
        tempFile.writeBytes(fileData)

        File(storageService.customAudioPath).mkdirs()

        val targetFormat = audioConfig.losslessFormat
        val targetFile = File(storageService.customAudioPath, "$uuid.${targetFormat.extension}")
        logger.info("Target file path: ${targetFile.absolutePath}")

        try {
            val sourceFormat = detectLosslessFormat(tempFile)
            if (sourceFormat != targetFormat) {
                logger.info("File is ${sourceFormat ?: "not lossless"}, converting to $targetFormat...")
                AudioUtils.convertLossless(tempFile, targetFile, targetFormat)
                logger.info("Conversion successful.")
            } else {
                logger.info("File is already $targetFormat, copying...")
                tempFile.copyTo(targetFile, overwrite = true)
                logger.info("Copy successful.")
            }

            if (metadata != null) {
                logger.info("Writing metadata...")
                try {
                    val audioFile = AudioFileIO.read(targetFile)
                    val tag = audioFile.tagOrCreateAndSetDefault

                    metadata.title?.let { tag.setField(FieldKey.TITLE, it) }
                    metadata.artists?.let {
                        tag.deleteField(FieldKey.ARTIST)
                        it.forEach { artist -> tag.addField(FieldKey.ARTIST, artist) }
                    }
                    metadata.album?.let { tag.setField(FieldKey.ALBUM, it) }
                    metadata.year?.let { tag.setField(FieldKey.YEAR, it) }
                    metadata.genre?.let { tag.setField(FieldKey.GENRE, it) }
                    metadata.coverData?.let {
                        logger.info("Writing cover art (${it.size} bytes)...")
                        val artwork = ArtworkFactory.getNew()
                        artwork.binaryData = it
                        tag.deleteArtworkField()
                        tag.setField(artwork)
                    }

                    audioFile.commit()
                    logger.info("Metadata written successfully.")
                } catch (e: Exception) {
                    logger.error("Failed to write metadata: ${e.message}", e)
                }
            }

            storageService.invalidate(StorageCategory.TOTAL)

            logger.info("Queueing file for indexing...")
            indexer.queue(
                songPaths = listOf(targetFile.toPath()),
                playlistPaths = emptyList(),
                stdout = { logger.info(it) }
            ).await()

            return@withContext uuid
        } catch (e: Exception) {
            logger.error("Failed to process custom audio upload", e)
            targetFile.delete()
            return@withContext null
        } finally {
            tempFile.delete()
        }
    }

    private fun detectLosslessFormat(file: File): LosslessFormat? {
        return try {
            val grabber = FFmpegFrameGrabber(file)
            grabber.start()
            val format = LosslessFormat.fromFfmpegFormat(grabber.format)
            grabber.stop()
            grabber.release()
            format
        } catch (e: Exception) {
            logger.warn("Failed to detect audio format: ${e.message}")
            null
        }
    }
}
