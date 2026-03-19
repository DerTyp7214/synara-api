package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.User
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class RpcUserService(
    private val user: User,
    private val userService: UserService,
    private val imageService: ImageService
) : IUserService {
    override suspend fun findUserById(id: UUID) = userService.findUserById(id)
    override suspend fun findUserByUsername(username: String) =
        userService.findUserByUsername(username)

    override suspend fun me() = userService.findUserById(user.id)!!.copy(passwordHash = "")
    override suspend fun setProfileImage(bytes: ByteArray) {
        val imageId = imageService.createImage(bytes, "profile")
        userService.updateProfileImage(user.id, imageId)
    }

    override suspend fun setDisplayName(name: String?) {
        userService.updateDisplayName(user.id, name)
    }
}

class UserService : Service() {
    companion object {
        fun mapUser(row: ResultRow): User {
            return User(
                id = row[UserTable.id].value,
                username = row[UserTable.username],
                displayName = row[UserTable.displayName],
                passwordHash = row[UserTable.passwordHash],
                isAdmin = row[UserTable.isAdmin],
                profileImageId = row[UserTable.profileImage]?.value
            )
        }
    }

    private fun map(row: ResultRow) = mapUser(row)

    suspend fun findUserById(id: UUID): User? = queryUser {
        where { UserTable.id eq id }
    }.singleOrNull()

    suspend fun findUserByUsername(username: String): User? = queryUser {
        where { UserTable.username eq username }
    }.singleOrNull()

    suspend fun findAdmin(): User? = dbQuery {
        UserTable
            .selectAll()
            .where { UserTable.isAdmin eq true }
            .map(::map)
            .firstOrNull()
    }

    suspend fun updateProfileImage(id: UUID, imageId: UUID?) = dbQuery {
        UserTable.update({ UserTable.id eq id }) {
            it[profileImage] = imageId
        }
    }

    suspend fun updateDisplayName(id: UUID, name: String?) = dbQuery {
        UserTable.update({ UserTable.id eq id }) {
            it[displayName] = name
        }
    }

    suspend fun createUser(user: AuthenticationRequest): User? = dbQuery {
        UserTable.batchInsert(listOf(user)) {
            this[UserTable.username] = it.username
            this[UserTable.passwordHash] = BCrypt.withDefaults()
                .hashToString(12, it.password.toCharArray())
            this[UserTable.isAdmin] = false
        }.map(::map)
    }.singleOrNull()

    suspend fun queryUser(query: Query.() -> Query = { this }): List<User> {
        return dbQuery {
            UserTable
                .selectAll()
                .query()
                .map(::map)
        }
    }
}