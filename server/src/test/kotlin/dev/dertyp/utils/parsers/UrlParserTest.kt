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
        assertNotNull(ParserFactory.getParser("https://www.youtube.com/watch?v=123"))
        assertNotNull(ParserFactory.getParser("https://soundcloud.com/artist/song"))
        assertNotNull(ParserFactory.getParser("https://tidal.com/track/123"))
        assertNotNull(ParserFactory.getParser("https://open.spotify.com/track/123"))
        assertNotNull(ParserFactory.getParser("https://musicbrainz.org/artist/123"))
        assertNotNull(ParserFactory.getParser("https://listenbrainz.org/artist/123"))
        
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
}
