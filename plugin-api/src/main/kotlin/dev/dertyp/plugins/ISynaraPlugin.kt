package dev.dertyp.plugins

import org.koin.core.module.Module

interface ISynaraPlugin {
    val id: String
    val name: String
    val apiVersion: Int get() = 1
    val enabled: Boolean get() = true

    fun init(context: PluginContext)

    fun getKoinModule(): Module? = null
}
