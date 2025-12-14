package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.core.foreignKeyOn
import dev.dertyp.db.*
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction

class DatabaseManager(environment: ApplicationEnvironment) {
    private val database: Database by lazy { getDatabase(environment) }

    init {
        transaction(database) {
            foreignKeyOn(database)

            SchemaUtils.create(SyncServiceTable)
            SchemaUtils.create(UserTable)
            SchemaUtils.create(SongTable)
            SchemaUtils.create(UserSongTable)
            SchemaUtils.create(SongArtistTable)
            SchemaUtils.create(TranscodedSongTable)
            SchemaUtils.create(RefreshTokenTable)
            SchemaUtils.create(PlaylistTable)
            SchemaUtils.create(PlaylistSongTable)
            SchemaUtils.create(ImageTable)
            SchemaUtils.create(ArtistTable)
            SchemaUtils.create(AlbumTable)
            SchemaUtils.create(AlbumArtistTable)

            val clientId = environment.config.propertyOrNull("client.id")?.getString()
            val clientSecret = environment.config.propertyOrNull("client.secret")?.getString()

            transaction(database) {
                if (clientId != null && clientSecret != null) {
                    UserTable.insertIgnore {
                        it[UserTable.username] = clientId
                        it[UserTable.passwordHash] = BCrypt.withDefaults()
                            .hashToString(12, clientSecret.toCharArray())
                    }
                }
            }
        }
    }

    private fun getDatabase(environment: ApplicationEnvironment): Database {
        val dbDriver = environment.config.property("storage.driverClassName").getString()
        val dbUrl = environment.config.property("storage.jdbcURL").getString()
        val dbUser = environment.config.property("storage.user").getString()
        val dbPassword = environment.config.property("storage.password").getString()

        return when (dbDriver) {
            "org.sqlite.JDBC" -> Database.connect(dbUrl, dbDriver)
            else -> Database.connect(
                url = dbUrl,
                driver = dbDriver,
                user = dbUser,
                password = dbPassword
            )
        }
    }
}