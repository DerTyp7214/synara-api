package dev.dertyp.core

import java.util.Properties

object BuildInfo {
    private const val RESOURCE = "/build-info.properties"
    private const val UNPACKAGED = "dev"

    private val properties = Properties().apply {
        BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { load(it) }
    }

    val buildTime: String = properties.getProperty("buildTime", UNPACKAGED)
    val gitHash: String = properties.getProperty("gitHash", UNPACKAGED)
}
