package dev.dertyp.listenbackup

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.sql.DataSource

object BackupListenTable : Table("backup_listen") {
    val id = javaUUID("id")
    val serverId = javaUUID("serverId")
    val userId = javaUUID("userId").nullable()
    val songId = javaUUID("songId").nullable()
    val recordingMbid = javaUUID("recordingMbid").nullable()
    val recordingMsid = javaUUID("recordingMsid").nullable()
    val releaseMbid = javaUUID("releaseMbid").nullable()
    val isrcs = text("isrcs").nullable()
    val artistMbids = text("artistMbids").nullable()
    val trackName = text("trackName").nullable()
    val artistName = text("artistName").nullable()
    val releaseName = text("releaseName").nullable()
    val listenedAt = long("listenedAt")
    val msPlayed = long("msPlayed").nullable()
    val updatedAt = long("updatedAt")
    val receivedAt = long("receivedAt")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, serverId, updatedAt)
        index(false, userId, listenedAt)
    }
}

class ListenBackupStore(dataSource: DataSource) {
    private val database = Database.connect(dataSource)

    init {
        transaction(database) {
            SchemaUtils.create(BackupListenTable)
        }
    }

    fun count(): Long = transaction(database) {
        BackupListenTable.select(BackupListenTable.id.count()).single()[BackupListenTable.id.count()]
    }

    fun status(serverId: UUID?): ListenBackupStatus = transaction(database) {
        val countExpr = BackupListenTable.id.count()
        val maxUpdated = BackupListenTable.updatedAt.max()
        val maxReceived = BackupListenTable.receivedAt.max()
        val query = BackupListenTable.select(countExpr, maxUpdated, maxReceived)
        if (serverId != null) query.where { BackupListenTable.serverId eq serverId }
        val row = query.single()
        ListenBackupStatus(
            serverId = serverId,
            listenCount = row[countExpr],
            lastReceivedAt = row[maxReceived],
            maxUpdatedAt = row[maxUpdated],
        )
    }

    fun upsert(batch: ListenBackupBatch): Int {
        if (batch.listens.isEmpty()) return 0
        val now = System.currentTimeMillis()
        transaction(database) {
            BackupListenTable.batchUpsert(batch.listens) { listen ->
                this[BackupListenTable.id] = listen.id
                this[BackupListenTable.serverId] = batch.serverId
                this[BackupListenTable.userId] = listen.userId
                this[BackupListenTable.songId] = listen.songId
                this[BackupListenTable.recordingMbid] = listen.recordingMbid
                this[BackupListenTable.recordingMsid] = listen.recordingMsid
                this[BackupListenTable.releaseMbid] = listen.releaseMbid
                this[BackupListenTable.isrcs] = listen.isrcs
                this[BackupListenTable.artistMbids] = listen.artistMbids
                this[BackupListenTable.trackName] = listen.trackName
                this[BackupListenTable.artistName] = listen.artistName
                this[BackupListenTable.releaseName] = listen.releaseName
                this[BackupListenTable.listenedAt] = listen.listenedAt
                this[BackupListenTable.msPlayed] = listen.msPlayed
                this[BackupListenTable.updatedAt] = listen.updatedAt
                this[BackupListenTable.receivedAt] = now
            }
        }
        return batch.listens.size
    }
}
