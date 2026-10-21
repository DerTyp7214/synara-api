package dev.dertyp.utils

import dev.dertyp.core.ClientFeature
import dev.dertyp.core.ClientInfo
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.ui.UiService
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiOption
import dev.dertyp.ui.UiSchema
import dev.dertyp.ui.UiSchemaVersion
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KClass

class UiSchemaCompat(
    private val introducedIn: Map<KClass<out UiComponent>, Int> = UiSchema.introducedIn,
    private val fieldIntroducedIn: Map<KClass<*>, Map<String, Int>> = UiSchema.fieldIntroducedIn,
) : CompatRule, KoinComponent {
    override val feature = ClientFeature.SERVER_DRIVEN_UI

    private val uiService by inject<UiService>()
    private val translations by inject<TranslationService>()

    private val latestVersion = (introducedIn.values + fieldIntroducedIn.values.flatMap { it.values }).maxOrNull()
        ?: UiSchemaVersion.CURRENT

    private val replacements = listOf(
        replacement<UiComponent.FileField> { field, _, _ -> asTextField(field) },
        replacement<UiComponent.SearchField> { field, scope, formId ->
            if (scope != null && formId != null) asSearchColumn(field, scope, formId) else null
        },
    ).associate { it.type to it }

    private val fieldDowngrades = listOf(
        stripField<UiComponent.ListItem>(IMAGE_FIELD) { it.copy(image = null) },
        stripField<UiComponent.Card>(IMAGE_FIELD) { it.copy(image = null) },
        stripField<UiOption>(IMAGE_FIELD) { it.copy(image = null) },
    )

    init {
        val missing = fieldIntroducedIn.flatMap { (owner, fields) -> fields.keys.map { owner to it } }
            .filter { (owner, field) -> fieldDowngrades.none { it.owner == owner && it.field == field } }
        require(missing.isEmpty()) { "Versioned UI fields without a downgrade: $missing" }
    }

    override fun isActive(client: ClientInfo): Boolean = client.uiSchemaVersion < latestVersion

    override suspend fun shapeUiComponent(component: UiComponent, scope: CompatScope): UiComponent =
        downgrade(component, scope.client.uiSchemaVersion, scope)

    override suspend fun interceptUiInvoke(
        scope: CompatScope,
        contributionId: String,
        actionId: String,
        payload: UiInvokePayload,
    ): UiInvokeResult? {
        if (!actionId.startsWith(SEARCH_ACTION_PREFIX)) return null
        if ((introducedIn[UiComponent.SearchField::class] ?: UiSchemaVersion.CURRENT) <= scope.client.uiSchemaVersion) {
            return null
        }
        val queryKey = queryKey(actionId.removePrefix(SEARCH_ACTION_PREFIX))
        val query = payload.values[queryKey]?.text.orEmpty()
        return UiInvokeResult(
            UiInvokeStatus.OK,
            next = UiAction.OpenPage(contributionId, payload.context.params + (queryKey to query)),
        )
    }

    private fun versionOf(component: UiComponent): Int = introducedIn[component::class] ?: UiSchemaVersion.CURRENT

    private fun versionOf(field: FieldDowngrade): Int =
        fieldIntroducedIn[field.owner]?.get(field.field) ?: UiSchemaVersion.CURRENT

    suspend fun downgrade(component: UiComponent, version: Int, scope: CompatScope? = null, formId: String? = null): UiComponent {
        if (versionOf(component) > version) {
            val legacy = replacements[component::class]?.build(component, scope, formId)
            return legacy?.let { downgrade(it, version, scope, formId) } ?: UiComponent.Fallback()
        }
        val enclosingForm = (component as? UiComponent.Form)?.id ?: formId
        return (withoutUnknownFields(component, version) as UiComponent)
            .mapOptions { withoutUnknownFields(it, version) as UiOption }
            .mapChildren { downgrade(it, version, scope, enclosingForm) }
    }

    private fun withoutUnknownFields(value: Any, version: Int): Any = fieldDowngrades.fold(value) { current, field ->
        if (field.owner == current::class && versionOf(field) > version) field.strip(current) else current
    }

    private class Replacement(
        val type: KClass<out UiComponent>,
        val build: suspend (UiComponent, CompatScope?, String?) -> UiComponent?,
    )

    private class FieldDowngrade(val owner: KClass<*>, val field: String, val strip: (Any) -> Any)

    private inline fun <reified T : UiComponent> replacement(
        noinline build: suspend (T, CompatScope?, String?) -> UiComponent?,
    ) = Replacement(T::class) { component, scope, formId -> build(component as T, scope, formId) }

    private inline fun <reified T : Any> stripField(field: String, noinline strip: (T) -> T) =
        FieldDowngrade(T::class, field) { strip(it as T) }

    private fun asTextField(field: UiComponent.FileField): UiComponent.TextField = UiComponent.TextField(
        key = field.key,
        label = field.label,
        value = field.value.takeUnless { field.secret },
        secret = field.secret,
        multiline = true,
        helper = if (field.binary) listOfNotNull(field.helper, BASE64_HINT).joinToString(" ") else field.helper,
        error = field.error,
        required = field.required,
        enabled = field.enabled,
        toolbar = field.toolbar,
    )

    private suspend fun asSearchColumn(
        field: UiComponent.SearchField,
        scope: CompatScope,
        formId: String,
    ): UiComponent.Column? {
        val user = scope.user ?: return null
        val contributionId = scope.contributionId ?: return null
        val registered = uiService.registry.get(contributionId) ?: return null
        if (registered.contribution.kind != UiContributionKind.PAGE) return null
        val queryKey = queryKey(field.key)
        val query = scope.context.params[queryKey]
        val results = if (query != null && query.length >= field.minChars) {
            uiService.search(user, scope.client, contributionId, field.key, query, scope.context, scope.call)
        } else {
            emptyList()
        }
        val selected = field.value?.takeIf { value -> results.none { it.value == value.value } }
        val t = translations.translator(UiRegistry.SERVER_SOURCE, scope.client.locale)
        return UiComponent.Column(
            listOf(
                UiComponent.TextField(
                    key = queryKey,
                    label = field.label,
                    value = query,
                    placeholder = field.placeholder,
                    helper = field.helper,
                    enabled = field.enabled,
                    toolbar = field.toolbar,
                ),
                UiComponent.Button(
                    label = t.t("ui.search.submit"),
                    action = UiAction.Invoke(contributionId, SEARCH_ACTION_PREFIX + field.key, formId = formId),
                    icon = UiIcon(UiIconName.SEARCH),
                    enabled = field.enabled,
                ),
                UiComponent.Select(
                    key = field.key,
                    label = t.t("ui.search.results"),
                    value = field.value?.value,
                    options = listOfNotNull(selected) + results,
                    error = field.error,
                    required = field.required,
                    enabled = field.enabled,
                ),
            ),
        )
    }

    companion object {
        const val IMAGE_FIELD = "image"
        const val BASE64_HINT = "Paste the file content as base64."
        const val SEARCH_ACTION_PREFIX = "ui.search."

        fun queryKey(key: String): String = "$key.query"
    }
}
