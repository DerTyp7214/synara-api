package dev.dertyp.services

import dev.dertyp.db.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

class LibraryFileDeleter : Service() {
    private val redisSearchService by inject<RedisSearchService>()

    data class SongRowDeletion(
        val deletedSongs: Int,
        val deletedSongIds: List<UUID>,
        val deletedAlbumIds: List<UUID>,
        val paths: List<String>
    )

    fun deleteSongRows(songIds: Collection<UUID>): SongRowDeletion {
        val chunks = songIds.distinct().chunked(5000)

        val songRows = chunks.flatMap { chunk ->
            SongTable
                .select(SongTable.id, SongTable.filePath)
                .where { SongTable.id inList chunk }
                .map { it[SongTable.id].value to it[SongTable.filePath] }
        }
        val variantPaths = chunks.flatMap { chunk ->
            SongVariantTable
                .select(SongVariantTable.path)
                .where { SongVariantTable.songId inList chunk }
                .map { it[SongVariantTable.path] }
        }
        val paths = songRows.map { it.second } + variantPaths

        logger.info("Found ${paths.size} files to delete.")

        val deletedSongs = chunks.sumOf { chunk ->
            SongTable.deleteWhere { SongTable.id inList chunk }
        }

        logger.info("Deleted $deletedSongs songs from the database")

        val orphanAlbumIds = AlbumTable
            .select(AlbumTable.id)
            .where {
                notExists(
                    SongTable.select(SongTable.id).where {
                        SongTable.albumId eq AlbumTable.id
                    }
                )
            }
            .map { it[AlbumTable.id].value }

        orphanAlbumIds.chunked(5000).forEach { chunk ->
            AlbumTable.deleteWhere { AlbumTable.id inList chunk }
        }

        val deletedSongIds = songRows.map { it.first }.distinct()

        afterCommit {
            deleteFiles(paths)
        }
        removeFromSearchIndex(SearchIndexEntityType.SONG, deletedSongIds)
        removeFromSearchIndex(SearchIndexEntityType.ALBUM, orphanAlbumIds)

        return SongRowDeletion(deletedSongs, deletedSongIds, orphanAlbumIds, paths)
    }

    fun removeFromSearchIndex(type: SearchIndexEntityType, ids: Collection<UUID>) {
        if (ids.isEmpty()) return
        val removed = ids.toList()
        afterCommit {
            if (redisSearchService.isEnabled()) redisSearchService.remove(type, removed)
        }
    }

    fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return

        val albumsPath = get<StorageService>().albumsPath?.let { Paths.get(it) }
        val links = if (albumsPath != null) {
            val fileNames = paths.mapTo(HashSet()) { File(it).nameWithoutExtension }
            albumsPath.toFile().walkTopDown().filter {
                it.toPath().isSymbolicLink() && it.nameWithoutExtension in fileNames
            }.map { it.absolutePath }.toList()
        } else emptyList()

        for (path in paths + links) {
            val file = File(path)
            if (file.toPath().isSymbolicLink())
                logger.info(
                    "File is a symbolic link pointing to: ${
                        file.toPath().readSymbolicLink().absolutePathString()
                    } (${file.delete()})"
                )
            if (file.exists())
                logger.info("Trying to delete ${file.absolutePath} (${file.delete()})")
            val parent = file.parentFile
            if (parent != null && parent.exists() && parent.list().isNullOrEmpty())
                logger.info("Trying to delete parent ${parent.absolutePath} (${parent.delete()})")
        }

        get<StorageService>().invalidate(StorageCategory.TOTAL)
    }

    private fun afterCommit(action: () -> Unit) {
        val transaction = TransactionManager.currentOrNull()
        if (transaction == null) {
            runSafely(action)
            return
        }
        transaction.registerInterceptor(object : StatementInterceptor {
            override fun afterCommit(transaction: Transaction) {
                runSafely(action)
            }
        })
    }

    private fun runSafely(action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Post-commit cleanup failed: ${e.message}")
        }
    }
}
