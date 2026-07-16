package dev.dertyp.services.subsonic

import dev.dertyp.data.SubsonicCredentialInfo
import dev.dertyp.data.User
import dev.dertyp.services.ISubsonicCredentialService

class RpcSubsonicCredentialService(
    private val user: User,
    private val credentialService: SubsonicCredentialService,
) : ISubsonicCredentialService {
    override suspend fun getSubsonicCredential(): SubsonicCredentialInfo? =
        credentialService.get(user.id, user.username)

    override suspend fun regenerateSubsonicCredential(): SubsonicCredentialInfo =
        credentialService.regenerate(user.id, user.username)

    override suspend fun revokeSubsonicCredential(): Boolean =
        credentialService.revoke(user.id)
}
