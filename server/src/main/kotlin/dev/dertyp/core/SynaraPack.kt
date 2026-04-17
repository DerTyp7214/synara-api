package dev.dertyp.core

import dev.dertyp.serializers.SynaraNegotiation
import dev.dertyp.serializers.SynaraPackHeader
import io.ktor.server.request.header
import kotlinx.rpc.krpc.ktor.server.KrpcRoute

fun KrpcRoute.withSynaraPack() {
    val enabled = call.request.header(SynaraPackHeader) == "true"
    SynaraNegotiation.isEnabled = enabled
}
