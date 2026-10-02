package dev.dertyp.services.ui.credentialserver

import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.credentials.CreateClientRequest
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.GrantSpec
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.CredentialServerConnection
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_ADMIN_KEY
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_CLIENT_ID
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_CLIENT_SECRET
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_URL
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PREFIX
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow

class CredentialsOverviewContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.OVERVIEW,
    kind = UiContributionKind.PAGE,
    titleKey = "credentials.title",
    descriptionKey = "credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 70,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val connection = ui.connection()
        val status = ui.linkStatus()
        val admin = status == CredentialServerLinkStatus.CONNECTED && connection.adminConfigured && ui.isAdmin()
        val children = mutableListOf<UiComponent>()
        children += localSection(scope)
        children += serverSection(scope, connection, status, admin)
        if (admin) {
            children += registerSection(scope, connection)
            children += managementSections(scope)
        }
        return UiComponent.Column(children)
    }

    private suspend fun localSection(scope: UiRenderScope): UiComponent {
        val rows = ui.localStore.entries.map { entry ->
            UiComponent.ListItem(
                title = scope.t("credentials.name.${entry.name}"),
                subtitle = scope.t("credentials.about.${entry.name}"),
                icon = UiIcon(UiIconName.KEY),
                trailing = scope.localStateText(ui.localState(entry.name)),
                action = UiAction.OpenPage(CredentialServerPages.LOCAL, mapOf(CredentialServerPages.PARAM_NAME to entry.name)),
            )
        }
        return UiComponent.Section(
            title = scope.t("credentials.local"),
            children = listOf(UiComponent.Text(scope.t("credentials.localHint"), UiTextStyle.CAPTION, UiTone.MUTED)) + rows,
        )
    }

    private suspend fun serverSection(
        scope: UiRenderScope,
        connection: CredentialServerConnection,
        status: CredentialServerLinkStatus,
        admin: Boolean,
    ): UiComponent {
        val children = mutableListOf(
            statusRow(scope, connection, status, admin),
            UiComponent.Text(scope.t("$PREFIX.connectionHint"), UiTextStyle.CAPTION, UiTone.MUTED),
            connectionForm(scope, connection),
        )
        when {
            !connection.adminConfigured -> children += UiComponent.Text(scope.t("$PREFIX.adminKeyMissing"), UiTextStyle.CAPTION, UiTone.MUTED)
            !admin -> children += UiComponent.Text(scope.t("$PREFIX.adminKeyRejected"), UiTextStyle.CAPTION, UiTone.MUTED)
        }
        return UiComponent.Section(title = scope.t("$PREFIX.title"), children = children)
    }

    private suspend fun statusRow(
        scope: UiRenderScope,
        connection: CredentialServerConnection,
        status: CredentialServerLinkStatus,
        admin: Boolean,
    ): UiComponent {
        val badges = mutableListOf<UiComponent>(
            UiComponent.Badge(scope.linkStatusText(status), linkStatusTone(status), UiIcon(UiIconName.PLUG)),
        )
        if (status == CredentialServerLinkStatus.CONNECTED) {
            runCatchingCancellable { ui.admin.health() }.getOrNull()?.let {
                badges += UiComponent.Badge(scope.t("$PREFIX.version", "version" to it.version), UiTone.DEFAULT, UiIcon(UiIconName.INFO))
            }
            if (admin && connection.consumerConfigured) {
                runCatchingCancellable { ui.admin.listClients() }.getOrNull()
                    ?.firstOrNull { it.clientId == connection.clientId }
                    ?.let { badges += UiComponent.Badge(scope.t("$PREFIX.grantedCount", "count" to it.grants.size.toString()), UiTone.PRIMARY, UiIcon(UiIconName.KEY)) }
            }
        }
        return UiComponent.Row(badges, spacing = UiSpacing.SMALL)
    }

    private fun connectionForm(scope: UiRenderScope, connection: CredentialServerConnection): UiComponent {
        val actions = mutableListOf<UiComponent>(
            UiComponent.Button(scope.t("$PREFIX.test"), UiAction.Invoke(id, ACTION_TEST), UiButtonStyle.TEXT, UiIcon(UiIconName.SYNC)),
        )
        if (connection.baseUrl != null) {
            actions += UiComponent.Button(
                scope.t("$PREFIX.disconnect"),
                UiAction.Invoke(id, ACTION_DISCONNECT, confirmText = scope.t("$PREFIX.disconnectConfirm")),
                UiButtonStyle.DESTRUCTIVE,
                UiIcon(UiIconName.CLOSE),
            )
        }
        val keepHint = scope.t("$PREFIX.keepHint")
        return UiComponent.Form(
            id = FORM_CONNECTION,
            submit = UiAction.Invoke(id, ACTION_SAVE, formId = FORM_CONNECTION),
            submitLabel = scope.t("$PREFIX.save"),
            actions = actions,
            children = listOf(
                UiComponent.TextField(FIELD_URL, scope.t("$PREFIX.url"), value = connection.url, placeholder = "https://credentials.example.com", kind = UiTextKind.URL, required = connection.baseUrl == null),
                UiComponent.TextField(FIELD_ADMIN_KEY, scope.t("$PREFIX.adminKey"), secret = true, helper = if (connection.adminKey != null) keepHint else null),
                UiComponent.TextField(FIELD_CLIENT_ID, scope.t("$PREFIX.clientId"), value = connection.clientId),
                UiComponent.TextField(FIELD_CLIENT_SECRET, scope.t("$PREFIX.clientSecret"), secret = true, helper = if (connection.clientSecret != null) keepHint else null),
            ),
        )
    }

    private fun registerSection(scope: UiRenderScope, connection: CredentialServerConnection): UiComponent = UiComponent.Section(
        title = scope.t("$PREFIX.register"),
        children = listOf(
            UiComponent.Text(
                scope.t(if (connection.consumerConfigured) "$PREFIX.registerReplaceHint" else "$PREFIX.registerHint"),
                UiTextStyle.CAPTION,
                UiTone.MUTED,
            ),
            UiComponent.Form(
                id = FORM_REGISTER,
                submit = UiAction.Invoke(
                    id,
                    ACTION_REGISTER,
                    formId = FORM_REGISTER,
                    confirmText = if (connection.consumerConfigured) scope.t("$PREFIX.registerConfirm") else null,
                ),
                submitLabel = scope.t("$PREFIX.registerSubmit"),
                children = listOf(
                    UiComponent.TextField(FIELD_SERVER_NAME, scope.t("$PREFIX.serverName"), value = ui.defaultServerName(), required = true),
                ),
            ),
        ),
    )

    private suspend fun managementSections(scope: UiRenderScope): List<UiComponent> {
        val credentials = runCatchingCancellable { ui.admin.listCredentials() }
        val clients = runCatchingCancellable { ui.admin.listClients() }
        val presets = runCatchingCancellable { ui.admin.presets() }.getOrDefault(emptyList())
        val error = credentials.exceptionOrNull() ?: clients.exceptionOrNull()
        if (error != null) {
            return listOf(UiComponent.Text(scope.t("$PREFIX.loadFailed", "reason" to scope.errorText(error)), UiTextStyle.CAPTION, UiTone.ERROR))
        }
        val credentialItems = credentials.getOrThrow().sortedBy { it.name }.map { credential ->
            UiComponent.ListItem(
                title = credential.name,
                subtitle = listOfNotNull(scope.kindText(credential.kind), credential.description?.takeIf { it.isNotBlank() }).joinToString(" · "),
                icon = UiIcon(if (credential.kind == CredentialKind.FILE || credential.kind == CredentialKind.TIDAL_DEVICE_SESSION) UiIconName.FILE else UiIconName.KEY),
                trailing = scope.statusText(credential.status),
                action = UiAction.OpenPage(CredentialServerPages.CREDENTIAL, mapOf(CredentialServerPages.PARAM_NAME to credential.name)),
            )
        }
        val existing = credentials.getOrThrow().map { it.name }.toSet()
        val presetOptions = presets.map { preset ->
            UiOption(preset.name, if (preset.name in existing) scope.t("$PREFIX.presetExisting", "name" to preset.name) else preset.name)
        } + UiOption(CUSTOM, scope.t("$PREFIX.presetCustom"))
        val credentialSection = UiComponent.Section(
            title = scope.t("$PREFIX.credentials"),
            children = buildList {
                if (credentialItems.isEmpty()) add(UiComponent.Text(scope.t("$PREFIX.noCredentials"), UiTextStyle.CAPTION, UiTone.MUTED))
                addAll(credentialItems)
                add(
                    UiComponent.Form(
                        id = FORM_CREATE_CREDENTIAL,
                        submit = UiAction.Invoke(id, ACTION_CREATE_CREDENTIAL, formId = FORM_CREATE_CREDENTIAL),
                        submitLabel = scope.t("$PREFIX.createCredential"),
                        children = listOf(
                            UiComponent.Select(FIELD_PRESET, scope.t("$PREFIX.preset"), presetOptions.firstOrNull()?.value, presetOptions),
                            UiComponent.TextField(FIELD_CUSTOM_NAME, scope.t("$PREFIX.customName"), helper = scope.t("$PREFIX.customNameHelper")),
                            UiComponent.Select(
                                FIELD_CUSTOM_KIND,
                                scope.t("$PREFIX.customKind"),
                                CredentialKind.API_KEY.name,
                                CredentialKind.entries.map { UiOption(it.name, scope.kindText(it)) },
                            ),
                        ),
                    ),
                )
            },
        )
        val connection = ui.connection()
        val clientItems = clients.getOrThrow().sortedBy { it.name.lowercase() }.map { client ->
            val state = scope.t(if (client.enabled) "$PREFIX.enabled" else "$PREFIX.disabled")
            UiComponent.ListItem(
                title = client.name,
                subtitle = listOfNotNull(
                    client.clientId,
                    scope.t("$PREFIX.thisServer").takeIf { client.clientId == connection.clientId },
                ).joinToString(" · "),
                icon = UiIcon(UiIconName.USER),
                trailing = "$state · ${scope.t("$PREFIX.grantCount", "count" to client.grants.size.toString())}",
                action = UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf(CredentialServerPages.PARAM_ID to client.id)),
            )
        }
        val clientSection = UiComponent.Section(
            title = scope.t("$PREFIX.clients"),
            children = buildList {
                if (clientItems.isEmpty()) add(UiComponent.Text(scope.t("$PREFIX.noClients"), UiTextStyle.CAPTION, UiTone.MUTED))
                addAll(clientItems)
                add(
                    UiComponent.Form(
                        id = FORM_CREATE_CLIENT,
                        submit = UiAction.Invoke(id, ACTION_CREATE_CLIENT, formId = FORM_CREATE_CLIENT),
                        submitLabel = scope.t("$PREFIX.createClient"),
                        children = listOf(UiComponent.TextField(FIELD_CLIENT_NAME, scope.t("$PREFIX.clientName"), required = true)),
                    ),
                )
            },
        )
        return listOf(credentialSection, clientSection)
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult = when (actionId) {
        ACTION_SAVE -> save(scope, values)
        ACTION_TEST -> test(scope)
        ACTION_DISCONNECT -> {
            ui.connection.clear()
            ui.forgetAdminProbe()
            UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.disconnected"), refresh = true)
        }
        ACTION_REGISTER -> register(scope, values)
        ACTION_CREATE_CREDENTIAL -> createCredential(scope, values)
        ACTION_CREATE_CLIENT -> createClient(scope, values)
        else -> super.invoke(scope, actionId, values)
    }

    private suspend fun save(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val current = ui.connection()
        val url = values.text(FIELD_URL)
        if (url.isEmpty() && current.baseUrl == null) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(FIELD_URL to scope.t("$PREFIX.error.url")))
        }
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(FIELD_URL to scope.t("$PREFIX.error.url")))
        }
        val updates = buildMap<String, String?> {
            if (url.isNotEmpty()) put(KEY_URL, url)
            values.text(FIELD_ADMIN_KEY).takeIf { it.isNotEmpty() }?.let { put(KEY_ADMIN_KEY, it) }
            values.text(FIELD_CLIENT_ID).takeIf { it.isNotEmpty() }?.let { put(KEY_CLIENT_ID, it) }
            values.text(FIELD_CLIENT_SECRET).takeIf { it.isNotEmpty() }?.let { put(KEY_CLIENT_SECRET, it) }
        }
        if (updates.isNotEmpty()) ui.connection.store(updates)
        ui.forgetAdminProbe()
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.saved"), refresh = true)
    }

    private suspend fun test(scope: UiRenderScope): UiInvokeResult {
        val connection = ui.connection()
        if (connection.baseUrl == null) return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("$PREFIX.error.notConfigured"))
        val health = runCatchingCancellable { ui.admin.health() }.getOrElse {
            return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("$PREFIX.testUnreachable", "reason" to scope.errorText(it)))
        }
        val parts = mutableListOf(scope.t("$PREFIX.testReachable", "version" to health.version))
        if (connection.adminConfigured) {
            ui.forgetAdminProbe()
            parts += runCatchingCancellable { ui.admin.listClients() }.fold(
                { scope.t("$PREFIX.testAdminOk") },
                { scope.t("$PREFIX.testAdminFailed", "reason" to scope.errorText(it)) },
            )
        }
        val clientId = connection.clientId
        val clientSecret = connection.clientSecret
        if (connection.consumerConfigured && clientId != null && clientSecret != null) {
            parts += runCatchingCancellable { ui.admin.testConsumer(clientId, clientSecret) }.fold(
                { scope.t("$PREFIX.testConsumerOk", "count" to it.grants.size.toString()) },
                { scope.t("$PREFIX.testConsumerFailed", "reason" to scope.errorText(it)) },
            )
        }
        return UiInvokeResult(UiInvokeStatus.OK, parts.joinToString(". "), refresh = true)
    }

    private suspend fun register(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val name = values.text(FIELD_SERVER_NAME).ifEmpty { ui.defaultServerName() }
        val available = ui.admin.listCredentials().associateBy { it.name }
        val grants = CredentialNames.CORE.mapNotNull { credentialName ->
            available[credentialName]?.let { GrantSpec(it.name, writeBack = it.kind == CredentialKind.TIDAL_DEVICE_SESSION) }
        }
        val created = ui.admin.createClient(CreateClientRequest(name, grants))
        ui.connection.store(mapOf(KEY_CLIENT_ID to created.client.clientId, KEY_CLIENT_SECRET to created.clientSecret))
        return UiInvokeResult(
            UiInvokeStatus.OK,
            scope.t("$PREFIX.registered", "name" to created.client.name, "count" to created.client.grants.size.toString()),
            refresh = true,
        )
    }

    private fun createCredential(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val preset = values.text(FIELD_PRESET)
        val params = if (preset.isNotEmpty() && preset != CUSTOM) {
            mapOf(CredentialServerPages.PARAM_NAME to preset)
        } else {
            val name = values.text(FIELD_CUSTOM_NAME).lowercase()
            if (!CredentialNames.isValid(name)) {
                return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(FIELD_CUSTOM_NAME to scope.t("$PREFIX.error.name")))
            }
            val kind = CredentialKind.entries.firstOrNull { it.name == values.text(FIELD_CUSTOM_KIND) } ?: CredentialKind.API_KEY
            mapOf(CredentialServerPages.PARAM_NAME to name, CredentialServerPages.PARAM_KIND to kind.name)
        }
        return UiInvokeResult(UiInvokeStatus.OK, next = UiAction.OpenPage(CredentialServerPages.CREDENTIAL, params))
    }

    private suspend fun createClient(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val name = values.text(FIELD_CLIENT_NAME)
        if (name.isEmpty()) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(FIELD_CLIENT_NAME to scope.t("$PREFIX.error.clientName")))
        }
        val created = ui.admin.createClient(CreateClientRequest(name))
        ui.reveal.put(scope.user.id, created.client.id, created.clientSecret)
        return UiInvokeResult(
            UiInvokeStatus.OK,
            scope.t("$PREFIX.clientCreated", "name" to created.client.name),
            refresh = true,
            next = UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf(CredentialServerPages.PARAM_ID to created.client.id)),
        )
    }

    companion object {
        const val FORM_CONNECTION = "credentialServerConnection"
        const val FORM_REGISTER = "credentialServerRegister"
        const val FORM_CREATE_CREDENTIAL = "credentialServerCreateCredential"
        const val FORM_CREATE_CLIENT = "credentialServerCreateClient"
        const val FIELD_URL = "url"
        const val FIELD_ADMIN_KEY = "adminKey"
        const val FIELD_CLIENT_ID = "clientId"
        const val FIELD_CLIENT_SECRET = "clientSecret"
        const val FIELD_SERVER_NAME = "serverName"
        const val FIELD_PRESET = "preset"
        const val FIELD_CUSTOM_NAME = "customName"
        const val FIELD_CUSTOM_KIND = "customKind"
        const val FIELD_CLIENT_NAME = "clientName"
        const val ACTION_SAVE = "save"
        const val ACTION_TEST = "test"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_REGISTER = "register"
        const val ACTION_CREATE_CREDENTIAL = "createCredential"
        const val ACTION_CREATE_CLIENT = "createClient"
        const val CUSTOM = "custom"
    }
}

internal fun Map<String, UiValue>.text(key: String): String = this[key]?.text?.trim().orEmpty()
