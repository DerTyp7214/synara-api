package dev.dertyp.services

import dev.dertyp.Indexer
import dev.dertyp.data.CustomMetadata
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
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
    private val storageService: StorageService
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

        val targetFile = File(storageService.customAudioPath, "$uuid.flac")
        logger.info("Target file path: ${targetFile.absolutePath}")

        try {
            if (!isFlac(tempFile)) {
                logger.info("File is not FLAC, converting...")
                convert(tempFile, targetFile)
                logger.info("Conversion successful.")
            } else {
                logger.info("File is FLAC, copying...")
                tempFile.copyTo(targetFile, overwrite = true)
                logger.info("Copy successful.")
            }

            if (metadata != null) {
                logger.info("Writing metadata...")
                try {
                    val audioFile = AudioFileIO.read(targetFile)
                    val tag = audioFile.tag

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

    private fun isFlac(file: File): Boolean {
        return try {
            val grabber = FFmpegFrameGrabber(file)
            grabber.start()
            val isFlac = grabber.format == "flac"
            grabber.stop()
            grabber.release()
            isFlac
        } catch (e: Exception) {
            logger.warn("Failed to check if file is FLAC: ${e.message}")
            false
        }
    }

    private fun convert(inputFile: File, outputFile: File) {
        val grabber = FFmpegFrameGrabber(inputFile)
        grabber.start()

        val inputMetadata: Map<String, String> = grabber.metadata.toMap()

        val recorder = FFmpegFrameRecorder(outputFile, grabber.audioChannels)
        recorder.format = "flac"
        recorder.sampleRate = grabber.sampleRate
        recorder.audioBitrate = grabber.audioBitrate
        recorder.audioCodec = avcodec.AV_CODEC_ID_FLAC

        inputMetadata.forEach { (key, value) ->
            recorder.setMetadata(key.lowercase(), value)
        }
        
        recorder.start()

        var frame: Frame?
        while (grabber.grabFrame().also { frame = it } != null) {
            if (frame?.samples != null) {
                recorder.record(frame)
            }
        }

        recorder.stop()
        recorder.release()
        grabber.stop()
        grabber.release()
    }
}
