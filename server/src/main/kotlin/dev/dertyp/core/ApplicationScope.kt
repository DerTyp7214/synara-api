package dev.dertyp.core

import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object ApplicationScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = AppJson
    val cbor = AppCbor
}
