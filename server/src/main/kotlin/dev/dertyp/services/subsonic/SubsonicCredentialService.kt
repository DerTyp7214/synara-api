package dev.dertyp.services.subsonic

import dev.dertyp.data.SubsonicCredentialInfo
import dev.dertyp.data.User
import dev.dertyp.db.SubsonicCredentialTable
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import dev.dertyp.services.UserService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.security.SecureRandom
import java.util.UUID

class SubsonicCredentialService : Service() {
    private val userService by inject<UserService>()

    private fun generateSecret(): String {
        val random = SecureRandom.getInstanceStrong()
        return buildString(SECRET_LENGTH) {
            repeat(SECRET_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    suspend fun get(userId: UUID, username: String): SubsonicCredentialInfo? = dbQuery {
        SubsonicCredentialTable.selectAll()
            .where { SubsonicCredentialTable.userId eq userId }
            .singleOrNull()
            ?.let {
                SubsonicCredentialInfo(
                    username = username,
                    password = it[SubsonicCredentialTable.secret],
                    createdAt = it[SubsonicCredentialTable.createdAt],
                )
            }
    }

    suspend fun regenerate(userId: UUID, username: String): SubsonicCredentialInfo {
        val secret = generateSecret()
        val now = System.currentTimeMillis()
        dbQuery {
            SubsonicCredentialTable.upsert(SubsonicCredentialTable.userId) {
                it[SubsonicCredentialTable.userId] = userId
                it[SubsonicCredentialTable.secret] = secret
                it[SubsonicCredentialTable.createdAt] = now
            }
        }
        return SubsonicCredentialInfo(username = username, password = secret, createdAt = now)
    }

    suspend fun revoke(userId: UUID): Boolean = dbQuery {
        SubsonicCredentialTable.deleteWhere { SubsonicCredentialTable.userId eq userId } > 0
    }

    suspend fun secretForUsername(username: String): Pair<User, String>? {
        val user = userService.findUserByUsername(username) ?: return null
        val secret = dbQuery {
            SubsonicCredentialTable.selectAll()
                .where { SubsonicCredentialTable.userId eq user.id }
                .singleOrNull()
                ?.get(SubsonicCredentialTable.secret)
        } ?: return null
        return user to secret
    }

    companion object {
        private const val SECRET_LENGTH = 20
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
