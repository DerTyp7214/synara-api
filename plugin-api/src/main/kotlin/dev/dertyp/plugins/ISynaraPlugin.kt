package dev.dertyp.plugins

import org.koin.core.module.Module

interface ISynaraPlugin {
    val id: String
    val name: String
    val apiVersion: Int get() = 1
    val enabled: Boolean get() = true
    val hookGroups: Set<HookGroup> get() = emptySet()

    fun init(context: PluginContext)

    fun getKoinModule(): Module? = null
}
