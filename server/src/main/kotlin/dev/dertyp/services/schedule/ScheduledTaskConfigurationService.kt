package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TaskKeys
import dev.dertyp.data.TriggerDefinition
import dev.dertyp.db.ScheduledTaskConfigurationTable
import dev.dertyp.dbQuery
import dev.dertyp.services.IScheduledTaskConfigurationService
import dev.dertyp.services.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class RpcScheduledTaskConfigurationService(
    private val configService: ScheduledTaskConfigurationService,
    private val scheduleService: ScheduleService,
) : IScheduledTaskConfigurationService {
    override suspend fun getConfigurations(): List<TaskConfiguration> {
        return configService.getConfigurations()
    }

    override suspend fun updateConfiguration(configuration: TaskConfiguration) {
        configService.updateConfiguration(configuration)
    }

    override fun getConfigurationsFlow(): Flow<List<TaskConfiguration>> {
        return configService.configurationsFlow
    }

    override suspend fun triggerTask(key: String): Boolean {
        return scheduleService.triggerTask(key)
    }
}

class ScheduledTaskConfigurationService : Service() {
    companion object {
        val DEFAULTS = listOf(
            TaskConfiguration(TaskKeys.REVERSE_PROXY_HEALTH_CHECK, "Reverse Proxy Health Check", true, TriggerDefinition.Cron("0 * * * *")),
            TaskConfiguration(TaskKeys.DATABASE_BACKUP, "Database Backup", true, TriggerDefinition.Cron("0 2 * * *")),
            TaskConfiguration(TaskKeys.USER_PLAYLIST_BACKUP, "User Playlist Backup", true, TriggerDefinition.Cron("0 2 * * *")),
            TaskConfiguration(TaskKeys.SESSION_CLEANUP, "Session Cleanup", true, TriggerDefinition.Cron("0 0 * * *")),
            TaskConfiguration(TaskKeys.MERGE_LIBRARY_DUPLICATES, "Merge Library Duplicates", true, TriggerDefinition.Cron("0 1 * * *")),
            TaskConfiguration(TaskKeys.AUDIO_ANALYSIS, "Audio Analysis", true, TriggerDefinition.Cron("0 3 * * *")),
            TaskConfiguration(TaskKeys.FLAC_ANALYSIS, "FLAC Analysis", true, TriggerDefinition.Cron("0 5 * * *")),
            TaskConfiguration(TaskKeys.MUSICBRAINZ_WORKER, "MusicBrainz Worker", true, TriggerDefinition.Cron("0 0 * * *")),
            TaskConfiguration(TaskKeys.MUSICBRAINZ_CACHE_WORKER, "MusicBrainz Cache Worker", true, TriggerDefinition.AfterTask(TaskKeys.MUSICBRAINZ_WORKER)),
            TaskConfiguration(TaskKeys.GENRE_METADATA_WORKER, "Genre Metadata Worker", true, TriggerDefinition.AfterTask(TaskKeys.MUSICBRAINZ_WORKER)),
            TaskConfiguration(TaskKeys.ARTIST_IMAGE_WORKER, "Artist Image Worker", true, TriggerDefinition.AfterTask(TaskKeys.GENRE_METADATA_WORKER)),
            TaskConfiguration(TaskKeys.FETCH_METADATA_THEAUDIODB, "Fetch Metadata (TheAudioDB)", true, TriggerDefinition.AfterTask(TaskKeys.ARTIST_IMAGE_WORKER)),
            TaskConfiguration(TaskKeys.AUTO_TRANSCODING, "Auto Transcoding", true, TriggerDefinition.Cron("0 3 * * *")),
            TaskConfiguration(TaskKeys.LYRICS_SYNC_WORKER, "Lyrics Sync Worker", false, TriggerDefinition.Cron("0 4 * * *")),
            TaskConfiguration(TaskKeys.LRCLIB_WORKER, "LrcLib Worker", true, TriggerDefinition.Cron("30 4 * * *")),
            TaskConfiguration(TaskKeys.RECENT_RELEASE_WORKER, "Recent Release Worker", true, TriggerDefinition.Cron("0 1 * * *")),
            TaskConfiguration(TaskKeys.PROVIDER_ENRICHMENT_WORKER, "Provider Enrichment Worker", true, TriggerDefinition.AfterTask(TaskKeys.RECENT_RELEASE_WORKER)),
            TaskConfiguration(TaskKeys.DELETE_EMPTY_ALBUMS, "Delete Empty Albums", true, TriggerDefinition.Cron("0 0 * * *")),
            TaskConfiguration(TaskKeys.DELETE_UNREFERENCED_ARTISTS, "Delete Unreferenced Artists", true, TriggerDefinition.AfterTask(TaskKeys.DELETE_EMPTY_ALBUMS)),
            TaskConfiguration(TaskKeys.DELETE_UNREFERENCED_IMAGES, "Delete Unreferenced Images", true, TriggerDefinition.AfterTask(TaskKeys.DELETE_UNREFERENCED_ARTISTS)),
            TaskConfiguration(TaskKeys.IMAGE_ANALYSIS, "Image Analysis", true, TriggerDefinition.AfterTask(TaskKeys.DELETE_UNREFERENCED_IMAGES)),
            TaskConfiguration(TaskKeys.LOG_CLEANUP_WORKER, "Log Cleanup Worker", true, TriggerDefinition.Cron("0 0 * * *")),
            TaskConfiguration(TaskKeys.SEARCH_INDEX_REBUILD_WORKER, "Search Index Rebuild Worker", true, TriggerDefinition.Manual)
        )
    }

    private val _configurationsFlow = MutableSharedFlow<List<TaskConfiguration>>(replay = 1)
    val configurationsFlow: Flow<List<TaskConfiguration>> = _configurationsFlow.onStart {
        emit(getConfigurations())
    }

    suspend fun getConfigurations(): List<TaskConfiguration> = dbQuery {
        ScheduledTaskConfigurationTable.selectAll().map {
            TaskConfiguration(
                key = it[ScheduledTaskConfigurationTable.id].value,
                name = it[ScheduledTaskConfigurationTable.name],
                enabled = it[ScheduledTaskConfigurationTable.enabled],
                trigger = ApplicationScope.json.decodeFromString<TriggerDefinition>(it[ScheduledTaskConfigurationTable.trigger])
            )
        }
    }

    suspend fun updateConfiguration(configuration: TaskConfiguration) {
        dbQuery {
            ScheduledTaskConfigurationTable.upsert(ScheduledTaskConfigurationTable.id) {
                it[id] = configuration.key
                it[name] = configuration.name
                it[enabled] = configuration.enabled
                it[trigger] = ApplicationScope.json.encodeToString(configuration.trigger)
            }
        }
        _configurationsFlow.emit(getConfigurations())
    }

    suspend fun ensureDefaults(defaults: List<TaskConfiguration>) {
        val existing = getConfigurations().map { it.key }.toSet()
        defaults.filter { it.key !in existing }.forEach {
            updateConfiguration(it)
        }
    }
}
