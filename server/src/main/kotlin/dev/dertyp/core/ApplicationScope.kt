package dev.dertyp.core

import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
object ApplicationScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = AppJson
    val cbor = AppCbor

    val rpcConfig = rpcServerConfig {
        serialization {
            cbor(AppCbor)
        }
    }
}
