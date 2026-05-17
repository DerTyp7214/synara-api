package dev.dertyp.utils.parsers

import dev.dertyp.services.import.Type
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.reflect.KClass

class UrlParserTest {

    enum class ParserTestData(
        val parserClass: KClass<out UrlParser>,
        val testCases: List<Pair<String, Pair<String, Type>?>>
    ) {
        YOUTUBE(YoutubeParser::class, listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to ("dQw4w9WgXcQ" to Type.SONG),
            "https://youtu.be/dQw4w9WgXcQ" to ("dQw4w9WgXcQ" to Type.SONG),
            "https://www.youtube.com/shorts/dQw4w9WgXcQ" to ("dQw4w9WgXcQ" to Type.SONG),
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ" to ("dQw4w9WgXcQ" to Type.SONG),
            "https://www.youtube.com/playlist?list=PL12345" to ("PL12345" to Type.PLAYLIST),
            "https://www.youtube.com/channel/UC123" to ("channel/UC123" to Type.ARTIST),
            "https://www.youtube.com/user/testuser" to ("user/testuser" to Type.ARTIST),
            "https://www.youtube.com/@testartist" to ("@testartist" to Type.ARTIST),
            "https://example.com/watch?v=dQw4w9WgXcQ" to null,
            "not a url" to null
        )),
        SOUNDCLOUD(SoundcloudParser::class, listOf(
            "https://soundcloud.com/artist/song" to ("artist/song" to Type.SONG),
            "https://soundcloud.com/artist/song/more" to ("artist/song/more" to Type.SONG),
            "https://soundcloud.com/artist/sets/playlist" to ("artist/sets/playlist" to Type.PLAYLIST),
            "https://soundcloud.com/artist" to ("artist" to Type.ARTIST),
            "https://soundcloud.com/artist/reposts" to ("artist" to Type.ARTIST),
            "https://example.com/artist/song" to null
        )),
        TIDAL(TidalParser::class, listOf(
            "https://tidal.com/track/12345" to ("12345" to Type.SONG),
            "https://tidal.com/browse/track/12345" to ("12345" to Type.SONG),
            "https://tidal.com/album/67890" to ("67890" to Type.ALBUM),
            "https://listen.tidal.com/album/11343637" to ("11343637" to Type.ALBUM),
            "https://tidal.com/playlist/abcde" to ("abcde" to Type.PLAYLIST),
            "https://tidal.com/artist/fghij" to ("fghij" to Type.ARTIST),
            "https://tidal.com/video/klmno" to ("klmno" to Type.VIDEO),
            "tiddl:12345" to ("12345" to Type.SONG),
            "tdn:12345" to ("12345" to Type.SONG),
            "tidal:12345" to ("12345" to Type.SONG)
        )),
        SPOTIFY(SpotifyParser::class, listOf(
            "https://open.spotify.com/track/123" to ("123" to Type.SONG),
            "https://open.spotify.com/episode/123" to ("123" to Type.SONG),
            "https://open.spotify.com/album/456" to ("456" to Type.ALBUM),
            "https://open.spotify.com/playlist/789" to ("789" to Type.PLAYLIST),
            "https://open.spotify.com/show/789" to ("789" to Type.PLAYLIST),
            "https://open.spotify.com/artist/abc" to ("abc" to Type.ARTIST),
            "spotify:123" to ("123" to Type.SONG)
        )),
        MUSICBRAINZ(MusicBrainzParser::class, listOf(
            "https://musicbrainz.org/recording/rec-id" to ("rec-id" to Type.SONG),
            "https://musicbrainz.org/release/rel-id" to ("rel-id" to Type.ALBUM),
            "https://musicbrainz.org/release-group/rg-id" to ("rg-id" to Type.ALBUM),
            "https://musicbrainz.org/artist/art-id" to ("art-id" to Type.ARTIST),
            "https://musicbrainz.org/series/ser-id" to ("ser-id" to Type.PLAYLIST)
        )),
        LISTENBRAINZ(ListenBrainzParser::class, listOf(
            "https://listenbrainz.org/artist/art-id" to ("art-id" to Type.ARTIST),
            "https://listenbrainz.org/release-group/rg-id" to ("rg-id" to Type.ALBUM),
            "https://listenbrainz.org/playlist/pl-id" to ("pl-id" to Type.PLAYLIST)
        )),
        AMAZON(AmazonParser::class, listOf(
            "https://www.amazon.de/gp/product/B01M4OCFDH" to ("B01M4OCFDH" to Type.ALBUM),
            "https://amazon.com/dp/B07HRCQCNB" to ("B07HRCQCNB" to Type.ALBUM),
            "https://music.amazon.com/albums/B009YARQU4" to ("B009YARQU4" to Type.ALBUM),
            "https://music.amazon.com/albums/B0064UPU4G?trackAsin=B0064UPUDC" to ("B0064UPUDC" to Type.SONG)
        )),
        APPLE(AppleMusicParser::class, listOf(
            "https://music.apple.com/us/album/evermore/1544268285" to ("1544268285" to Type.ALBUM),
            "https://music.apple.com/us/album/evermore/1544268285?i=1544268286" to ("1544268286" to Type.SONG),
            "https://music.apple.com/us/music-video/willow/1544520973" to ("1544520973" to Type.VIDEO),
            "https://itunes.apple.com/de/album/id571919008" to ("571919008" to Type.ALBUM),
            "https://geo.music.apple.com/us/album/_/571919008?mt=1" to ("571919008" to Type.ALBUM)
        )),
        DEEZER(DeezerParser::class, listOf(
            "https://www.deezer.com/album/610328042" to ("610328042" to Type.ALBUM),
            "https://deezer.com/track/3135556" to ("3135556" to Type.SONG)
        )),
        YANDEX(YandexParser::class, listOf(
            "https://music.yandex.ru/album/3882209" to ("3882209" to Type.ALBUM),
            "https://music.yandex.ru/album/9881481/track/62579582" to ("62579582" to Type.SONG)
        )),
        PANDORA(PandoraParser::class, listOf(
            "https://www.pandora.com/AL:11435696" to ("AL:11435696" to Type.ALBUM),
            "https://pandora.com/track/name/TR:11423273" to ("TR:11423273" to Type.SONG)
        )),
        BEATPORT(BeatportParser::class, listOf(
            "https://www.beatport.com/release/slug/1702043" to ("1702043" to Type.ALBUM),
            "https://www.beatport.com/track/slug/23011269" to ("23011269" to Type.SONG)
        )),
        BOOMPLAY(BoomplayParser::class, listOf(
            "https://www.boomplay.com/albums/8411102" to ("8411102" to Type.ALBUM),
            "https://www.boomplay.com/songs/74767514" to ("74767514" to Type.SONG),
            "https://www.boomplay.com/share/album/40002743" to ("40002743" to Type.ALBUM)
        )),
        DISCOGS(DiscogsParser::class, listOf(
            "https://www.discogs.com/release/7049051" to ("7049051" to Type.ALBUM),
            "https://www.discogs.com/master/26647-slug" to ("26647" to Type.ALBUM)
        )),
        RATEYOURMUSIC(RateYourMusicParser::class, listOf(
            "https://rateyourmusic.com/release/album/achtvier-bonez-mc/zwei-assis-trumpfen-aus/" to ("album/achtvier-bonez-mc/zwei-assis-trumpfen-aus" to Type.ALBUM)
        )),
        WIKIDATA(WikidataParser::class, listOf(
            "https://www.wikidata.org/wiki/Q127446878" to ("Q127446878" to Type.ALBUM)
        )),
        MORA(MoraParser::class, listOf(
            "https://mora.jp/package/43000006/00602465618013/" to ("00602465618013" to Type.ALBUM),
            "https://mora.jp/track/43000006/00602465617924/1/" to ("00602465617924" to Type.SONG)
        )),
        NAPSTER(NapsterParser::class, listOf(
            "https://play.napster.com/album/alb.595142205" to ("alb.595142205" to Type.ALBUM),
            "https://web.napster.com/track/tra.123" to ("tra.123" to Type.SONG)
        )),
        QOBUZ(QobuzParser::class, listOf(
            "https://www.qobuz.com/de-de/album/vulcano-ep-bonez-mc-raf-camora/yxz0pt2qy7jhb" to ("yxz0pt2qy7jhb" to Type.ALBUM),
            "https://open.qobuz.com/track/12345" to ("12345" to Type.SONG)
        )),
        ANGHAMI(AnghamiParser::class, listOf(
            "https://play.anghami.com/album/4129825" to ("4129825" to Type.ALBUM),
            "https://play.anghami.com/song/1267509588" to ("1267509588" to Type.SONG)
        )),
        LIVEMIXTAPES(LiveMixtapesParser::class, listOf(
            "https://www.livemixtapes.com/mixtapes/15113/slug.html" to ("15113" to Type.ALBUM),
            "https://www.livemixtapes.com/download/123/slug.html" to ("123" to Type.ALBUM)
        )),
        MUSIKSAMMLER(MusikSammlerParser::class, listOf(
            "https://www.musik-sammler.de/album/568467/" to ("568467" to Type.ALBUM)
        ));

        val parser: UrlParser by lazy { parserClass.java.getDeclaredConstructor().newInstance() }
    }

    @ParameterizedTest
    @EnumSource(ParserTestData::class)
    fun `Parser should correctly parse various URLs`(data: ParserTestData) = runBlocking {
        for ((url, expected) in data.testCases) {
            val result = data.parser.parse(url)
            assertEquals(expected, result, "Failed to parse $url with ${data.parser.name}")
        }
    }

    @Test
    fun `ParserFactory should return correct parser instance`() {
        val testCases = mapOf(
            "youtube:123" to YoutubeParser::class,
            "https://www.youtube.com/watch?v=123" to YoutubeParser::class,
            "soundcloud:123" to SoundcloudParser::class,
            "https://soundcloud.com/artist/song" to SoundcloudParser::class,
            "tidal:123" to TidalParser::class,
            "tiddl:123" to TidalParser::class,
            "https://tidal.com/track/123" to TidalParser::class,
            "spotify:123" to SpotifyParser::class,
            "https://open.spotify.com/track/123" to SpotifyParser::class,
            "musicbrainz:123" to MusicBrainzParser::class,
            "https://musicbrainz.org/artist/123" to MusicBrainzParser::class,
            "listenbrainz:123" to ListenBrainzParser::class,
            "https://listenbrainz.org/artist/123" to ListenBrainzParser::class,
            "amazon:123" to AmazonParser::class,
            "https://www.amazon.de/dp/B01M4OCFDH" to AmazonParser::class,
            "apple:123" to AppleMusicParser::class,
            "https://music.apple.com/us/album/123" to AppleMusicParser::class,
            "deezer:123" to DeezerParser::class,
            "https://www.deezer.com/album/123" to DeezerParser::class,
            "yandex:123" to YandexParser::class,
            "https://music.yandex.ru/album/123" to YandexParser::class,
            "pandora:123" to PandoraParser::class,
            "https://www.pandora.com/AL:123" to PandoraParser::class,
            "beatport:123" to BeatportParser::class,
            "https://www.beatport.com/release/slug/123" to BeatportParser::class,
            "boomplay:123" to BoomplayParser::class,
            "https://www.boomplay.com/albums/123" to BoomplayParser::class,
            "discogs:123" to DiscogsParser::class,
            "https://www.discogs.com/release/123" to DiscogsParser::class,
            "rateyourmusic:123" to RateYourMusicParser::class,
            "https://rateyourmusic.com/release/album/a/b/" to RateYourMusicParser::class,
            "wikidata:123" to WikidataParser::class,
            "https://www.wikidata.org/wiki/Q123" to WikidataParser::class,
            "mora:123" to MoraParser::class,
            "https://mora.jp/package/1/123/" to MoraParser::class,
            "napster:123" to NapsterParser::class,
            "https://play.napster.com/album/alb.123" to NapsterParser::class,
            "qobuz:123" to QobuzParser::class,
            "https://www.qobuz.com/album/slug/id" to QobuzParser::class,
            "anghami:123" to AnghamiParser::class,
            "https://play.anghami.com/album/123" to AnghamiParser::class,
            "livemixtapes:123" to LiveMixtapesParser::class,
            "https://www.livemixtapes.com/mixtapes/123/slug.html" to LiveMixtapesParser::class,
            "musiksammler:123" to MusikSammlerParser::class,
            "https://www.musik-sammler.de/album/123/" to MusikSammlerParser::class
        )

        for ((input, expectedClass) in testCases) {
            val parser = ParserFactory.getParser(input)
            assertNotNull(parser, "No parser found for $input")
            assertTrue(expectedClass.isInstance(parser), "Expected ${expectedClass.simpleName} for $input, but got ${parser?.javaClass?.simpleName}")
        }
        
        assertNull(ParserFactory.getParser("https://example.com"))
    }

    @Test
    fun `ParserFactory should return parser for provider name`() {
        val providers = mapOf(
            "YouTube" to YoutubeParser::class,
            "soundcloud" to SoundcloudParser::class,
            "TIDAL" to TidalParser::class,
            "tiddl" to TidalParser::class,
            "tdn" to TidalParser::class,
            "Spotify" to SpotifyParser::class,
            "MusicBrainz" to MusicBrainzParser::class,
            "ListenBrainz" to ListenBrainzParser::class,
            "amazon" to AmazonParser::class,
            "apple" to AppleMusicParser::class,
            "itunes" to AppleMusicParser::class,
            "deezer" to DeezerParser::class,
            "yandex" to YandexParser::class,
            "pandora" to PandoraParser::class,
            "beatport" to BeatportParser::class,
            "boomplay" to BoomplayParser::class,
            "discogs" to DiscogsParser::class,
            "rateyourmusic" to RateYourMusicParser::class,
            "rym" to RateYourMusicParser::class,
            "wikidata" to WikidataParser::class,
            "mora" to MoraParser::class,
            "napster" to NapsterParser::class,
            "qobuz" to QobuzParser::class,
            "anghami" to AnghamiParser::class,
            "livemixtapes" to LiveMixtapesParser::class,
            "musiksammler" to MusikSammlerParser::class
        )

        for ((name, expectedClass) in providers) {
            val parser = ParserFactory.getParserForProvider(name)
            assertNotNull(parser, "No parser found for provider $name")
            assertTrue(expectedClass.isInstance(parser), "Expected ${expectedClass.simpleName} for $name")
        }
        
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
        assertEquals("a/b/c/d/e" to Type.SONG, result)
    }
}
