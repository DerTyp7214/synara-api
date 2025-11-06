package dev.dertyp.services.models.tidal


class Tidal(
    override val name: String? = null,
    override val source: Source? = null
) : LyricsProvider()