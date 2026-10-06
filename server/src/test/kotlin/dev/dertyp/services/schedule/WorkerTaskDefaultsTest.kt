package dev.dertyp.services.schedule

import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TaskKeys
import dev.dertyp.data.TriggerDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkerTaskDefaultsTest {

    private val legacyDefaults = listOf(
        TaskConfiguration(
            TaskKeys.REVERSE_PROXY_HEALTH_CHECK,
            "Reverse Proxy Health Check",
            true,
            TriggerDefinition.Cron("0 * * * *")
        ),
        TaskConfiguration(TaskKeys.DATABASE_BACKUP, "Database Backup", true, TriggerDefinition.Cron("0 2 * * *")),
        TaskConfiguration(
            TaskKeys.USER_PLAYLIST_BACKUP,
            "User Playlist Backup",
            true,
            TriggerDefinition.Cron("0 2 * * *")
        ),
        TaskConfiguration(TaskKeys.SESSION_CLEANUP, "Session Cleanup", true, TriggerDefinition.Cron("0 0 * * *")),
        TaskConfiguration(TaskKeys.QUEUE_CLEANUP, "Queue Cleanup", true, TriggerDefinition.Cron("30 0 * * *")),
        TaskConfiguration(
            TaskKeys.CLIENT_SETTINGS_CLEANUP,
            "Client Settings Cleanup",
            true,
            TriggerDefinition.Cron("45 0 * * *")
        ),
        TaskConfiguration(
            TaskKeys.MERGE_LIBRARY_DUPLICATES,
            "Merge Library Duplicates",
            true,
            TriggerDefinition.Cron("0 1 * * *")
        ),
        TaskConfiguration(TaskKeys.AUDIO_ANALYSIS, "Audio Analysis", true, TriggerDefinition.Cron("0 3 * * *")),
        TaskConfiguration(TaskKeys.FLAC_ANALYSIS, "FLAC Analysis", true, TriggerDefinition.Cron("0 5 * * *")),
        TaskConfiguration(TaskKeys.PCM_ANALYSIS, "WAV/AIFF Analysis", true, TriggerDefinition.Cron("20 5 * * *")),
        TaskConfiguration(
            TaskKeys.AUDIO_START_ANALYSIS,
            "Audio Start Analysis",
            true,
            TriggerDefinition.Cron("40 5 * * *")
        ),
        TaskConfiguration(TaskKeys.MUSICBRAINZ_WORKER, "MusicBrainz Worker", true, TriggerDefinition.Cron("0 0 * * *")),
        TaskConfiguration(
            TaskKeys.MUSICBRAINZ_CACHE_WORKER,
            "MusicBrainz Cache Worker",
            true,
            TriggerDefinition.AfterTask(TaskKeys.MUSICBRAINZ_WORKER)
        ),
        TaskConfiguration(
            TaskKeys.GENRE_METADATA_WORKER,
            "Genre Metadata Worker",
            true,
            TriggerDefinition.AfterTask(TaskKeys.MUSICBRAINZ_WORKER)
        ),
        TaskConfiguration(
            TaskKeys.ARTIST_IMAGE_WORKER,
            "Artist Image Worker",
            true,
            TriggerDefinition.AfterTask(TaskKeys.GENRE_METADATA_WORKER)
        ),
        TaskConfiguration(
            TaskKeys.FETCH_METADATA_THEAUDIODB,
            "Fetch Metadata (TheAudioDB)",
            true,
            TriggerDefinition.AfterTask(TaskKeys.ARTIST_IMAGE_WORKER)
        ),
        TaskConfiguration(TaskKeys.AUTO_TRANSCODING, "Auto Transcoding", true, TriggerDefinition.Cron("0 3 * * *")),
        TaskConfiguration(
            TaskKeys.LYRICS_SYNC_WORKER,
            "Lyrics Sync Worker",
            false,
            TriggerDefinition.Cron("0 4 * * *")
        ),
        TaskConfiguration(TaskKeys.LRCLIB_WORKER, "LrcLib Worker", true, TriggerDefinition.Cron("30 4 * * *")),
        TaskConfiguration(
            TaskKeys.RECENT_RELEASE_WORKER,
            "Recent Release Worker",
            true,
            TriggerDefinition.Cron("0 1 * * *")
        ),
        TaskConfiguration(
            TaskKeys.APPLE_MUSIC_RELEASE_WORKER,
            "Apple Music Release Worker",
            true,
            TriggerDefinition.Cron("0 6 * * *")
        ),
        TaskConfiguration(
            TaskKeys.PROVIDER_ENRICHMENT_WORKER,
            "Provider Enrichment Worker",
            true,
            TriggerDefinition.AfterTask(TaskKeys.RECENT_RELEASE_WORKER)
        ),
        TaskConfiguration(
            TaskKeys.ISRC_PROVIDER_ENRICHMENT_WORKER,
            "ISRC/Barcode Provider Enrichment Worker",
            true,
            TriggerDefinition.AfterTask(TaskKeys.MUSICBRAINZ_CACHE_WORKER)
        ),
        TaskConfiguration(
            TaskKeys.DELETE_EMPTY_ALBUMS,
            "Delete Empty Albums",
            true,
            TriggerDefinition.Cron("0 0 * * *")
        ),
        TaskConfiguration(
            TaskKeys.DELETE_UNREFERENCED_ARTISTS,
            "Delete Unreferenced Artists",
            true,
            TriggerDefinition.AfterTask(TaskKeys.DELETE_EMPTY_ALBUMS)
        ),
        TaskConfiguration(
            TaskKeys.DELETE_UNREFERENCED_IMAGES,
            "Delete Unreferenced Images",
            true,
            TriggerDefinition.AfterTask(TaskKeys.DELETE_UNREFERENCED_ARTISTS)
        ),
        TaskConfiguration(
            TaskKeys.IMAGE_ANALYSIS,
            "Image Analysis",
            true,
            TriggerDefinition.AfterTask(TaskKeys.DELETE_UNREFERENCED_IMAGES)
        ),
        TaskConfiguration(TaskKeys.LOG_CLEANUP_WORKER, "Log Cleanup Worker", true, TriggerDefinition.Cron("0 0 * * *")),
        TaskConfiguration(
            TaskKeys.ENTITY_CHANGE_CLEANUP_WORKER,
            "Entity Change Cleanup Worker",
            true,
            TriggerDefinition.Cron("15 0 * * *")
        ),
        TaskConfiguration(
            TaskKeys.SEARCH_INDEX_REBUILD_WORKER,
            "Search Index Rebuild Worker",
            true,
            TriggerDefinition.Manual
        ),
        TaskConfiguration(TaskKeys.LISTENBRAINZ_SYNC, "ListenBrainz Sync", true, TriggerDefinition.Cron("0 * * * *")),
        TaskConfiguration(TaskKeys.LISTEN_BACKUP, "Listen Backup", true, TriggerDefinition.Cron("30 * * * *")),
        TaskConfiguration(
            TaskKeys.AUDIO_EMBEDDING,
            "Audio Embedding",
            true,
            TriggerDefinition.AfterTask(TaskKeys.AUDIO_ANALYSIS)
        ),
        TaskConfiguration(
            TaskKeys.AUDIO_TIMELINE_BACKFILL,
            "Audio Timeline Backfill",
            true,
            TriggerDefinition.AfterTask(TaskKeys.AUDIO_ANALYSIS)
        ),
        TaskConfiguration(
            TaskKeys.COVER_BACKFILL,
            "Cover Backfill",
            true,
            TriggerDefinition.AfterTask(TaskKeys.IMAGE_ANALYSIS)
        ),
        TaskConfiguration(
            TaskKeys.RECOMMENDATION_TRAINING,
            "Recommendation Model Training",
            true,
            TriggerDefinition.Cron("0 7 * * *")
        ),
        TaskConfiguration(
            TaskKeys.RADIO_SESSION_CLEANUP,
            "Radio Session Cleanup",
            true,
            TriggerDefinition.Cron("0 * * * *")
        ),
        TaskConfiguration(
            TaskKeys.STORAGE_SIZE_REFRESH,
            "Storage Size Refresh",
            true,
            TriggerDefinition.Cron("0 */6 * * *")
        ),
        TaskConfiguration(TaskKeys.PODCAST_REFRESH, "Podcast Refresh", true, TriggerDefinition.Cron("15 * * * *")),
        TaskConfiguration(TaskKeys.PODCAST_IMPORT, "Podcast Import", true, TriggerDefinition.Cron("*/15 * * * *"))
    )

    private val animatedImagesDefault = TaskConfiguration(
        TaskKeys.DELETE_UNREFERENCED_ANIMATED_IMAGES,
        "Delete Unreferenced Animated Images",
        true,
        TriggerDefinition.AfterTask(TaskKeys.DELETE_UNREFERENCED_ARTISTS)
    )

    @Test
    fun `generated defaults reproduce every legacy default and add the animated images task`() {
        val generated = ScheduledTaskConfigurationService.DEFAULTS.associateBy { it.key }

        assertEquals(legacyDefaults.size + 1, generated.size)
        assertEquals(ScheduledTaskConfigurationService.DEFAULTS.size, generated.size)
        legacyDefaults.forEach { legacy ->
            assertEquals(legacy, generated[legacy.key], "Default for ${legacy.key}")
        }
        assertEquals(animatedImagesDefault, generated[TaskKeys.DELETE_UNREFERENCED_ANIMATED_IMAGES])
    }

    @Test
    fun `animated images default matches the unreferenced images default`() {
        val generated = ScheduledTaskConfigurationService.DEFAULTS.associateBy { it.key }
        val images = generated.getValue(TaskKeys.DELETE_UNREFERENCED_IMAGES)
        val animated = generated.getValue(TaskKeys.DELETE_UNREFERENCED_ANIMATED_IMAGES)

        assertEquals(images.enabled, animated.enabled)
        assertEquals(images.trigger, animated.trigger)
    }

    @Test
    fun `every registered worker class has exactly one default`() {
        val keys = WorkerTasks.workerClasses.map { it.getAnnotation(WorkerTask::class.java).key }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.toSet(), ScheduledTaskConfigurationService.DEFAULTS.map { it.key }.toSet())
    }

    @Test
    fun `annotation without a trigger defaults to manual`() {
        assertEquals(TriggerDefinition.Manual, WorkerTask("key", "Name").defaultTrigger())
    }

    @Test
    fun `annotation maps each trigger field to its trigger definition`() {
        assertEquals(
            TriggerDefinition.Cron("0 1 * * *"),
            WorkerTask("key", "Name", cron = "0 1 * * *").defaultTrigger()
        )
        assertEquals(TriggerDefinition.Interval(60), WorkerTask("key", "Name", intervalSeconds = 60).defaultTrigger())
        assertEquals(
            TriggerDefinition.AfterTask("other"),
            WorkerTask("key", "Name", afterTask = "other").defaultTrigger()
        )
    }

    @Test
    fun `annotation with more than one trigger is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkerTask("key", "Name", cron = "0 1 * * *", afterTask = "other").defaultTrigger()
        }
    }
}
