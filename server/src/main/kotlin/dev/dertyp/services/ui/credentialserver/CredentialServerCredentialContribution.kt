package dev.dertyp.services.ui.credentialserver

import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.credentials.*
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PARAM_KIND
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PARAM_NAME
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PREFIX
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.io.encoding.Base64

class CredentialServerCredentialContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.CREDENTIAL,
    kind = UiContributionKind.PAGE,
    titleKey = "credentialserver.credential.title",
    icon = UiIcon(UiIconName.KEY),
    order = 72,
    access = UiAccess(requiresAdmin = true),
) {
    private data class Target(val name: String, val kind: CredentialKind, val existing: CredentialSummary?, val preset: CredentialPreset?)

    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val name = requireNotNull(scope.context.params[PARAM_NAME]?.takeIf { it.isNotBlank() }) { "Missing credential name" }
        if (!ui.isAdmin()) return UiComponent.EmptyState(scope.t("credentials.adminRequired"), icon = UiIcon(UiIconName.WARNING))
        val target = runCatchingCancellable { target(name, scope.context.params[PARAM_KIND]) }.getOrElse {
            return UiComponent.Text(scope.t("$PREFIX.loadFailed", "reason" to scope.errorText(it)), UiTextStyle.BODY, UiTone.ERROR)
        } ?: return UiComponent.EmptyState(scope.t("$PREFIX.credential.notFound"), icon = UiIcon(UiIconName.WARNING))
        val params = mapOf(PARAM_NAME to UiValue.of(target.name), PARAM_KIND to UiValue.of(target.kind.name))
        val children = mutableListOf<UiComponent>()
        children += header(scope, target)
        if (target.kind == CredentialKind.TIDAL_DEVICE_SESSION) {
            ui.tidalLogins.active(scope.user.id, target.name)?.let { session ->
                children += UiComponent.Live("$LIVE_TIDAL_PREFIX${session.loginId}", loginComponent(scope, target.name, session, TidalLoginEvent(TidalLoginState.PENDING)))
            }
        }
        val formActions = buildList {
            if (target.kind == CredentialKind.TIDAL_DEVICE_SESSION) {
                add(UiComponent.Button(scope.t("$PREFIX.credential.tidalLogin"), UiAction.Invoke(id, ACTION_TIDAL_LOGIN, params = params, formId = FORM_CREDENTIAL), UiButtonStyle.TEXT, UiIcon(UiIconName.LOGIN)))
            }
        }
        children += UiComponent.Form(
            id = FORM_CREDENTIAL,
            submit = UiAction.Invoke(id, ACTION_SAVE, params = params, formId = FORM_CREDENTIAL),
            submitLabel = scope.t("$PREFIX.save"),
            actions = formActions,
            children = fields(scope, target),
        )
        if (target.existing != null) {
            children += UiComponent.Divider
            children += UiComponent.Column(
                listOf(
                    UiComponent.Button(scope.t("$PREFIX.credential.test"), UiAction.Invoke(id, ACTION_TEST, params = params), UiButtonStyle.SECONDARY, UiIcon(UiIconName.CHECK)),
                    UiComponent.Button(
                        scope.t("$PREFIX.credential.delete"),
                        UiAction.Invoke(id, ACTION_DELETE, params = params, confirmText = scope.t("$PREFIX.credential.deleteConfirm")),
                        UiButtonStyle.DESTRUCTIVE,
                        UiIcon(UiIconName.CLOSE),
                    ),
                ),
                spacing = UiSpacing.SMALL,
                align = UiAlign.START,
            )
        }
        return UiComponent.Column(children)
    }

    private suspend fun target(name: String, kindParam: String?): Target? {
        val existing = runCatchingCancellable { ui.admin.getCredential(name) }.getOrElse { if (it.isNotFound()) null else throw it }
        val preset = ui.admin.presets().firstOrNull { it.name == name }
        val kind = existing?.kind ?: preset?.kind ?: CredentialKind.entries.firstOrNull { it.name == kindParam } ?: return null
        return Target(name, kind, existing, preset)
    }

    private fun header(scope: UiRenderScope, target: Target): UiComponent {
        val existing = target.existing
        val lines = mutableListOf<UiComponent>(UiComponent.Text(target.name, UiTextStyle.TITLE))
        val badges = mutableListOf<UiComponent>(UiComponent.Badge(scope.kindText(target.kind), UiTone.DEFAULT))
        if (existing == null) {
            badges += UiComponent.Badge(scope.t("$PREFIX.credential.new"), UiTone.MUTED)
        } else {
            badges += UiComponent.Badge(scope.statusText(existing.status), statusTone(existing.status))
        }
        lines += UiComponent.Row(badges, spacing = UiSpacing.SMALL)
        existing?.statusMessage?.takeIf { it.isNotBlank() }?.let { lines += UiComponent.Text(it, UiTextStyle.CAPTION, statusTone(existing.status)) }
        existing?.expiresAt?.let { lines += UiComponent.Text(scope.t("$PREFIX.credential.expires", "time" to formatTime(it)), UiTextStyle.CAPTION, UiTone.MUTED) }
        existing?.let {
            lines += UiComponent.Text(
                if (it.grantedTo.isEmpty()) scope.t("$PREFIX.credential.notGranted") else scope.t("$PREFIX.credential.grantedTo", "clients" to it.grantedTo.joinToString(", ")),
                UiTextStyle.CAPTION,
                UiTone.MUTED,
            )
        }
        target.preset?.description?.takeIf { existing == null }?.let { lines += UiComponent.Text(it, UiTextStyle.CAPTION, UiTone.MUTED) }
        return UiComponent.Column(lines, spacing = UiSpacing.SMALL)
    }

    private fun fields(scope: UiRenderScope, target: Target): List<UiComponent> {
        val stored = target.existing != null
        val keep = if (stored) scope.t("$PREFIX.keepHint") else null
        fun text(key: String, labelKey: String, secret: Boolean = false, value: String? = null, helper: String? = keep) =
            UiComponent.TextField(key, scope.t(labelKey), value = value, secret = secret, helper = helper, required = !stored && value == null)
        val preset = target.preset
        val description = UiComponent.TextField(
            FIELD_DESCRIPTION,
            scope.t("$PREFIX.field.description"),
            value = target.existing?.description ?: preset?.description,
        )
        val specific = when (target.kind) {
            CredentialKind.OAUTH_CLIENT_CREDENTIALS -> listOf(
                text(FIELD_CLIENT_ID, "$PREFIX.field.clientId"),
                text(FIELD_CLIENT_SECRET, "$PREFIX.field.clientSecret", secret = true),
                text(FIELD_TOKEN_URL, "$PREFIX.field.tokenUrl", value = preset?.tokenUrl),
                UiComponent.Select(
                    FIELD_AUTH_STYLE,
                    scope.t("$PREFIX.field.authStyle"),
                    (preset?.authStyle ?: OAuthAuthStyle.BASIC).name,
                    OAuthAuthStyle.entries.map { UiOption(it.name, scope.t("$PREFIX.authStyle.${it.name}")) },
                ),
                UiComponent.TextField(FIELD_SCOPE, scope.t("$PREFIX.field.scope")),
            )
            CredentialKind.APPLE_DEVELOPER_KEY -> listOf(
                text(FIELD_TEAM_ID, "$PREFIX.field.teamId"),
                text(FIELD_KEY_ID, "$PREFIX.field.keyId"),
                UiComponent.FileField(FIELD_P8, scope.t("$PREFIX.field.p8"), accept = listOf(".p8"), secret = true, helper = keep, required = !stored),
                UiComponent.NumberField(FIELD_TTL, scope.t("$PREFIX.field.ttl"), DEFAULT_APPLE_TTL.toDouble(), min = 60.0, max = 15_552_000.0, step = 60.0),
            )
            CredentialKind.API_KEY -> listOf(text(FIELD_KEY, "$PREFIX.field.key", secret = true))
            CredentialKind.API_KEY_PAIR -> listOf(
                text(FIELD_KEY, "$PREFIX.field.key", secret = true),
                text(FIELD_SECRET, "$PREFIX.field.secret", secret = true),
            )
            CredentialKind.TIDAL_DEVICE_SESSION -> listOf(
                UiComponent.Select(
                    FIELD_FORMAT,
                    scope.t("$PREFIX.field.format"),
                    (preset?.format ?: TidalSessionFormat.TIDDL).name,
                    TidalSessionFormat.entries.map { UiOption(it.name, it.name.lowercase()) },
                ),
                text(FIELD_CLIENT_ID, "$PREFIX.field.clientId"),
                text(FIELD_CLIENT_SECRET, "$PREFIX.field.clientSecret", secret = true),
                UiComponent.FileField(
                    FIELD_AUTH_FILE,
                    scope.t("$PREFIX.field.authFile"),
                    accept = accept(CredentialFileRoles.TIDDL_AUTH),
                    secret = true,
                    helper = scope.t("$PREFIX.field.authFileHelper"),
                ),
            )
            CredentialKind.FILE -> fileRoles(target).map { role ->
                if (isBinaryRole(role)) {
                    UiComponent.FileField(fileKey(role), role, accept = accept(role), binary = true, secret = true, helper = scope.t("$PREFIX.field.base64Helper"))
                } else {
                    UiComponent.FileField(fileKey(role), role, accept = accept(role), secret = true, helper = keep)
                }
            }
        }
        return listOf(description) + specific
    }

    override fun live(scope: UiRenderScope, key: String): Flow<UiLiveUpdate>? {
        if (!key.startsWith(LIVE_TIDAL_PREFIX)) return null
        val loginId = key.removePrefix(LIVE_TIDAL_PREFIX)
        val entry = ui.tidalLogins.byLoginId(scope.user.id, loginId) ?: return null
        return flow {
            ui.admin.tidalLoginEvents(loginId).collect { event ->
                if (event.state != TidalLoginState.PENDING) ui.tidalLogins.remove(scope.user.id, entry.credentialName)
                emit(UiLiveUpdate.Replace(loginComponent(scope, entry.credentialName, entry.session, event)))
            }
        }
    }

    private fun loginComponent(scope: UiRenderScope, credentialName: String, session: TidalLoginSession, event: TidalLoginEvent): UiComponent {
        val url = session.verificationUriComplete ?: session.verificationUri
        return when (event.state) {
            TidalLoginState.PENDING -> UiComponent.Card(
                title = scope.t("$PREFIX.tidal.title"),
                icon = UiIcon(UiIconName.LOGIN),
                tone = UiTone.PRIMARY,
                children = listOf(
                    UiComponent.Text(scope.t("$PREFIX.tidal.instructions"), UiTextStyle.BODY),
                    UiComponent.Text(session.userCode, UiTextStyle.CODE),
                    UiComponent.Text(session.verificationUri, UiTextStyle.CAPTION, UiTone.MUTED),
                    UiComponent.Progress(null, event.message ?: scope.t("$PREFIX.tidal.waiting", "time" to formatTime(session.expiresAt))),
                ),
                actions = listOf(
                    UiComponent.Button(scope.t("$PREFIX.tidal.open"), UiAction.OpenUrl(url), UiButtonStyle.PRIMARY, UiIcon(UiIconName.LINK)),
                    UiComponent.Button(
                        scope.t("$PREFIX.tidal.cancel"),
                        UiAction.Invoke(id, ACTION_TIDAL_CANCEL, params = mapOf(PARAM_NAME to UiValue.of(credentialName), FIELD_LOGIN_ID to UiValue.of(session.loginId))),
                        UiButtonStyle.TEXT,
                        UiIcon(UiIconName.CLOSE),
                    ),
                ),
            )
            TidalLoginState.COMPLETED -> UiComponent.Badge(scope.t("$PREFIX.tidal.completed"), UiTone.SUCCESS, UiIcon(UiIconName.CHECK))
            TidalLoginState.EXPIRED -> UiComponent.Badge(scope.t("$PREFIX.tidal.expired"), UiTone.WARNING, UiIcon(UiIconName.WARNING))
            TidalLoginState.FAILED -> UiComponent.Badge(scope.t("$PREFIX.tidal.failed", "reason" to (event.message ?: "")), UiTone.ERROR, UiIcon(UiIconName.ERROR))
            TidalLoginState.CANCELLED -> UiComponent.Badge(scope.t("$PREFIX.tidal.cancelled"), UiTone.MUTED, UiIcon(UiIconName.CLOSE))
        }
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        if (actionId !in ACTIONS) return super.invoke(scope, actionId, values)
        val name = values.text(PARAM_NAME).ifEmpty { scope.context.params[PARAM_NAME].orEmpty() }
        if (!CredentialNames.isValid(name)) return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("$PREFIX.error.name"))
        return when (actionId) {
            ACTION_SAVE -> save(scope, name, values)
            ACTION_TEST -> {
                val result = ui.admin.testCredential(name)
                val expiry = result.expiresAt?.let { scope.t("$PREFIX.credential.expires", "time" to formatTime(it)) }
                if (result.ok) {
                    UiInvokeResult(UiInvokeStatus.OK, listOfNotNull(scope.t("$PREFIX.credential.testOk"), expiry).joinToString(". "), refresh = true)
                } else {
                    UiInvokeResult(UiInvokeStatus.ERROR, scope.t("$PREFIX.credential.testFailed", "reason" to (result.message ?: "")), refresh = true)
                }
            }
            ACTION_DELETE -> {
                ui.admin.deleteCredential(name)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.credential.deleted"), next = UiAction.OpenPage(CredentialServerPages.OVERVIEW))
            }
            ACTION_TIDAL_LOGIN -> startTidalLogin(scope, name, values)
            else -> {
                val loginId = values.text(FIELD_LOGIN_ID)
                if (loginId.isNotEmpty()) runCatchingCancellable { ui.admin.cancelTidalLogin(loginId) }
                ui.tidalLogins.remove(scope.user.id, name)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.tidal.cancelled"), refresh = true)
            }
        }
    }

    private suspend fun startTidalLogin(scope: UiRenderScope, name: String, values: Map<String, UiValue>): UiInvokeResult {
        val format = TidalSessionFormat.entries.firstOrNull { it.name == values.text(FIELD_FORMAT) } ?: TidalSessionFormat.TIDDL
        val session = ui.admin.startTidalLogin(
            name,
            TidalLoginStart(
                format = format,
                clientId = values.text(FIELD_CLIENT_ID).ifEmpty { null },
                clientSecret = values.text(FIELD_CLIENT_SECRET).ifEmpty { null },
            ),
        )
        ui.tidalLogins.put(scope.user.id, name, session)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.tidal.started"), refresh = true)
    }

    private suspend fun save(scope: UiRenderScope, name: String, values: Map<String, UiValue>): UiInvokeResult {
        val target = target(name, values.text(PARAM_KIND)) ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("$PREFIX.credential.notFound"))
        val stored = target.existing != null
        val missing = mutableMapOf<String, String>()
        fun field(key: String, required: Boolean = !stored): String {
            val value = values.text(key)
            if (required && value.isEmpty()) missing[key] = scope.t("$PREFIX.error.required")
            return value
        }
        val input: CredentialInput = when (target.kind) {
            CredentialKind.OAUTH_CLIENT_CREDENTIALS -> CredentialInput.OAuthClientCredentialsInput(
                clientId = field(FIELD_CLIENT_ID),
                clientSecret = field(FIELD_CLIENT_SECRET),
                tokenUrl = field(FIELD_TOKEN_URL),
                authStyle = OAuthAuthStyle.entries.firstOrNull { it.name == values.text(FIELD_AUTH_STYLE) } ?: OAuthAuthStyle.BASIC,
                scope = values.text(FIELD_SCOPE).ifEmpty { null },
            )
            CredentialKind.APPLE_DEVELOPER_KEY -> CredentialInput.AppleDeveloperKeyInput(
                teamId = field(FIELD_TEAM_ID),
                keyId = field(FIELD_KEY_ID),
                p8Pem = field(FIELD_P8),
                ttlSeconds = values[FIELD_TTL]?.number?.toLong() ?: DEFAULT_APPLE_TTL,
            )
            CredentialKind.API_KEY -> CredentialInput.ApiKeyInput(field(FIELD_KEY))
            CredentialKind.API_KEY_PAIR -> CredentialInput.ApiKeyPairInput(field(FIELD_KEY), field(FIELD_SECRET))
            CredentialKind.TIDAL_DEVICE_SESSION -> CredentialInput.TidalSessionInput(
                format = TidalSessionFormat.entries.firstOrNull { it.name == values.text(FIELD_FORMAT) } ?: TidalSessionFormat.TIDDL,
                clientId = field(FIELD_CLIENT_ID),
                clientSecret = field(FIELD_CLIENT_SECRET),
                authFileContent = values.text(FIELD_AUTH_FILE).ifEmpty { null },
            )
            CredentialKind.FILE -> {
                val files = fileRoles(target).mapNotNull { role ->
                    val content = values[fileKey(role)]?.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val encoded = if (isBinaryRole(role)) {
                        val compact = content.filterNot { it.isWhitespace() }
                        if (runCatching { Base64.decode(compact) }.isFailure) {
                            missing[fileKey(role)] = scope.t("$PREFIX.error.base64")
                        }
                        compact
                    } else {
                        Base64.encode(content.toByteArray(Charsets.UTF_8))
                    }
                    CredentialFile(role, encoded)
                }
                if (!stored && files.isEmpty()) fileRoles(target).firstOrNull()?.let { missing[fileKey(it)] = scope.t("$PREFIX.error.required") }
                CredentialInput.FileInput(files)
            }
        }
        if (missing.isNotEmpty()) return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = missing)
        ui.admin.upsertCredential(name, UpsertCredentialRequest(target.kind, values.text(FIELD_DESCRIPTION).ifEmpty { null }, input))
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.saved"), refresh = true)
    }

    private fun fileRoles(target: Target): List<String> =
        target.preset?.fileRoles?.takeIf { it.isNotEmpty() } ?: when (target.name) {
            CredentialNames.IMPORTER_GAMDL -> listOf(CredentialFileRoles.GAMDL_COOKIES, CredentialFileRoles.GAMDL_WVD)
            else -> listOf(DEFAULT_FILE_ROLE)
        }

    private fun isBinaryRole(role: String) = role == CredentialFileRoles.GAMDL_WVD

    private fun accept(role: String): List<String> =
        role.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { listOf(".$it") } ?: emptyList()

    companion object {
        const val FORM_CREDENTIAL = "credentialServerCredential"
        const val LIVE_TIDAL_PREFIX = "tidalLogin:"
        const val FIELD_DESCRIPTION = "description"
        const val FIELD_CLIENT_ID = "clientId"
        const val FIELD_CLIENT_SECRET = "clientSecret"
        const val FIELD_TOKEN_URL = "tokenUrl"
        const val FIELD_AUTH_STYLE = "authStyle"
        const val FIELD_SCOPE = "scope"
        const val FIELD_TEAM_ID = "teamId"
        const val FIELD_KEY_ID = "keyId"
        const val FIELD_P8 = "p8Pem"
        const val FIELD_TTL = "ttlSeconds"
        const val FIELD_KEY = "key"
        const val FIELD_SECRET = "secret"
        const val FIELD_FORMAT = "format"
        const val FIELD_AUTH_FILE = "authFile"
        const val FIELD_FILE_PREFIX = "file:"
        const val FIELD_LOGIN_ID = "loginId"
        const val ACTION_SAVE = "save"
        const val ACTION_TEST = "test"
        const val ACTION_DELETE = "delete"
        const val ACTION_TIDAL_LOGIN = "tidalLogin"
        const val ACTION_TIDAL_CANCEL = "tidalCancel"
        const val DEFAULT_FILE_ROLE = "file"
        const val DEFAULT_APPLE_TTL = 43200L
        private val ACTIONS = setOf(ACTION_SAVE, ACTION_TEST, ACTION_DELETE, ACTION_TIDAL_LOGIN, ACTION_TIDAL_CANCEL)

        fun fileKey(role: String) = "$FIELD_FILE_PREFIX$role"
    }
}
