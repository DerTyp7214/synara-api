package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.EntityType
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserAlbumTable
import dev.dertyp.db.UserTable
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.release.AppleMusicReleaseService
import dev.dertyp.services.release.ProviderLinkService
import dev.dertyp.services.subsonic.SubsonicQueryService
import dev.dertyp.testing.RecordedChange
import dev.dertyp.testing.recordedChanges
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.AfterEach
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.util.UUID

abstract class EntityChangeContentTest : EntityChangeLibraryTest() {
    protected lateinit var userPlaylistService: UserPlaylistService
    protected lateinit var playlistService: PlaylistService
    protected lateinit var collectionService: CollectionService
    protected lateinit var timecodeTagService: TimecodeTagService
    protected lateinit var subsonicQueryService: SubsonicQueryService
    protected lateinit var releaseService: ReleaseService
    protected lateinit var userService: UserService
    protected lateinit var stranger: UUID

    private val containerTypes = setOf(EntityType.USER_PLAYLIST, EntityType.PLAYLIST, EntityType.COLLECTION)

    protected fun setupContent(dialect: DbDialect) {
        setup(dialect)
        loadKoinModules(module {
            single<HookBus> { HookService() }
            single { mockk<AppleMusicReleaseService>(relaxed = true) }
            single { mockk<ProviderLinkService>(relaxed = true) }
            single { UserPlaylistService() }
            single { PlaylistService() }
            single { CollectionService() }
            single { TimecodeTagService() }
            single { SubsonicQueryService() }
            single { ReleaseService(get()) }
            single { UserService() }
        })
        db {
            SchemaUtils.create(UserAlbumTable)
            stranger = UserTable.insertAndGetId {
                it[username] = "stranger"
                it[passwordHash] = "hash"
            }.value
        }
        userPlaylistService = getKoin().get()
        playlistService = getKoin().get()
        collectionService = getKoin().get()
        timecodeTagService = getKoin().get()
        subsonicQueryService = getKoin().get()
        releaseService = getKoin().get()
        userService = getKoin().get()
    }

    @AfterEach
    fun stopContentServices() {
        if (::releaseService.isInitialized) {
            runBlocking {
                userPlaylistService.stopService()
                playlistService.stopService()
                collectionService.stopService()
                timecodeTagService.stopService()
                subsonicQueryService.stopService()
                releaseService.stopService()
                userService.stopService()
            }
        }
    }

    protected fun picture(): UUID = db {
        ImageTable.insertAndGetId {
            it[path] = "${UUID.randomUUID()}.jpg"
            it[imageHash] = UUID.randomUUID().toString()
            it[origin] = "test"
        }.value
    }

    protected fun containerChanges(): Set<RecordedChange> =
        recordedChanges(database).filterTo(mutableSetOf()) { it.type in containerTypes }
}
