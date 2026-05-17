package dev.dertyp.utils.parsers

object ParserFactory {
    private val parsers = listOf(
        YoutubeParser(),
        SoundcloudParser(),
        TidalParser(),
        SpotifyParser(),
        MusicBrainzParser(),
        ListenBrainzParser(),
        AmazonParser(),
        AppleMusicParser(),
        DeezerParser(),
        YandexParser(),
        PandoraParser(),
        BeatportParser(),
        BoomplayParser(),
        DiscogsParser(),
        RateYourMusicParser(),
        WikidataParser(),
        MoraParser(),
        NapsterParser(),
        QobuzParser(),
        AnghamiParser(),
        LiveMixtapesParser(),
        MusikSammlerParser()
    )

    fun getParser(url: String): UrlParser? {
        if (url.contains(":") && !url.contains("://")) {
            val provider = url.substringBefore(":")
            return getParserForProvider(provider)
        }
        return parsers.find { it.canHandle(url) }
    }

    fun getParserForProvider(provider: String): UrlParser? {
        return parsers.find {
            it.name.equals(provider, ignoreCase = true) ||
                    it.alternativeNames.any { alt -> alt.equals(provider, ignoreCase = true) }
        }
    }
}
