package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UrlParserTest {

    @Test
    fun `YoutubeParser should parse various YouTube URLs`() = runBlocking {
        val parser = YoutubeParser()

        assertEquals("dQw4w9WgXcQ" to Type.SONG, parser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ" to Type.SONG, parser.parse("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ" to Type.SONG, parser.parse("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ" to Type.SONG, parser.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))

        assertEquals("PL12345" to Type.PLAYLIST, parser.parse("https://www.youtube.com/playlist?list=PL12345"))

        assertEquals("channel/UC123" to Type.ARTIST, parser.parse("https://www.youtube.com/channel/UC123"))
        assertEquals("user/testuser" to Type.ARTIST, parser.parse("https://www.youtube.com/user/testuser"))
        assertEquals("@testartist" to Type.ARTIST, parser.parse("https://www.youtube.com/@testartist"))

        assertNull(parser.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parser.parse("not a url"))
    }

    @Test
    fun `SoundcloudParser should parse various SoundCloud URLs`() = runBlocking {
        val parser = SoundcloudParser()

        assertEquals("artist/song" to Type.SONG, parser.parse("https://soundcloud.com/artist/song"))
        assertEquals("artist/song/more" to Type.SONG, parser.parse("https://soundcloud.com/artist/song/more"))

        assertEquals("artist/sets/playlist" to Type.PLAYLIST, parser.parse("https://soundcloud.com/artist/sets/playlist"))

        assertEquals("artist" to Type.ARTIST, parser.parse("https://soundcloud.com/artist"))
        assertEquals("artist" to Type.ARTIST, parser.parse("https://soundcloud.com/artist/reposts"))

        assertNull(parser.parse("https://example.com/artist/song"))
    }

    @Test
    fun `TidalParser should parse various Tidal URLs`() = runBlocking {
        val parser = TidalParser()

        assertEquals("12345" to Type.SONG, parser.parse("https://tidal.com/track/12345"))
        assertEquals("12345" to Type.SONG, parser.parse("https://tidal.com/browse/track/12345"))
        assertEquals("196091131" to Type.SONG, parser.parse("https://tidal.com/track/196091131"))
        assertEquals("11343638" to Type.SONG, parser.parse("https://tidal.com/browse/track/11343638"))

        assertEquals("67890" to Type.ALBUM, parser.parse("https://tidal.com/album/67890"))
        assertEquals("357676034" to Type.ALBUM, parser.parse("https://tidal.com/album/357676034"))
        assertEquals("130201923" to Type.ALBUM, parser.parse("https://tidal.com/browse/album/130201923"))
        assertEquals("11343637" to Type.ALBUM, parser.parse("https://listen.tidal.com/album/11343637"))
        assertEquals("301366648" to Type.ALBUM, parser.parse("https://listen.tidal.com/album/301366648/track/301366649"))

        assertEquals("abcde" to Type.PLAYLIST, parser.parse("https://tidal.com/playlist/abcde"))

        assertEquals("fghij" to Type.ARTIST, parser.parse("https://tidal.com/artist/fghij"))
        assertEquals("80" to Type.ARTIST, parser.parse("https://tidal.com/artist/80"))
        assertEquals("3557299" to Type.ARTIST, parser.parse("https://tidal.com/browse/artist/3557299"))
        assertEquals("116" to Type.ARTIST, parser.parse("https://listen.tidal.com/artist/116"))

        assertEquals("klmno" to Type.VIDEO, parser.parse("https://tidal.com/video/klmno"))
        assertEquals("358461354" to Type.VIDEO, parser.parse("https://tidal.com/video/358461354"))

        assertTrue(parser.canHandle("tiddl:12345"))
        assertTrue(parser.canHandle("tdn:12345"))
        assertTrue(parser.canHandle("tidal:12345"))
        
        assertEquals("12345" to Type.SONG, parser.parse("tiddl:12345"))
        assertEquals("12345" to Type.SONG, parser.parse("tdn:12345"))
        assertEquals("12345" to Type.SONG, parser.parse("tidal:12345"))

        assertNull(parser.parse("https://tidal.com/unknown/12345"))
    }

    @Test
    fun `ParserFactory should return correct parser for prefixed IDs`() {
        assertTrue(ParserFactory.getParser("youtube:123") is YoutubeParser)
        assertTrue(ParserFactory.getParser("soundcloud:123") is SoundcloudParser)
        assertTrue(ParserFactory.getParser("tidal:123") is TidalParser)
        assertTrue(ParserFactory.getParser("tiddl:123") is TidalParser)
        assertTrue(ParserFactory.getParser("tdn:123") is TidalParser)
        assertTrue(ParserFactory.getParser("spotify:123") is SpotifyParser)
        assertTrue(ParserFactory.getParser("musicbrainz:123") is MusicBrainzParser)
        assertTrue(ParserFactory.getParser("listenbrainz:123") is ListenBrainzParser)
        assertTrue(ParserFactory.getParser("amazon:123") is AmazonParser)
        assertTrue(ParserFactory.getParser("apple:123") is AppleMusicParser)
        assertTrue(ParserFactory.getParser("deezer:123") is DeezerParser)
        assertTrue(ParserFactory.getParser("yandex:123") is YandexParser)
        assertTrue(ParserFactory.getParser("pandora:123") is PandoraParser)
        assertTrue(ParserFactory.getParser("beatport:123") is BeatportParser)
        assertTrue(ParserFactory.getParser("boomplay:123") is BoomplayParser)
        assertTrue(ParserFactory.getParser("discogs:123") is DiscogsParser)
        assertTrue(ParserFactory.getParser("rateyourmusic:123") is RateYourMusicParser)
        assertTrue(ParserFactory.getParser("wikidata:123") is WikidataParser)
        assertTrue(ParserFactory.getParser("mora:123") is MoraParser)
        assertTrue(ParserFactory.getParser("napster:123") is NapsterParser)
        assertTrue(ParserFactory.getParser("qobuz:123") is QobuzParser)
        assertTrue(ParserFactory.getParser("anghami:123") is AnghamiParser)
        assertTrue(ParserFactory.getParser("livemixtapes:123") is LiveMixtapesParser)
        assertTrue(ParserFactory.getParser("musiksammler:123") is MusikSammlerParser)
    }

    @Test
    fun `SpotifyParser should parse various Spotify URLs`() = runBlocking {
        val parser = SpotifyParser()

        assertEquals("123" to Type.SONG, parser.parse("https://open.spotify.com/track/123"))
        assertEquals("123" to Type.SONG, parser.parse("https://open.spotify.com/episode/123"))

        assertEquals("456" to Type.ALBUM, parser.parse("https://open.spotify.com/album/456"))

        assertEquals("789" to Type.PLAYLIST, parser.parse("https://open.spotify.com/playlist/789"))
        assertEquals("789" to Type.PLAYLIST, parser.parse("https://open.spotify.com/show/789"))

        assertEquals("abc" to Type.ARTIST, parser.parse("https://open.spotify.com/artist/abc"))

        assertNull(parser.parse("https://open.spotify.com/unknown/123"))
    }

    @Test
    fun `MusicBrainzParser should parse various MusicBrainz URLs`() = runBlocking {
        val parser = MusicBrainzParser()

        assertEquals("rec-id" to Type.SONG, parser.parse("https://musicbrainz.org/recording/rec-id"))

        assertEquals("rel-id" to Type.ALBUM, parser.parse("https://musicbrainz.org/release/rel-id"))
        assertEquals("rg-id" to Type.ALBUM, parser.parse("https://musicbrainz.org/release-group/rg-id"))

        assertEquals("art-id" to Type.ARTIST, parser.parse("https://musicbrainz.org/artist/art-id"))

        assertEquals("ser-id" to Type.PLAYLIST, parser.parse("https://musicbrainz.org/series/ser-id"))

        assertNull(parser.parse("https://musicbrainz.org/work/work-id"))
    }

    @Test
    fun `ListenBrainzParser should parse various ListenBrainz URLs`() = runBlocking {
        val parser = ListenBrainzParser()

        assertEquals("art-id" to Type.ARTIST, parser.parse("https://listenbrainz.org/artist/art-id"))

        assertEquals("rg-id" to Type.ALBUM, parser.parse("https://listenbrainz.org/release-group/rg-id"))

        assertEquals("pl-id" to Type.PLAYLIST, parser.parse("https://listenbrainz.org/playlist/pl-id"))

        assertNull(parser.parse("https://listenbrainz.org/recording/rec-id"))
    }

    @Test
    fun `ParserFactory should return correct parser`() {
        assertTrue(ParserFactory.getParser("https://www.youtube.com/watch?v=123") is YoutubeParser)
        assertTrue(ParserFactory.getParser("https://soundcloud.com/artist/song") is SoundcloudParser)
        assertTrue(ParserFactory.getParser("https://tidal.com/track/123") is TidalParser)
        assertTrue(ParserFactory.getParser("https://open.spotify.com/track/123") is SpotifyParser)
        assertTrue(ParserFactory.getParser("https://musicbrainz.org/artist/123") is MusicBrainzParser)
        assertTrue(ParserFactory.getParser("https://listenbrainz.org/artist/123") is ListenBrainzParser)
        assertTrue(ParserFactory.getParser("https://www.amazon.de/dp/B01M4OCFDH") is AmazonParser)
        assertTrue(ParserFactory.getParser("https://music.apple.com/us/album/evermore/1544268285") is AppleMusicParser)
        assertTrue(ParserFactory.getParser("https://www.deezer.com/album/610328042") is DeezerParser)
        assertTrue(ParserFactory.getParser("https://music.yandex.ru/album/3882209") is YandexParser)
        assertTrue(ParserFactory.getParser("https://www.pandora.com/AL:11435696") is PandoraParser)
        assertTrue(ParserFactory.getParser("https://www.beatport.com/release/slug/1702043") is BeatportParser)
        assertTrue(ParserFactory.getParser("https://www.boomplay.com/albums/8411102") is BoomplayParser)
        assertTrue(ParserFactory.getParser("https://www.discogs.com/release/7049051") is DiscogsParser)
        assertTrue(ParserFactory.getParser("https://rateyourmusic.com/release/album/artist/title/") is RateYourMusicParser)
        assertTrue(ParserFactory.getParser("https://www.wikidata.org/wiki/Q127446878") is WikidataParser)
        assertTrue(ParserFactory.getParser("https://mora.jp/package/43000006/00602465618013/") is MoraParser)
        assertTrue(ParserFactory.getParser("https://play.napster.com/album/alb.595142205") is NapsterParser)
        assertTrue(ParserFactory.getParser("https://www.qobuz.com/album/slug/id") is QobuzParser)
        assertTrue(ParserFactory.getParser("https://play.anghami.com/album/4129825") is AnghamiParser)
        assertTrue(ParserFactory.getParser("https://www.livemixtapes.com/mixtapes/15113/slug.html") is LiveMixtapesParser)
        assertTrue(ParserFactory.getParser("https://www.musik-sammler.de/album/568467/") is MusikSammlerParser)
        
        assertNull(ParserFactory.getParser("https://example.com"))
    }

    @Test
    fun `ParserFactory should return parser for provider`() {
        assertTrue(ParserFactory.getParserForProvider("YouTube") is YoutubeParser)
        assertTrue(ParserFactory.getParserForProvider("soundcloud") is SoundcloudParser)
        assertTrue(ParserFactory.getParserForProvider("TIDAL") is TidalParser)
        assertTrue(ParserFactory.getParserForProvider("Spotify") is SpotifyParser)
        assertTrue(ParserFactory.getParserForProvider("MusicBrainz") is MusicBrainzParser)
        assertTrue(ParserFactory.getParserForProvider("ListenBrainz") is ListenBrainzParser)
        assertTrue(ParserFactory.getParserForProvider("amazon") is AmazonParser)
        assertTrue(ParserFactory.getParserForProvider("apple") is AppleMusicParser)
        assertTrue(ParserFactory.getParserForProvider("deezer") is DeezerParser)
        assertTrue(ParserFactory.getParserForProvider("yandex") is YandexParser)
        assertTrue(ParserFactory.getParserForProvider("pandora") is PandoraParser)
        assertTrue(ParserFactory.getParserForProvider("beatport") is BeatportParser)
        assertTrue(ParserFactory.getParserForProvider("boomplay") is BoomplayParser)
        assertTrue(ParserFactory.getParserForProvider("discogs") is DiscogsParser)
        assertTrue(ParserFactory.getParserForProvider("rateyourmusic") is RateYourMusicParser)
        assertTrue(ParserFactory.getParserForProvider("wikidata") is WikidataParser)
        assertTrue(ParserFactory.getParserForProvider("mora") is MoraParser)
        assertTrue(ParserFactory.getParserForProvider("napster") is NapsterParser)
        assertTrue(ParserFactory.getParserForProvider("qobuz") is QobuzParser)
        assertTrue(ParserFactory.getParserForProvider("anghami") is AnghamiParser)
        assertTrue(ParserFactory.getParserForProvider("livemixtapes") is LiveMixtapesParser)
        assertTrue(ParserFactory.getParserForProvider("musiksammler") is MusikSammlerParser)
        
        assertNull(ParserFactory.getParserForProvider("unknown"))
    }

    @Test
    fun `YoutubeParser should handle edge cases`() = runBlocking {
        val parser = YoutubeParser()
        assertNull(parser.parse("https://www.youtube.com/watch?id=123"))
        assertNull(parser.parse("https://www.youtube.com/watch"))
        assertNull(parser.parse("https://youtube.com"))
    }

    @Test
    fun `SoundcloudParser should handle edge cases`() = runBlocking {
        val parser = SoundcloudParser()
        assertNull(parser.parse("https://soundcloud.com"))
        assertNull(parser.parse("https://soundcloud.com/"))
        val result = parser.parse("https://soundcloud.com/a/b/c/d/e")
        assertNotNull(result)
        assertEquals("a/b/c/d/e", result?.first)
        assertEquals(Type.SONG, result?.second)
    }

    @Test
    fun `AmazonParser should parse various Amazon URLs`() = runBlocking {
        val parser = AmazonParser()

        assertEquals("B01M4OCFDH" to Type.ALBUM, parser.parse("https://www.amazon.de/gp/product/B01M4OCFDH"))
        assertEquals("B07HRCQCNB" to Type.ALBUM, parser.parse("https://amazon.com/dp/B07HRCQCNB"))
        assertEquals("B009YARQU4" to Type.ALBUM, parser.parse("https://music.amazon.com/albums/B009YARQU4"))
        assertEquals("B0064UPUDC" to Type.SONG, parser.parse("https://music.amazon.com/albums/B0064UPU4G?trackAsin=B0064UPUDC"))

        assertNull(parser.parse("https://example.com/dp/B01M4OCFDH"))
    }

    @Test
    fun `AppleMusicParser should parse various Apple Music URLs`() = runBlocking {
        val parser = AppleMusicParser()

        assertEquals("1544268285" to Type.ALBUM, parser.parse("https://music.apple.com/us/album/evermore/1544268285"))
        assertEquals("1544268286" to Type.SONG, parser.parse("https://music.apple.com/us/album/evermore/1544268285?i=1544268286"))
        assertEquals("1544520973" to Type.VIDEO, parser.parse("https://music.apple.com/us/music-video/willow/1544520973"))
        assertEquals("571919008" to Type.ALBUM, parser.parse("https://itunes.apple.com/de/album/id571919008"))
        assertEquals("571919008" to Type.ALBUM, parser.parse("https://geo.music.apple.com/us/album/_/571919008?mt=1"))

        assertNull(parser.parse("https://example.com/us/album/1544268285"))
    }

    @Test
    fun `DeezerParser should parse various Deezer URLs`() = runBlocking {
        val parser = DeezerParser()

        assertEquals("610328042" to Type.ALBUM, parser.parse("https://www.deezer.com/album/610328042"))
        assertEquals("242075822" to Type.ALBUM, parser.parse("https://www.deezer.com/album/242075822"))
        assertEquals("3135556" to Type.SONG, parser.parse("https://deezer.com/track/3135556"))

        assertNull(parser.parse("https://example.com/album/610328042"))
    }

    @Test
    fun `YandexParser should parse various Yandex URLs`() = runBlocking {
        val parser = YandexParser()

        assertEquals("3882209" to Type.ALBUM, parser.parse("https://music.yandex.ru/album/3882209"))
        assertEquals("62579582" to Type.SONG, parser.parse("https://music.yandex.ru/album/9881481/track/62579582"))

        assertNull(parser.parse("https://example.com/album/3882209"))
    }

    @Test
    fun `PandoraParser should parse various Pandora URLs`() = runBlocking {
        val parser = PandoraParser()

        assertEquals("AL:11435696" to Type.ALBUM, parser.parse("https://www.pandora.com/AL:11435696"))
        assertEquals("TR:11423273" to Type.SONG, parser.parse("https://pandora.com/track/name/TR:11423273"))

        assertNull(parser.parse("https://example.com/AL:11435696"))
    }

    @Test
    fun `BeatportParser should parse various Beatport URLs`() = runBlocking {
        val parser = BeatportParser()

        assertEquals("1702043" to Type.ALBUM, parser.parse("https://www.beatport.com/release/senses-overload-the-remixes/1702043"))
        assertEquals("23011269" to Type.SONG, parser.parse("https://www.beatport.com/track/greece-2000/23011269"))

        assertNull(parser.parse("https://example.com/release/1702043"))
    }

    @Test
    fun `BoomplayParser should parse various Boomplay URLs`() = runBlocking {
        val parser = BoomplayParser()

        assertEquals("8411102" to Type.ALBUM, parser.parse("https://www.boomplay.com/albums/8411102"))
        assertEquals("74767514" to Type.SONG, parser.parse("https://www.boomplay.com/songs/74767514"))
        assertEquals("40002743" to Type.ALBUM, parser.parse("https://www.boomplay.com/share/album/40002743"))

        assertNull(parser.parse("https://example.com/albums/8411102"))
    }

    @Test
    fun `DiscogsParser should parse various Discogs URLs`() = runBlocking {
        val parser = DiscogsParser()

        assertEquals("7049051" to Type.ALBUM, parser.parse("https://www.discogs.com/release/7049051"))
        assertEquals("26647" to Type.ALBUM, parser.parse("https://www.discogs.com/master/26647-Daft-Punk-Discovery"))

        assertNull(parser.parse("https://example.com/release/7049051"))
    }

    @Test
    fun `RateYourMusicParser should parse various RYM URLs`() = runBlocking {
        val parser = RateYourMusicParser()

        assertEquals("album/achtvier-bonez-mc/zwei-assis-trumpfen-aus" to Type.ALBUM, parser.parse("https://rateyourmusic.com/release/album/achtvier-bonez-mc/zwei-assis-trumpfen-aus/"))
        assertEquals("single/artist/title" to Type.ALBUM, parser.parse("https://rateyourmusic.com/release/single/artist/title/"))

        assertNull(parser.parse("https://example.com/release/album/artist/title"))
    }

    @Test
    fun `WikidataParser should parse various Wikidata URLs`() = runBlocking {
        val parser = WikidataParser()

        assertEquals("Q127446878" to Type.ALBUM, parser.parse("https://www.wikidata.org/wiki/Q127446878"))

        assertNull(parser.parse("https://example.com/wiki/Q127446878"))
    }

    @Test
    fun `MoraParser should parse various Mora URLs`() = runBlocking {
        val parser = MoraParser()

        assertEquals("00602465618013" to Type.ALBUM, parser.parse("https://mora.jp/package/43000006/00602465618013/"))
        assertEquals("00602465617924" to Type.SONG, parser.parse("https://mora.jp/track/43000006/00602465617924/1/"))

        assertNull(parser.parse("https://example.com/package/123/456"))
    }

    @Test
    fun `NapsterParser should parse various Napster URLs`() = runBlocking {
        val parser = NapsterParser()

        assertEquals("alb.595142205" to Type.ALBUM, parser.parse("https://play.napster.com/album/alb.595142205"))
        assertEquals("tra.123" to Type.SONG, parser.parse("https://web.napster.com/track/tra.123"))

        assertNull(parser.parse("https://example.com/album/alb.123"))
    }

    @Test
    fun `QobuzParser should parse various Qobuz URLs`() = runBlocking {
        val parser = QobuzParser()

        assertEquals("yxz0pt2qy7jhb" to Type.ALBUM, parser.parse("https://www.qobuz.com/de-de/album/vulcano-ep-bonez-mc-raf-camora/yxz0pt2qy7jhb"))
        assertEquals("12345" to Type.SONG, parser.parse("https://open.qobuz.com/track/12345"))

        assertNull(parser.parse("https://example.com/album/123"))
    }

    @Test
    fun `AnghamiParser should parse various Anghami URLs`() = runBlocking {
        val parser = AnghamiParser()

        assertEquals("4129825" to Type.ALBUM, parser.parse("https://play.anghami.com/album/4129825?refer=linktree"))
        assertEquals("1267509588" to Type.SONG, parser.parse("https://play.anghami.com/song/1267509588"))

        assertNull(parser.parse("https://example.com/album/123"))
    }

    @Test
    fun `LiveMixtapesParser should parse various LiveMixtapes URLs`() = runBlocking {
        val parser = LiveMixtapesParser()

        assertEquals("15113" to Type.ALBUM, parser.parse("https://www.livemixtapes.com/mixtapes/15113/stuey_rock_future_fdu_free_bandz_reloaded.html"))
        assertEquals("123" to Type.ALBUM, parser.parse("https://www.livemixtapes.com/download/123/slug.html"))

        assertNull(parser.parse("https://example.com/mixtapes/123/slug"))
    }

    @Test
    fun `MusikSammlerParser should parse various MusikSammler URLs`() = runBlocking {
        val parser = MusikSammlerParser()

        assertEquals("568467" to Type.ALBUM, parser.parse("https://www.musik-sammler.de/album/568467/"))

        assertNull(parser.parse("https://example.com/album/123"))
    }
}
