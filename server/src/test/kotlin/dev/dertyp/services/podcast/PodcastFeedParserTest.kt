package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastEpisodeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class PodcastFeedParserTest {
    private val now = 1_700_000_000_000L

    private fun parse(xml: String) = PodcastFeedParser.parse(xml.byteInputStream(), now)

    private fun rss(channelBody: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:podcast="https://podcastindex.org/namespace/1.0"
             xmlns:atom="http://www.w3.org/2005/Atom">
          <channel>
        $channelBody
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `full rss feed maps show and episode fields including itunes extensions`() {
        val feed = parse(
            rss(
                """
                <title>The Example Show</title>
                <description>A show about examples</description>
                <link>https://example.com/show</link>
                <language>en-us</language>
                <itunes:author>Example Author</itunes:author>
                <itunes:image href="https://cdn.example.com/cover.jpg"/>
                <itunes:explicit>yes</itunes:explicit>
                <item>
                  <guid isPermaLink="false">episode-guid-1</guid>
                  <title>First Episode</title>
                  <description>The first episode</description>
                  <link>https://example.com/episodes/1</link>
                  <pubDate>Tue, 10 Sep 2024 12:00:00 GMT</pubDate>
                  <itunes:duration>01:02:03</itunes:duration>
                  <itunes:season>2</itunes:season>
                  <itunes:episode>5</itunes:episode>
                  <itunes:episodeType>bonus</itunes:episodeType>
                  <itunes:explicit>true</itunes:explicit>
                  <itunes:image href="https://cdn.example.com/ep1.jpg"/>
                  <enclosure url="https://cdn.example.com/ep1.mp3" type="audio/mpeg" length="123456"/>
                  <podcast:transcript url="https://cdn.example.com/ep1.vtt" type="text/vtt" language="en" rel="captions"/>
                  <podcast:transcript url="https://cdn.example.com/ep1.json" type="application/json"/>
                  <podcast:transcript type="text/plain"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals("The Example Show", feed.show.title)
        assertEquals("A show about examples", feed.show.description)
        assertEquals("Example Author", feed.show.author)
        assertEquals("en-us", feed.show.language)
        assertEquals("https://example.com/show", feed.show.link)
        assertEquals("https://cdn.example.com/cover.jpg", feed.show.imageUrl)
        assertTrue(feed.show.explicit)
        assertNull(feed.newFeedUrl)

        assertEquals(1, feed.episodes.size)
        val episode = feed.episodes.single()
        assertEquals("episode-guid-1", episode.guid)
        assertEquals("First Episode", episode.title)
        assertEquals("The first episode", episode.description)
        assertEquals("https://example.com/episodes/1", episode.link)
        assertEquals(1725969600000L, episode.publishedAt)
        assertEquals(3723000L, episode.durationMs)
        assertEquals("https://cdn.example.com/ep1.mp3", episode.enclosureUrl)
        assertEquals("audio/mpeg", episode.enclosureType)
        assertEquals(123456L, episode.enclosureLength)
        assertEquals("https://cdn.example.com/ep1.jpg", episode.imageUrl)
        assertEquals(2, episode.seasonNumber)
        assertEquals(5, episode.episodeNumber)
        assertEquals(PodcastEpisodeType.BONUS, episode.episodeType)
        assertTrue(episode.explicit)

        assertEquals(2, episode.transcripts.size)
        val vtt = episode.transcripts.first()
        assertEquals("https://cdn.example.com/ep1.vtt", vtt.url)
        assertEquals("text/vtt", vtt.type)
        assertEquals("en", vtt.language)
        assertEquals("captions", vtt.rel)
        val json = episode.transcripts.last()
        assertEquals("https://cdn.example.com/ep1.json", json.url)
        assertEquals("application/json", json.type)
        assertNull(json.language)
        assertNull(json.rel)
    }

    @Test
    fun `cdata html description is kept raw`() {
        val feed = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>Episode</title>
                  <description><![CDATA[<p>Hello <b>world</b> &amp; more</p>]]></description>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals("<p>Hello <b>world</b> &amp; more</p>", feed.episodes.single().description)
    }

    @Test
    fun `content encoded wins over itunes summary which wins over description`() {
        val encoded = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>Episode</title>
                  <description>plain</description>
                  <itunes:summary>summary</itunes:summary>
                  <content:encoded><![CDATA[<p>rich</p>]]></content:encoded>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )
        assertEquals("<p>rich</p>", encoded.episodes.single().description)

        val summary = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>Episode</title>
                  <description>plain</description>
                  <itunes:summary>summary</itunes:summary>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )
        assertEquals("summary", summary.episodes.single().description)

        val plain = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>Episode</title>
                  <description>plain</description>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )
        assertEquals("plain", plain.episodes.single().description)
    }

    @Test
    fun `missing guid falls back to the enclosure url`() {
        val feed = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>Episode</title>
                  <enclosure url="https://cdn.example.com/no-guid.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals("https://cdn.example.com/no-guid.mp3", feed.episodes.single().guid)
    }

    @Test
    fun `guidFor prefers guid then enclosure url then link and title`() {
        assertEquals("guid", PodcastFeedParser.guidFor(" guid ", "https://cdn.example.com/a.mp3", "https://example.com/1", "Title"))
        assertEquals(
            "https://cdn.example.com/a.mp3",
            PodcastFeedParser.guidFor("   ", "https://cdn.example.com/a.mp3", "https://example.com/1", "Title")
        )
        assertEquals("https://example.com/1|Title", PodcastFeedParser.guidFor(null, "", "https://example.com/1", "Title"))
        assertEquals("|Title", PodcastFeedParser.guidFor(null, "", null, "Title"))
    }

    @Test
    fun `pubDate variants are parsed and garbage falls back to now`() {
        assertEquals(1725969600000L, PodcastFeedParser.parseRfc822("Tue, 10 Sep 2024 12:00:00 GMT", now))
        assertEquals(1725969600000L, PodcastFeedParser.parseRfc822("Tue, 10 Sep 2024 05:00:00 PDT", now))
        assertEquals(1725969600000L, PodcastFeedParser.parseRfc822("10 Sep 2024 12:00:00 +0000", now))
        assertEquals(1725969600000L, PodcastFeedParser.parseRfc822("2024-09-10T12:00:00Z", now))
        assertEquals(now, PodcastFeedParser.parseRfc822("last thursday", now))
        assertEquals(now, PodcastFeedParser.parseRfc822(null, now))
        assertEquals(now, PodcastFeedParser.parseRfc822("   ", now))
    }

    @Test
    fun `an item without a usable pubDate uses now`() {
        val feed = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>Episode</title>
                  <pubDate>whenever</pubDate>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals(now, feed.episodes.single().publishedAt)
    }

    @Test
    fun `itunes durations are parsed in every supported shape`() {
        assertEquals(3723000L, PodcastFeedParser.parseItunesDuration("01:02:03"))
        assertEquals(3723000L, PodcastFeedParser.parseItunesDuration("62:03"))
        assertEquals(3723000L, PodcastFeedParser.parseItunesDuration("3723"))
        assertEquals(3723500L, PodcastFeedParser.parseItunesDuration("3723.5"))
        assertEquals(754000L, PodcastFeedParser.parseItunesDuration(" 12:34 "))
        assertNull(PodcastFeedParser.parseItunesDuration("abc"))
        assertNull(PodcastFeedParser.parseItunesDuration(""))
        assertNull(PodcastFeedParser.parseItunesDuration(null))
        assertNull(PodcastFeedParser.parseItunesDuration("1:2:3:4"))
    }

    @Test
    fun `items without an enclosure are dropped`() {
        val feed = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <title>No audio</title>
                  <guid>no-audio</guid>
                </item>
                <item>
                  <title>With audio</title>
                  <guid>with-audio</guid>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>Blank enclosure</title>
                  <guid>blank</guid>
                  <enclosure url="   " type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals(listOf("with-audio"), feed.episodes.map { it.guid })
    }

    @Test
    fun `a duplicate guid keeps the first item`() {
        val feed = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <guid>same</guid>
                  <title>First</title>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <guid>same</guid>
                  <title>Second</title>
                  <enclosure url="https://cdn.example.com/2.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals(1, feed.episodes.size)
        assertEquals("First", feed.episodes.single().title)
        assertEquals("https://cdn.example.com/1.mp3", feed.episodes.single().enclosureUrl)
    }

    @Test
    fun `new feed url is surfaced`() {
        val feed = parse(
            rss(
                """
                <title>Show</title>
                <itunes:new-feed-url>https://example.com/moved.xml</itunes:new-feed-url>
                <item>
                  <guid>a</guid>
                  <title>Episode</title>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        )

        assertEquals("https://example.com/moved.xml", feed.newFeedUrl)
    }

    @Test
    fun `atom feeds are parsed through the enclosure link`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-gb">
              <title>Atom Show</title>
              <subtitle>Atom subtitle</subtitle>
              <logo>https://cdn.example.com/atom.png</logo>
              <link href="https://example.com/atom"/>
              <author><name>Atom Author</name></author>
              <entry>
                <id>atom-entry-1</id>
                <title>Atom Episode</title>
                <summary>Atom summary</summary>
                <published>2024-09-10T12:00:00Z</published>
                <link rel="alternate" href="https://example.com/atom/1"/>
                <link rel="enclosure" href="https://cdn.example.com/atom1.mp3" type="audio/mpeg" length="999"/>
              </entry>
              <entry>
                <id>atom-entry-2</id>
                <title>No audio</title>
              </entry>
            </feed>
            """.trimIndent()
        )

        assertEquals("Atom Show", feed.show.title)
        assertEquals("Atom subtitle", feed.show.description)
        assertEquals("Atom Author", feed.show.author)
        assertEquals("en-gb", feed.show.language)
        assertEquals("https://example.com/atom", feed.show.link)
        assertEquals("https://cdn.example.com/atom.png", feed.show.imageUrl)

        val episode = feed.episodes.single()
        assertEquals("atom-entry-1", episode.guid)
        assertEquals("Atom Episode", episode.title)
        assertEquals("Atom summary", episode.description)
        assertEquals("https://example.com/atom/1", episode.link)
        assertEquals(1725969600000L, episode.publishedAt)
        assertEquals("https://cdn.example.com/atom1.mp3", episode.enclosureUrl)
        assertEquals("audio/mpeg", episode.enclosureType)
        assertEquals(999L, episode.enclosureLength)
    }

    @Test
    fun `a byte order mark and leading whitespace are skipped`() {
        val xml = rss(
            """
            <title>BOM Show</title>
            <item>
              <guid>a</guid>
              <title>Episode</title>
              <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
            </item>
            """.trimIndent()
        )
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "\n   \t".toByteArray(Charsets.UTF_8) +
            xml.toByteArray(Charsets.UTF_8)

        val feed = PodcastFeedParser.parse(ByteArrayInputStream(bytes), now)

        assertEquals("BOM Show", feed.show.title)
        assertEquals(1, feed.episodes.size)
    }

    @Test
    fun `an external entity declaration never leaks file content`() {
        val directory = Files.createTempDirectory("podcast-xxe")
        val secretFile = directory.resolve("secret.txt")
        Files.write(secretFile, "SUPER_SECRET_MARKER".toByteArray(Charsets.UTF_8))

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file://${secretFile.toAbsolutePath()}">]>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>&xxe;</title>
                <item>
                  <guid>a</guid>
                  <title>&xxe;</title>
                  <description>&xxe;</description>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val result = runCatching { PodcastFeedParser.parse(xml.byteInputStream(), now) }

        val failure = result.exceptionOrNull()
        if (failure != null) {
            assertTrue(failure is FeedParseException, "Unexpected exception type: ${failure::class.qualifiedName}")
        } else {
            val feed = result.getOrThrow()
            val texts = buildList {
                add(feed.show.title)
                add(feed.show.description.orEmpty())
                feed.episodes.forEach {
                    add(it.title)
                    add(it.description.orEmpty())
                    add(it.guid)
                }
            }
            texts.forEach { assertFalse(it.contains("SUPER_SECRET_MARKER"), "External entity leaked into: $it") }
        }

        Files.deleteIfExists(secretFile)
        Files.deleteIfExists(directory)
    }

    @Test
    fun `a large feed with five thousand items is parsed completely`() {
        val items = buildString {
            repeat(5000) { index ->
                append(
                    """
                    <item>
                      <guid>episode-$index</guid>
                      <title>Episode $index</title>
                      <pubDate>Tue, 10 Sep 2024 12:00:00 GMT</pubDate>
                      <enclosure url="https://cdn.example.com/$index.mp3" type="audio/mpeg" length="$index"/>
                    </item>
                    """.trimIndent()
                )
            }
        }

        val feed = parse(rss("<title>Big Show</title>\n$items"))

        assertEquals(5000, feed.episodes.size)
        assertEquals(5000, feed.episodes.map { it.guid }.toSet().size)
        assertEquals("episode-0", feed.episodes.first().guid)
        assertEquals("episode-4999", feed.episodes.last().guid)
    }

    @Test
    fun `unknown vendor elements and nested unknown subtrees are skipped`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"
                 xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:vendor="urn:example:vendor">
              <channel>
                <title>Show</title>
                <vendor:block><vendor:inner><vendor:deep>ignored</vendor:deep></vendor:inner></vendor:block>
                <unknown><nested><deeper>ignored</deeper></nested></unknown>
                <item>
                  <guid>a</guid>
                  <vendor:meta id="1"><vendor:child><vendor:leaf>ignored</vendor:leaf></vendor:child></vendor:meta>
                  <unknownItem><nested>ignored</nested></unknownItem>
                  <title>Episode</title>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                  <itunes:keywords>a,b,c</itunes:keywords>
                </item>
              </channel>
            </rss>
            """.trimIndent()
        )

        assertEquals("Show", feed.show.title)
        val episode = feed.episodes.single()
        assertEquals("a", episode.guid)
        assertEquals("Episode", episode.title)
        assertEquals("https://cdn.example.com/a.mp3", episode.enclosureUrl)
        assertNull(episode.description)
    }

    @Test
    fun `explicit accepts yes true and explicit but not no`() {
        fun explicitOf(value: String): Boolean = parse(
            rss(
                """
                <title>Show</title>
                <item>
                  <guid>a</guid>
                  <title>Episode</title>
                  <itunes:explicit>$value</itunes:explicit>
                  <enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/>
                </item>
                """.trimIndent()
            )
        ).episodes.single().explicit

        assertTrue(explicitOf("yes"))
        assertTrue(explicitOf("true"))
        assertTrue(explicitOf("explicit"))
        assertTrue(explicitOf("YES"))
        assertFalse(explicitOf("no"))
        assertFalse(explicitOf("clean"))
    }

    @Test
    fun `an empty document is rejected`() {
        val failure = runCatching { PodcastFeedParser.parse("".byteInputStream(), now) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is FeedParseException)
    }
}
