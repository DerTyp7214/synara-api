package dev.dertyp.services

import dev.dertyp.data.Session
import dev.dertyp.db.RefreshTokenTable
import dev.dertyp.db.SessionTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.days

class SessionService : Service() {

    suspend fun createSession(userId: UUID, userAgent: String, ipAddress: String): UUID = dbQuery {
        SessionTable.insert {
            it[SessionTable.userId] = userId
            it[SessionTable.userAgent] = userAgent
            it[SessionTable.ipAddress] = ipAddress
            it[SessionTable.lastActive] = Instant.now().toEpochMilli()
            it[SessionTable.isActive] = true
        }[SessionTable.id].value
    }

    suspend fun updateSessionActivity(sessionId: UUID, userId: UUID) = dbQuery {
        SessionTable.update({ (SessionTable.id eq sessionId) and (SessionTable.userId eq userId) }) {
            it[lastActive] = Instant.now().toEpochMilli()
            it[isActive] = true
        }
    }

    suspend fun deactivateSession(sessionId: UUID, userId: UUID) = dbQuery {
        SessionTable.update({ (SessionTable.id eq sessionId) and (SessionTable.userId eq userId) }) {
            it[isActive] = false
        }
    }

    suspend fun getSessions(userId: UUID): List<Session> = dbQuery {
        SessionTable
            .selectAll()
            .where { SessionTable.userId eq userId }
            .map(::mapSession)
    }

    suspend fun isSessionActive(sessionId: UUID): Boolean = dbQuery {
        SessionTable
            .selectAll()
            .where { SessionTable.id eq sessionId }
            .singleOrNull()
            ?.get(SessionTable.isActive) ?: false
    }

    suspend fun cleanupOldSessions(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int = dbQuery {
        val oneMonthAgo = Instant.now().minusMillis(30.days.inWholeMilliseconds).toEpochMilli()
        val sessionsToDelete = SessionTable.selectAll().where {
            (SessionTable.lastActive less oneMonthAgo) or (SessionTable.isActive eq false)
        }.map { it[SessionTable.id] }

        onProgress(0.0, "Found ${sessionsToDelete.size} sessions to clean up")

        if (sessionsToDelete.isNotEmpty()) {
            val chunks = sessionsToDelete.chunked(100)
            chunks.forEachIndexed { index, chunk ->
                val progress = (index.toDouble() / chunks.size) * 100.0
                onProgress(progress, "Cleaning up sessions batch ${index + 1}/${chunks.size}")
                
                RefreshTokenTable.deleteWhere { sessionId inList chunk }
                SessionTable.deleteWhere { id inList chunk }
            }
        }
        
        onProgress(100.0, "Cleaned up ${sessionsToDelete.size} sessions")
        sessionsToDelete.size
    }

    private fun mapSession(row: ResultRow): Session {
        return Session(
            id = row[SessionTable.id].value,
            userAgent = row[SessionTable.userAgent],
            ipAddress = row[SessionTable.ipAddress],
            lastActive = row[SessionTable.lastActive],
            isActive = row[SessionTable.isActive]
        )
    }
}