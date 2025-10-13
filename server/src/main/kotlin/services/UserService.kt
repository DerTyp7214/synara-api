package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.User
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class UserService(database: Database, environment: ApplicationEnvironment) : Service() {
    init {
        val clientId = environment.config.propertyOrNull("client.id")?.getString()
        val clientSecret = environment.config.propertyOrNull("client.secret")?.getString()

        transaction(database) {
            SchemaUtils.create(UserTable)

            if (clientId != null && clientSecret != null) {
                UserTable.insertIgnore {
                    it[UserTable.username] = clientId
                    it[UserTable.passwordHash] = BCrypt.withDefaults()
                        .hashToString(12, clientSecret.toCharArray())
                }
            }
        }
    }

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

    suspend fun findUserById(id: UUID): User? = queryUser {
        where { UserTable.id eq id }
    }.singleOrNull()

    suspend fun findUserByUsername(username: String): User? = queryUser {
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