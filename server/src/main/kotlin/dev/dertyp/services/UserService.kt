package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserCapabilityTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.*
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
    override suspend fun getAllUsers(): List<User> {
        return userService.queryUser().map { it.copy(passwordHash = "") }
    }

    override suspend fun setProfileImage(bytes: ByteArray) {
        val imageId = imageService.createImage(bytes, "profile")
        userService.updateProfileImage(user.id, imageId)
    }

    override suspend fun setDisplayName(name: String?) {
        userService.updateDisplayName(user.id, name)
    }

    override suspend fun setCapabilities(id: UUID, capabilities: List<UserCapability>) {
        userService.setCapabilities(id, capabilities)
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
                profileImageId = row[UserTable.profileImage]?.value,
                blurHash = row.getOrNull(ImageTable.blurHash)
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

    suspend fun setCapabilities(id: UUID, capabilities: List<UserCapability>) = dbQuery {
        UserCapabilityTable.deleteWhere { userId eq id }
        UserCapabilityTable.batchInsert(capabilities) {
            this[UserCapabilityTable.userId] = id
            this[UserCapabilityTable.capability] = it
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

    suspend fun upsertUser(user: User) = dbQuery {
        UserTable.upsert {
            it[id] = user.id
            it[username] = user.username
            it[passwordHash] = user.passwordHash
            it[displayName] = user.displayName
            it[isAdmin] = user.isAdmin
            it[profileImage] = user.profileImageId
        }

        UserCapabilityTable.deleteWhere { userId eq user.id }
        UserCapabilityTable.batchInsert(user.capabilities) {
            this[UserCapabilityTable.userId] = user.id
            this[UserCapabilityTable.capability] = it
        }
    }

    suspend fun queryUser(query: Query.() -> Query = { this }): List<User> {
        return dbQuery {
            val users = UserTable
                .leftJoin(ImageTable, onColumn = { UserTable.profileImage }, otherColumn = { ImageTable.id })
                .selectAll()
                .query()
                .map(::map)

            val userIds = users.map { it.id }
            val capabilitiesMap = UserCapabilityTable
                .selectAll()
                .where { UserCapabilityTable.userId inList userIds }
                .groupBy { it[UserCapabilityTable.userId].value }
                .mapValues { entry -> entry.value.map { it[UserCapabilityTable.capability] } }

            users.map { user ->
                user.copy(capabilities = capabilitiesMap[user.id] ?: emptyList())
            }
        }
    }
}