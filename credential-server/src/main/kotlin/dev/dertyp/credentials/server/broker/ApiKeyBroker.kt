package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.ResolvedCredential

class ApiKeyBroker : CredentialBroker<StoredSecret> {
    override suspend fun resolve(name: String, secret: StoredSecret): BrokerResolution<StoredSecret> {
        val credential = when (secret) {
            is ApiKeySecret -> ResolvedCredential.ApiKey(name = name, key = secret.key)
            is ApiKeyPairSecret -> ResolvedCredential.ApiKeyPair(name = name, key = secret.key, secret = secret.secret)
            else -> throw IllegalArgumentException("ApiKeyBroker cannot resolve ${secret.kind}")
        }
        return BrokerResolution(credential, null, CredentialStateUpdate(CredentialStatus.OK, null, null, null))
    }
}
