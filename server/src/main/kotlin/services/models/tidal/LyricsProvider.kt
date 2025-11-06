package dev.dertyp.services.models.tidal


sealed class LyricsProvider {

    abstract val name: String?
    abstract val source: Source?

    @Suppress("unused")
    enum class Source {
        TIDAL,
        THIRD_PARTY;
    }
}

