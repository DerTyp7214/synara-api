package dev.dertyp.utils

import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiOption

inline fun UiComponent.mapOptions(transform: (UiOption) -> UiOption): UiComponent = when (this) {
    is UiComponent.Select -> copy(options = options.map(transform))
    is UiComponent.SearchField -> copy(value = value?.let(transform))
    else -> this
}

inline fun UiComponent.mapChildren(transform: (UiComponent) -> UiComponent): UiComponent = when (this) {
    is UiComponent.Column -> copy(children = children.map(transform))
    is UiComponent.Row -> copy(children = children.map(transform))
    is UiComponent.Grid -> copy(children = children.map(transform))
    is UiComponent.Card -> copy(children = children.map(transform), actions = actions.map(transform))
    is UiComponent.Section -> copy(children = children.map(transform))
    is UiComponent.Form -> copy(children = children.map(transform), actions = actions.map(transform))
    is UiComponent.Native -> copy(fallback = fallback?.let(transform))
    is UiComponent.Live -> copy(child = transform(child))
    is UiComponent.EmptyState -> copy(actions = actions.map(transform))
    is UiComponent.TextField -> copy(toolbar = toolbar.map(transform))
    is UiComponent.NumberField -> copy(toolbar = toolbar.map(transform))
    is UiComponent.FileField -> copy(toolbar = toolbar.map(transform))
    is UiComponent.SearchField -> copy(toolbar = toolbar.map(transform))
    else -> this
}
