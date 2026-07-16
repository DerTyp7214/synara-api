package dev.dertyp.services

import dev.dertyp.data.ApiKeyInfo
import dev.dertyp.data.ApiKeyScopeInfo
import dev.dertyp.data.User
import java.util.UUID

class RpcApiKeyService(
    private val user: User,
    private val apiKeyService: ApiKeyService,
) : IApiKeyService {
    override suspend fun createApiKey(label: String, scopes: List<String>): String =
        apiKeyService.createKey(user.id, label, scopes)

    override suspend fun listAvailableScopes(): List<ApiKeyScopeInfo> =
        apiKeyService.availableScopes()

    override suspend fun listApiKeys(): List<ApiKeyInfo> =
        apiKeyService.listKeys(user.id)

    override suspend fun revokeApiKey(id: UUID): Boolean =
        apiKeyService.revokeKey(id, user.id)
}
