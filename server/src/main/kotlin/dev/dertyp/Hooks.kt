package dev.dertyp

import dev.dertyp.services.HookService
import dev.dertyp.services.HookSubscriber
import io.ktor.server.application.Application
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject

fun Application.configureHooks() {
    val hooks by inject<HookService>()
    getKoin().getAll<HookSubscriber>().forEach { it.subscribe(hooks) }
}
