package dev.dertyp.services.ui

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val uiModule = module {
    singleOf(::UiRegistry)
    singleOf(::TranslationService)
    singleOf(::PluginSettingsService)
    singleOf(::UserHomeCardService)
    singleOf(::UiService)
    singleOf(::ImporterState)
    singleOf(::CoreUiContributions)
}
