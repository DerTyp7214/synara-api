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
import java.util.*

class UserService : IUserService, Service() {
    companion object {
        fun mapUser(row: ResultRow): User {
            return User(
                id = row[UserTable.id].value,
                username = row[UserTable.username],
                passwordHash = row[UserTable.passwordHash]
            )
        }
    }

    private fun map(row: ResultRow) = mapUser(row)

    override suspend fun findUserById(id: UUID): User? = queryUser {
        where { UserTable.id eq id }
    }.singleOrNull()

    override suspend fun findUserByUsername(username: String): User? = queryUser {
        where { UserTable.username eq username }
    }.singleOrNull()

    suspend fun createUser(user: AuthenticationRequest): User? = dbQuery {
        UserTable.batchInsert(listOf(user)) {
            this[UserTable.username] = it.username
            this[UserTable.passwordHash] = BCrypt.withDefaults()
                .hashToString(12, it.password.toCharArray())
        }.map(::map)
    }.singleOrNull()

    private suspend fun queryUser(query: Query.() -> Query = { this }): List<User> {
        return dbQuery {
            UserTable
                .selectAll()
                .query()
                .map(::map)
        }
    }
}