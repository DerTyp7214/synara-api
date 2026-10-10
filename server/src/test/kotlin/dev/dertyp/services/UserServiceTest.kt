package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.User
import dev.dertyp.data.UserPasswordHash
import dev.dertyp.db.ImageMetadataTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserCapabilityTable
import dev.dertyp.db.UserTable
import dev.dertyp.serializers.AppJson
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class UserServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: UserService

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "user_test", UserTable, ImageTable, ImageMetadataTable, UserCapabilityTable)
        service = UserService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `findUserById should return user with profile blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "profile_blurhash"
            }
            UserTable.insert {
                it[id] = userId
                it[username] = "user_with_image"
                it[passwordHash] = "hash"
                it[profileImage] = imageId
            }
        }

        val user = service.findUserById(userId)
        assertNotNull(user)
        assertEquals(imageId, user?.profileImageId)
        assertEquals("profile_blurhash", user?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createUser should insert a new user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val request = AuthenticationRequest("testuser", "password")
        val user = service.createUser(request)

        assertNotNull(user)
        assertEquals("testuser", user?.username)
        assertFalse(user!!.isAdmin)

        val found = service.findUserByUsername("testuser")
        assertEquals(user.id, found?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateDisplayName should change name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
        }

        service.updateDisplayName(userId, "New Name")
        val updated = service.findUserById(userId)
        assertEquals("New Name", updated?.displayName)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `findAdmin should return user with isAdmin true`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            UserTable.insert {
                it[username] = "admin"
                it[passwordHash] = "hash"
                it[isAdmin] = true
            }
            UserTable.insert {
                it[username] = "regular"
                it[passwordHash] = "hash"
                it[isAdmin] = false
            }
        }

        val admin = service.findAdmin()
        assertNotNull(admin)
        assertEquals("admin", admin?.username)
        assertTrue(admin!!.isAdmin)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateProfileImage should update image id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "path"
                it[imageHash] = "hash"
                it[origin] = "origin"
            }
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
        }

        service.updateProfileImage(userId, imageId)

        val user = service.findUserById(userId)
        assertEquals(imageId, user?.profileImageId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queryUser should return all users`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            UserTable.insert { it[username] = "user1"; it[passwordHash] = "h" }
            UserTable.insert { it[username] = "user2"; it[passwordHash] = "h" }
        }

        val users = service.queryUser()
        assertEquals(2, users.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `client facing user calls send an empty password hash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "hashed"
                it[passwordHash] = "stored-hash"
            }
        }
        val caller = service.findUserById(userId)!!
        val rpc = RpcUserService(caller, service, mockk(relaxed = true))

        val users = listOfNotNull(rpc.byId(userId), rpc.byUsername("hashed"), rpc.me()) + rpc.allUsers()
        assertEquals(4, users.size)
        users.forEach { user ->
            val json = AppJson.encodeToString(User.serializer(), user)
            assertFalse(json.contains("passwordHash"), json)
            assertFalse(json.contains("stored-hash"), json)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `passwordHashOf reads the stored hash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            UserTable.insert {
                it[username] = "hashed"
                it[passwordHash] = "stored-hash"
            }
        }

        assertEquals("stored-hash", service.passwordHashOf("hashed"))
        assertNull(service.passwordHashOf("missing"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertUser keeps the stored hash and setPasswordHash replaces it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mirrored = User(id = UUID.randomUUID(), username = "mirrored")

        service.upsertUser(mirrored)
        assertEquals("", service.passwordHashOf("mirrored"))

        service.setPasswordHash(id = mirrored.id, hash = "mirrored-hash")
        service.upsertUser(mirrored.copy(displayName = "Mirrored"))

        assertEquals("mirrored-hash", service.passwordHashOf("mirrored"))
        assertEquals("Mirrored", service.findUserById(mirrored.id)?.displayName)
        assertEquals(
            listOf(UserPasswordHash(id = mirrored.id, passwordHash = "mirrored-hash")),
            service.passwordHashes()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `login checks the password against the stored hash`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect)
        val created = service.createUser(AuthenticationRequest("login", "secret"))!!
        val authService = AuthService(service, mockk(), mockk())

        assertEquals(created.id, authService.validateUser("login", "secret").id)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { authService.validateUser("login", "wrong") }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allProfiles should list every user by username with the avatar and no account data`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val zoeId = UUID.randomUUID()
            val annId = UUID.randomUUID()
            val imageId = UUID.randomUUID()
            transaction(database) {
                ImageTable.insert {
                    it[id] = imageId
                    it[path] = "avatar.jpg"
                    it[imageHash] = "avatar"
                    it[origin] = "test"
                    it[blurHash] = "avatar_blurhash"
                }
                UserTable.insert {
                    it[id] = zoeId
                    it[username] = "zoe"
                    it[passwordHash] = "secret-hash"
                    it[isAdmin] = true
                }
                UserTable.insert {
                    it[id] = annId
                    it[username] = "ann"
                    it[displayName] = "Ann A"
                    it[passwordHash] = "hash"
                    it[profileImage] = imageId
                }
            }

            val profiles = service.allProfiles()

            assertEquals(listOf("ann", "zoe"), profiles.map { it.username })
            val ann = profiles.first()
            assertEquals(annId, ann.id)
            assertEquals("Ann A", ann.displayName)
            assertEquals(imageId, ann.profileImageId)
            assertEquals("avatar_blurhash", ann.blurHash)
            val zoe = profiles.last()
            assertEquals(zoeId, zoe.id)
            assertNull(zoe.displayName)
            assertNull(zoe.profileImageId)
            assertNull(zoe.blurHash)
        }
}
