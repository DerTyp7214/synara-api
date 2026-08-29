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

    fun downgrade(component: UiComponent, version: Int): UiComponent {
        if ((introducedIn[component::class] ?: UiSchemaVersion.CURRENT) > version) return UiComponent.Fallback()
        val shape: (UiComponent) -> UiComponent = { downgrade(it, version) }
        return when (component) {
            is UiComponent.Column -> component.copy(children = component.children.map(shape))
            is UiComponent.Row -> component.copy(children = component.children.map(shape))
            is UiComponent.Grid -> component.copy(children = component.children.map(shape))
            is UiComponent.Card -> component.copy(children = component.children.map(shape), actions = component.actions.map(shape))
            is UiComponent.Section -> component.copy(children = component.children.map(shape))
            is UiComponent.Form -> component.copy(children = component.children.map(shape), actions = component.actions.map(shape))
            is UiComponent.Native -> component.copy(fallback = component.fallback?.let(shape))
            is UiComponent.Live -> component.copy(child = shape(component.child))
            is UiComponent.EmptyState -> component.copy(actions = component.actions.map(shape))
            is UiComponent.TextField -> component.copy(toolbar = component.toolbar.map(shape))
            else -> component
        }
    }
}
