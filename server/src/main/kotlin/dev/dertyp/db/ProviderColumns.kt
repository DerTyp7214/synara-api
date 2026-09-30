package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Column

interface ProviderColumns {
    val provider: Column<String>
    val externalId: Column<String>
    val type: Column<String?>
    val rawUrl: Column<String>
}
