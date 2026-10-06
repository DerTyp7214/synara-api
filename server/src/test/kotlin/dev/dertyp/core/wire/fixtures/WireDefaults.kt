package dev.dertyp.core.wire.fixtures

import kotlinx.rpc.annotations.Rpc

@Rpc
interface IWireDefaultsService {
    suspend fun annotate(page: Int = 3, note: String? = "none"): String

    suspend fun toggle(page: Int = 3, enabled: Boolean = true): String
}
