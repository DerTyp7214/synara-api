package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.services.SongService
import kotlinx.coroutines.flow.toList
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.koin.core.component.inject
import java.io.File

@Migration("1.0")
class TagMusicBrainzIds : CustomMigration() {
    private val songService by inject<SongService>()

    override suspend fun migrate() {
        val songs = songService.allSongsFlow().toList()
        
        songs.forEach { song ->
            if (song.musicBrainzId != null && song.path.endsWith(".flac", true)) {
                try {
                    val file = AudioFileIO.read(File(song.path))
                    file.tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, song.musicBrainzId)
                    file.commit()
                } catch (e: Exception) {
                    logger.error("Failed to tag ${song.path}: ${e.message}")
                }
            }
        }
    }
}
