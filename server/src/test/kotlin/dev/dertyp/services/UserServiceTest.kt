package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class UserServiceTest {
    private lateinit var database: Database
    private val service = UserService()

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "user_test")
        transaction(database) {
            SchemaUtils.create(UserTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
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
}
