package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.data.QueueUploadStart
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.User
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class RpcQueueService(
    private val user: User,
    private val sessionId: UUID?,
    private val queueService: QueueService,
    private val sessionService: SessionService,
    private val clientRequestService: ClientRequestService
) : IQueueService {
    private fun session(): UUID = sessionId ?: throw IllegalStateException("Queue sync requires a session")

    override suspend fun getQueueInfo(): QueueInfo = queueService.getInfo(user.id)

    override suspend fun getQueue(page: Int, pageSize: Int, includeSongs: Boolean): PaginatedResponse<QueueItem> =
        queueService.getQueue(user.id, page, pageSize, includeSongs)

    override fun observeQueue(): Flow<QueueInfo> = queueService.observe(user.id)

    override suspend fun beginUpload(baseVersion: Long, force: Boolean): QueueUploadStart =
        queueService.beginUpload(user.id, sessionId, baseVersion, force)

    override suspend fun uploadPage(uploadId: UUID, @LogParam("size") items: List<QueueItem>): Int =
        queueService.uploadPage(user.id, uploadId, items)

    override suspend fun commitUpload(uploadId: UUID, meta: QueueMeta, requestId: UUID?): QueueWriteResult {
        val result = queueService.commitUpload(user.id, uploadId, meta)
        if (result is QueueWriteResult.Ok && requestId != null) {
            clientRequestService.complete(session(), requestId, ClientRequestStatus.COMPLETED)
        }
        return result
    }

    override suspend fun cancelUpload(uploadId: UUID) {
        queueService.cancelUpload(user.id, uploadId)
    }

    override suspend fun insert(
        baseVersion: Long,
        position: Int,
        @LogParam("size") items: List<QueueItem>,
        force: Boolean
    ): QueueWriteResult = queueService.insert(user.id, sessionId, baseVersion, position, items, force)

    override suspend fun remove(
        baseVersion: Long,
        @LogParam("size") queueIds: List<Long>,
        force: Boolean
    ): QueueWriteResult = queueService.remove(user.id, sessionId, baseVersion, queueIds, force)

    override suspend fun move(baseVersion: Long, queueId: Long, toPosition: Int, force: Boolean): QueueWriteResult =
        queueService.move(user.id, sessionId, baseVersion, queueId, toPosition, force)

    override suspend fun setCurrentIndex(baseVersion: Long, currentIndex: Int, force: Boolean): QueueWriteResult =
        queueService.setCurrentIndex(user.id, sessionId, baseVersion, currentIndex, force)

    override suspend fun setModes(
        baseVersion: Long,
        shuffleMode: Boolean,
        repeatMode: RepeatMode,
        force: Boolean
    ): QueueWriteResult = queueService.setModes(user.id, sessionId, baseVersion, shuffleMode, repeatMode, force)

    override suspend fun setSyncEnabled(enabled: Boolean, deviceName: String) {
        queueService.setSyncEnabled(user.id, session(), enabled, deviceName)
    }

    override suspend fun ackSynced(version: Long) {
        queueService.ackSynced(user.id, session(), version)
    }

    override suspend fun getSyncDevices(): List<QueueSyncDevice> = queueService.getSyncDevices(user.id, sessionId)

    override suspend fun requestUploadFrom(sessionId: UUID): ClientRequestStatus {
        if (!sessionService.sessionBelongsTo(sessionId, user.id)) {
            throw UnauthorizedException("Session does not belong to the current user")
        }
        val requester = session()
        val deviceName = queueService.deviceName(requester)
        return clientRequestService.request(sessionId, 30.seconds) { id, at ->
            ClientRequest.UploadQueue(id, at, requester, deviceName)
        }
    }
}
