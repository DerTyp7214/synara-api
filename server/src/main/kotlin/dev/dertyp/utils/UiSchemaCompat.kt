package dev.dertyp.utils

import dev.dertyp.core.ClientFeature
import dev.dertyp.core.ClientInfo
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiSchema
import dev.dertyp.ui.UiSchemaVersion
import kotlin.reflect.KClass

class UiSchemaCompat(
    private val introducedIn: Map<KClass<out UiComponent>, Int> = UiSchema.introducedIn,
) : CompatRule {
    override val feature = ClientFeature.SERVER_DRIVEN_UI

    private val latestVersion = introducedIn.values.maxOrNull() ?: UiSchemaVersion.CURRENT

    override fun isActive(client: ClientInfo): Boolean = client.uiSchemaVersion < latestVersion

    override fun shapeUiComponent(component: UiComponent, client: ClientInfo): UiComponent =
        downgrade(component, client.uiSchemaVersion)

    private fun versionOf(component: UiComponent): Int = introducedIn[component::class] ?: UiSchemaVersion.CURRENT

    fun downgrade(component: UiComponent, version: Int): UiComponent {
        if (component is UiComponent.FileField && versionOf(component) > version) return downgrade(
            asTextField(component),
            version
        )
        if (versionOf(component) > version) return UiComponent.Fallback()
        return component.mapChildren { downgrade(it, version) }
    }

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

    companion object {
        const val BASE64_HINT = "Paste the file content as base64."
    }
}
