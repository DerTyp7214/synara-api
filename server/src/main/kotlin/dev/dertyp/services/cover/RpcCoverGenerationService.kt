package dev.dertyp.services.cover

import dev.dertyp.data.CoverGenerationOptions
import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverInfo
import dev.dertyp.data.CoverTarget
import dev.dertyp.data.User
import dev.dertyp.services.ICoverGenerationService
import java.util.UUID

class RpcCoverGenerationService(
    private val user: User,
    private val service: CoverGenerationService,
) : ICoverGenerationService {
    override suspend fun options(): CoverGenerationOptions = service.options()

    override suspend fun coverInfo(target: CoverTarget): CoverInfo = service.coverInfo(target)

    override suspend fun previewCoverImage(target: CoverTarget, params: CoverGenerationParams): ByteArray {
        requireOwner(target)
        return service.preview(target, params)
    }

    override suspend fun applyCover(target: CoverTarget, params: CoverGenerationParams): UUID {
        requireOwner(target)
        return service.apply(target, params)
    }

    override suspend fun resetCover(target: CoverTarget): Boolean {
        requireOwner(target)
        return service.reset(target)
    }

    override suspend fun generateMissing(params: CoverGenerationParams): UUID =
        service.enqueueMissing(user.id, params).id

    private suspend fun requireOwner(target: CoverTarget) {
        val row = service.row(target) ?: throw IllegalArgumentException("Unknown ${target.type.name.lowercase()} ${target.id}")
        if (row.creator != user.id && !user.isAdmin) throw IllegalAccessException("Not the owner of ${target.type.name.lowercase()} ${target.id}")
    }
}
