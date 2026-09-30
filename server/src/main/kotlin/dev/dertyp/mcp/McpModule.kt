package dev.dertyp.mcp

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val mcpModule = module {
    singleOf(::ListenHistoryQueryService)
    singleOf(::ListenHistoryMcpServerFactory)
}
