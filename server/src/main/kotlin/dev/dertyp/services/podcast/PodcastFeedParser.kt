package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastEpisodeType
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

object PodcastFeedParser {
    private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val NS_ATOM = "http://www.w3.org/2005/Atom"

    private val PODCAST_NAMESPACES = setOf(
        "https://podcastindex.org/namespace/1.0",
        "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md"
    )

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
    )

    private val ZONE_OFFSETS = mapOf(
        "PST" to "-0800",
        "PDT" to "-0700",
        "EST" to "-0500",
        "EDT" to "-0400",
        "CST" to "-0600",
        "CDT" to "-0500",
        "MST" to "-0700",
        "MDT" to "-0600",
        "GMT" to "+0000",
        "UT" to "+0000",
        "UTC" to "+0000",
        "Z" to "+0000"
    )

    private val TRUE_VALUES = setOf("yes", "true", "explicit")

    fun parse(input: InputStream, now: Long = System.currentTimeMillis()): ParsedFeed {
        val reader = try {
            newFactory().createXMLStreamReader(skipLeadingBytes(input))
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            throw FeedParseException(e.message ?: "Feed could not be read", e)
        }

        return try {
            readDocument(reader, now)
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            throw FeedParseException(e.message ?: "Feed could not be parsed", e)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun newFactory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    private fun skipLeadingBytes(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        while (true) {
            buffered.mark(1)
            val next = buffered.read()
            if (next == -1) throw FeedParseException("Feed document is empty")
            if (next == '<'.code) {
                buffered.reset()
                return buffered
            }
        }
    }

    private fun readDocument(reader: XMLStreamReader, now: Long): ParsedFeed {
        while (reader.hasNext()) {
            if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
            return when (reader.localName) {
                "rss", "RDF" -> parseRss(reader, now)
                "channel" -> parseChannel(reader, now)
                "feed" -> parseAtom(reader, now)
                else -> throw FeedParseException("Unsupported feed root element: ${reader.localName}")
            }
        }
        throw FeedParseException("Feed document has no root element")
    }

    private fun parseRss(reader: XMLStreamReader, now: Long): ParsedFeed {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (reader.localName == "channel") return parseChannel(reader, now)
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> break
            }
        }
        throw FeedParseException("RSS feed without a channel element")
    }

    private fun parseChannel(reader: XMLStreamReader, now: Long): ParsedFeed {
        var title: String? = null
        var description: String? = null
        var summary: String? = null
        var author: String? = null
        var managingEditor: String? = null
        var language: String? = null
        var link: String? = null
        var atomLink: String? = null
        var itunesImage: String? = null
        var rssImage: String? = null
        var explicit = false
        var newFeedUrl: String? = null
        val episodes = mutableListOf<ParsedEpisode>()
        val seen = mutableSetOf<String>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) break
            if (event != XMLStreamConstants.START_ELEMENT) continue

            val namespace = reader.namespaceURI
            val name = reader.localName

            when {
                name == "item" && isRssNamespace(namespace) -> {
                    val episode = parseItem(reader, now)
                    if (episode != null && seen.add(episode.guid)) episodes += episode
                }

                namespace == NS_ITUNES -> when (name) {
                    "summary" -> summary = textOf(reader).ifBlank { null }
                    "author" -> author = textOf(reader).ifBlank { null }
                    "image" -> {
                        itunesImage = attribute(reader, "href")
                        skipElement(reader)
                    }

                    "explicit" -> explicit = isTruthy(textOf(reader))
                    "new-feed-url" -> newFeedUrl = textOf(reader).ifBlank { null }
                    else -> skipElement(reader)
                }

                namespace == NS_ATOM && name == "link" -> {
                    val rel = attribute(reader, "rel")
                    val href = attribute(reader, "href")
                    if (atomLink == null && href != null && (rel == null || rel == "alternate")) atomLink = href
                    skipElement(reader)
                }

                isRssNamespace(namespace) -> when (name) {
                    "title" -> title = textOf(reader).ifBlank { null }
                    "description" -> description = textOf(reader).ifBlank { null }
                    "managingEditor" -> managingEditor = textOf(reader).ifBlank { null }
                    "language" -> language = textOf(reader).ifBlank { null }
                    "link" -> link = textOf(reader).ifBlank { null }
                    "image" -> rssImage = parseRssImage(reader) ?: rssImage
                    else -> skipElement(reader)
                }

                else -> skipElement(reader)
            }
        }

        val show = ParsedShow(
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
            description = description ?: summary,
            author = author ?: managingEditor,
            language = language?.take(16),
            link = link ?: atomLink,
            imageUrl = itunesImage ?: rssImage,
            explicit = explicit
        )

        return ParsedFeed(show, episodes, newFeedUrl)
    }

    private fun parseRssImage(reader: XMLStreamReader): String? {
        var url: String? = null
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) break
            if (event != XMLStreamConstants.START_ELEMENT) continue
            if (reader.localName == "url") url = textOf(reader).ifBlank { null }
            else skipElement(reader)
        }
        return url
    }

    private fun parseItem(reader: XMLStreamReader, now: Long): ParsedEpisode? {
        var guid: String? = null
        var title: String? = null
        var description: String? = null
        var summary: String? = null
        var encoded: String? = null
        var link: String? = null
        var pubDate: String? = null
        var duration: String? = null
        var enclosureUrl: String? = null
        var enclosureType: String? = null
        var enclosureLength: Long? = null
        var imageUrl: String? = null
        var season: Int? = null
        var episodeNumber: Int? = null
        var episodeType = PodcastEpisodeType.FULL
        var explicit = false
        val transcripts = mutableListOf<ParsedTranscript>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) break
            if (event != XMLStreamConstants.START_ELEMENT) continue

            val namespace = reader.namespaceURI
            val name = reader.localName

            when {
                namespace == NS_ITUNES -> when (name) {
                    "summary", "subtitle" -> if (summary == null) summary = textOf(reader).ifBlank { null } else skipElement(reader)
                    "duration" -> duration = textOf(reader).ifBlank { null }
                    "image" -> {
                        imageUrl = attribute(reader, "href")
                        skipElement(reader)
                    }

                    "season" -> season = textOf(reader).trim().toIntOrNull()
                    "episode" -> episodeNumber = textOf(reader).trim().toIntOrNull()
                    "episodeType" -> episodeType = episodeTypeOf(textOf(reader))
                    "explicit" -> explicit = isTruthy(textOf(reader))
                    else -> skipElement(reader)
                }

                namespace == NS_CONTENT && name == "encoded" -> encoded = textOf(reader).ifBlank { null }

                namespace in PODCAST_NAMESPACES && name == "transcript" -> {
                    val url = attribute(reader, "url")
                    val transcriptType = attribute(reader, "type")
                    if (!url.isNullOrBlank() && !transcriptType.isNullOrBlank()) {
                        transcripts += ParsedTranscript(
                            url = url,
                            type = transcriptType.take(64),
                            language = attribute(reader, "language")?.take(16),
                            rel = attribute(reader, "rel")?.take(32)
                        )
                    }
                    skipElement(reader)
                }

                isRssNamespace(namespace) -> when (name) {
                    "guid" -> guid = textOf(reader).ifBlank { null }
                    "title" -> title = textOf(reader).ifBlank { null }
                    "description" -> description = textOf(reader).ifBlank { null }
                    "link" -> link = textOf(reader).ifBlank { null }
                    "pubDate" -> pubDate = textOf(reader).ifBlank { null }
                    "enclosure" -> {
                        enclosureUrl = attribute(reader, "url")?.trim()?.ifBlank { null }
                        enclosureType = attribute(reader, "type")?.trim()?.take(64)?.ifBlank { null }
                        enclosureLength = attribute(reader, "length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                        skipElement(reader)
                    }

                    else -> skipElement(reader)
                }

                else -> skipElement(reader)
            }
        }

        val url = enclosureUrl?.takeIf { it.isNotBlank() } ?: return null
        val episodeTitle = title?.takeIf { it.isNotBlank() } ?: "Untitled"

        return ParsedEpisode(
            guid = guidFor(guid, url, link, episodeTitle),
            title = episodeTitle,
            description = encoded ?: summary ?: description,
            link = link,
            publishedAt = parseRfc822(pubDate, now),
            durationMs = parseItunesDuration(duration),
            enclosureUrl = url,
            enclosureType = enclosureType,
            enclosureLength = enclosureLength,
            imageUrl = imageUrl,
            seasonNumber = season,
            episodeNumber = episodeNumber,
            episodeType = episodeType,
            explicit = explicit,
            transcripts = transcripts.distinctBy { it.url }
        )
    }

    private fun parseAtom(reader: XMLStreamReader, now: Long): ParsedFeed {
        val language = reader.getAttributeValue("http://www.w3.org/XML/1998/namespace", "lang")
        var title: String? = null
        var description: String? = null
        var author: String? = null
        var link: String? = null
        var imageUrl: String? = null
        var itunesImage: String? = null
        var explicit = false
        var newFeedUrl: String? = null
        val episodes = mutableListOf<ParsedEpisode>()
        val seen = mutableSetOf<String>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) break
            if (event != XMLStreamConstants.START_ELEMENT) continue

            val namespace = reader.namespaceURI
            val name = reader.localName

            when {
                name == "entry" -> {
                    val episode = parseAtomEntry(reader, now)
                    if (episode != null && seen.add(episode.guid)) episodes += episode
                }

                namespace == NS_ITUNES -> when (name) {
                    "author" -> author = textOf(reader).ifBlank { null }
                    "summary" -> if (description == null) description = textOf(reader).ifBlank { null } else skipElement(reader)
                    "image" -> {
                        itunesImage = attribute(reader, "href")
                        skipElement(reader)
                    }

                    "explicit" -> explicit = isTruthy(textOf(reader))
                    "new-feed-url" -> newFeedUrl = textOf(reader).ifBlank { null }
                    else -> skipElement(reader)
                }

                name == "title" -> title = textOf(reader).ifBlank { null }
                name == "subtitle" -> if (description == null) description = textOf(reader).ifBlank { null } else skipElement(reader)
                name == "logo" || name == "icon" -> if (imageUrl == null) imageUrl = textOf(reader).ifBlank { null } else skipElement(reader)
                name == "author" -> {
                    val parsed = parseAtomAuthor(reader)
                    if (author == null) author = parsed
                }
                name == "link" -> {
                    val rel = attribute(reader, "rel")
                    val href = attribute(reader, "href")
                    if (link == null && href != null && (rel == null || rel == "alternate")) link = href
                    skipElement(reader)
                }

                else -> skipElement(reader)
            }
        }

        val show = ParsedShow(
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
            description = description,
            author = author,
            language = language?.take(16),
            link = link,
            imageUrl = itunesImage ?: imageUrl,
            explicit = explicit
        )

        return ParsedFeed(show, episodes, newFeedUrl)
    }

    private fun parseAtomAuthor(reader: XMLStreamReader): String? {
        var name: String? = null
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) break
            if (event != XMLStreamConstants.START_ELEMENT) continue
            if (reader.localName == "name") name = textOf(reader).ifBlank { null }
            else skipElement(reader)
        }
        return name
    }

    private fun parseAtomEntry(reader: XMLStreamReader, now: Long): ParsedEpisode? {
        var id: String? = null
        var title: String? = null
        var content: String? = null
        var summary: String? = null
        var link: String? = null
        var published: String? = null
        var updated: String? = null
        var duration: String? = null
        var enclosureUrl: String? = null
        var enclosureType: String? = null
        var enclosureLength: Long? = null
        var imageUrl: String? = null
        var season: Int? = null
        var episodeNumber: Int? = null
        var episodeType = PodcastEpisodeType.FULL
        var explicit = false
        val transcripts = mutableListOf<ParsedTranscript>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == XMLStreamConstants.END_ELEMENT) break
            if (event != XMLStreamConstants.START_ELEMENT) continue

            val namespace = reader.namespaceURI
            val name = reader.localName

            when {
                namespace == NS_ITUNES -> when (name) {
                    "summary", "subtitle" -> if (summary == null) summary = textOf(reader).ifBlank { null } else skipElement(reader)
                    "duration" -> duration = textOf(reader).ifBlank { null }
                    "image" -> {
                        imageUrl = attribute(reader, "href")
                        skipElement(reader)
                    }

                    "season" -> season = textOf(reader).trim().toIntOrNull()
                    "episode" -> episodeNumber = textOf(reader).trim().toIntOrNull()
                    "episodeType" -> episodeType = episodeTypeOf(textOf(reader))
                    "explicit" -> explicit = isTruthy(textOf(reader))
                    else -> skipElement(reader)
                }

                namespace in PODCAST_NAMESPACES && name == "transcript" -> {
                    val url = attribute(reader, "url")
                    val transcriptType = attribute(reader, "type")
                    if (!url.isNullOrBlank() && !transcriptType.isNullOrBlank()) {
                        transcripts += ParsedTranscript(
                            url = url,
                            type = transcriptType.take(64),
                            language = attribute(reader, "language")?.take(16),
                            rel = attribute(reader, "rel")?.take(32)
                        )
                    }
                    skipElement(reader)
                }

                name == "id" -> id = textOf(reader).ifBlank { null }
                name == "title" -> title = textOf(reader).ifBlank { null }
                name == "content" -> content = textOf(reader).ifBlank { null }
                name == "summary" -> if (summary == null) summary = textOf(reader).ifBlank { null } else skipElement(reader)
                name == "published" -> published = textOf(reader).ifBlank { null }
                name == "updated" -> updated = textOf(reader).ifBlank { null }
                name == "link" -> {
                    val rel = attribute(reader, "rel")
                    val href = attribute(reader, "href")
                    if (rel == "enclosure" && href != null) {
                        enclosureUrl = href.trim().ifBlank { null }
                        enclosureType = attribute(reader, "type")?.trim()?.take(64)?.ifBlank { null }
                        enclosureLength = attribute(reader, "length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                    } else if (link == null && href != null && (rel == null || rel == "alternate")) {
                        link = href
                    }
                    skipElement(reader)
                }

                else -> skipElement(reader)
            }
        }

        val url = enclosureUrl?.takeIf { it.isNotBlank() } ?: return null
        val entryTitle = title?.takeIf { it.isNotBlank() } ?: "Untitled"

        return ParsedEpisode(
            guid = guidFor(id, url, link, entryTitle),
            title = entryTitle,
            description = content ?: summary,
            link = link,
            publishedAt = parseRfc822(published ?: updated, now),
            durationMs = parseItunesDuration(duration),
            enclosureUrl = url,
            enclosureType = enclosureType,
            enclosureLength = enclosureLength,
            imageUrl = imageUrl,
            seasonNumber = season,
            episodeNumber = episodeNumber,
            episodeType = episodeType,
            explicit = explicit,
            transcripts = transcripts.distinctBy { it.url }
        )
    }

    internal fun guidFor(guid: String?, enclosureUrl: String, link: String?, title: String): String {
        val trimmed = guid?.trim()
        if (!trimmed.isNullOrBlank()) return trimmed
        if (enclosureUrl.isNotBlank()) return enclosureUrl
        return "${link.orEmpty()}|$title"
    }

    internal fun parseItunesDuration(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(":").map { it.trim() }
        if (parts.any { it.isEmpty() }) return null

        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }
            2 -> {
                val minutes = parts[0].toLongOrNull()
                val seconds = parts[1].toDoubleOrNull()
                if (minutes == null || seconds == null || minutes < 0 || seconds < 0) null
                else ((minutes * 60) * 1000) + (seconds * 1000).toLong()
            }

            3 -> {
                val hours = parts[0].toLongOrNull()
                val minutes = parts[1].toLongOrNull()
                val seconds = parts[2].toDoubleOrNull()
                if (hours == null || minutes == null || seconds == null || hours < 0 || minutes < 0 || seconds < 0) null
                else ((hours * 3600 + minutes * 60) * 1000) + (seconds * 1000).toLong()
            }

            else -> null
        }
    }

    internal fun parseRfc822(raw: String?, fallback: Long): Long {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
        val candidates = buildList {
            add(text)
            val normalized = normalizeZone(text)
            if (normalized != text) add(normalized)
            val withoutDay = normalized.substringAfter(',', normalized).trim()
            if (withoutDay != normalized) add(withoutDay)
        }

        for (candidate in candidates) {
            for (format in DATE_FORMATS) {
                val parsed = runCatching { ZonedDateTime.parse(candidate, format).toInstant().toEpochMilli() }.getOrNull()
                if (parsed != null) return parsed
            }
            val iso = parseIso(candidate)
            if (iso != null) return iso
        }

        return fallback
    }

    private fun parseIso(text: String): Long? {
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        return null
    }

    private fun normalizeZone(text: String): String {
        val lastSpace = text.lastIndexOf(' ')
        if (lastSpace < 0) return text
        val zone = text.substring(lastSpace + 1).trim().uppercase(Locale.ENGLISH)
        val offset = ZONE_OFFSETS[zone] ?: return text
        return text.substring(0, lastSpace + 1) + offset
    }

    private fun episodeTypeOf(raw: String): PodcastEpisodeType = when (raw.trim().lowercase(Locale.ENGLISH)) {
        "trailer" -> PodcastEpisodeType.TRAILER
        "bonus" -> PodcastEpisodeType.BONUS
        else -> PodcastEpisodeType.FULL
    }

    private fun isTruthy(raw: String): Boolean = raw.trim().lowercase(Locale.ENGLISH) in TRUE_VALUES

    private fun isRssNamespace(namespace: String?): Boolean =
        namespace.isNullOrEmpty() || namespace == "http://purl.org/rss/1.0/" || namespace == "http://backend.userland.com/rss2"

    private fun attribute(reader: XMLStreamReader, name: String): String? =
        reader.getAttributeValue(null, name)?.trim()?.ifBlank { null }

    private fun textOf(reader: XMLStreamReader): String {
        val builder = StringBuilder()
        var depth = 1
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                    if (depth == 1) builder.append(reader.text)

                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> {
                    depth--
                    if (depth == 0) return builder.toString().trim()
                }
            }
        }
        return builder.toString().trim()
    }

    private fun skipElement(reader: XMLStreamReader) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
    }
}
