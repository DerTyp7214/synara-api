package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.plugins.PluginCredentials
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider

class PluginCredentialsFactory(
    private val remote: RemoteCredentialProvider,
    private val localStore: LocalPluginCredentialStore,
) {
    fun forPlugin(pluginId: String): PluginCredentials = ScopedPluginCredentials(pluginId)

    private inner class ScopedPluginCredentials(private val pluginId: String) : PluginCredentials {
        private val prefix = CredentialNames.plugin(pluginId, "")

        override val managedRemotely: Boolean
            get() = remote.grantedNames().any { it.startsWith(prefix) }

        override suspend fun get(name: String): ResolvedCredential? {
            val fullName = CredentialNames.plugin(pluginId, name)
            return if (remote.isManagedRemotely(fullName)) remote.resolve(fullName) else localStore.get(pluginId, name)
        }

        override suspend fun store(name: String, credential: ResolvedCredential) {
            checkLocal(name)
            localStore.store(pluginId, name, credential)
        }

        override suspend fun remove(name: String) {
            checkLocal(name)
            localStore.remove(pluginId, name)
        }

        private fun checkLocal(name: String) {
            val fullName = CredentialNames.plugin(pluginId, name)
            check(!remote.isManagedRemotely(fullName)) { "Credential $fullName is managed by the credential server" }
        }
    }
}
