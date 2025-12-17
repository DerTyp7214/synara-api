package dev.dertyp.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

object ApplicationScope {
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        prettyPrint = false
        useArrayPolymorphism = false
        ignoreUnknownKeys = true
    }
}