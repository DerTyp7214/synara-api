package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricVoice
import dev.dertyp.services.models.LyricVoiceType
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object TtmlParser {
    const val MAX_DEPTH = 32

    private val doctype = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
    private val clockTime = Regex("^(?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?)$")
    private val offsetTime = Regex("^(\\d+(?:\\.\\d+)?)(h|m|s|ms)?$")
    private val skippedRoles = setOf("x-translation", "x-transliteration")

    private val factory: XMLInputFactory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }

    private sealed interface Node
    private class Element(val name: String, val attributes: Map<String, String>) : Node {
        val children = mutableListOf<Node>()
    }
    private class Text(val text: String) : Node

    private class Collector {
        val words = mutableListOf<RawWord>()
        val prefix = StringBuilder()
        var begin: Duration? = null
        var end: Duration? = null

        fun appendText(text: String) {
            if (words.isEmpty()) prefix.append(text)
            else words[words.lastIndex] = words.last().let { it.copy(text = it.text + text) }
        }

        fun addSyllable(text: String, start: Duration?, end: Duration?) {
            val leading = if (words.isEmpty()) prefix.toString().also { prefix.clear() } else ""
            words += RawWord(leading + text, start, end)
        }

        fun toLine(start: Duration?, end: Duration?, voice: String?, background: Boolean, group: Int): RawLine? {
            if (words.isEmpty()) {
                if (prefix.isBlank()) return null
                return RawLine(start, end, text = prefix.toString(), voice = voice, background = background, group = group)
            }
            return RawLine(start ?: words.first().start, end ?: words.last().end, words = words.toList(), voice = voice, background = background, group = group)
        }
    }

    fun parse(ttml: String): ParsedLyrics? {
        if (doctype.containsMatchIn(ttml)) return null
        val root = readTree(ttml) ?: return null
        if (root.name != "tt") return null

        val voices = linkedMapOf<String, LyricVoice>()
        root.elements("head").forEach { head -> collectAgents(head, voices) }
        val body = root.elements("body").firstOrNull() ?: return null
        val durationSec = (body.attributes["dur"] ?: root.attributes["dur"])?.let(::parseTime)?.inWholeSeconds?.toInt()

        val lines = mutableListOf<RawLine>()
        var group = 0
        collectParagraphs(body, body.attributes["agent"]) { paragraph, agent ->
            lines += paragraphLines(paragraph, agent, group++)
        }
        return LyricsAssembler.assemble(lines, voices, tags = TrackTags(durationSec = durationSec))
    }

    fun parseTime(value: String): Duration? {
        val text = value.trim()
        val seconds = clockTime.matchEntire(text)?.let { match ->
            val hours = match.groupValues[1].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
            val minutes = match.groupValues[2].toDoubleOrNull() ?: return null
            val secs = match.groupValues[3].toDoubleOrNull() ?: return null
            hours * 3600 + minutes * 60 + secs
        } ?: offsetTime.matchEntire(text)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull() ?: return null
            when (match.groupValues[2]) {
                "h" -> number * 3600
                "m" -> number * 60
                "ms" -> number / 1000
                else -> number
            }
        } ?: return null
        if (!seconds.isFinite() || seconds < 0) return null
        return (seconds * 1000).roundToLong().milliseconds
    }

    private fun readTree(ttml: String): Element? {
        val reader = try {
            factory.createXMLStreamReader(StringReader(ttml))
        } catch (_: XMLStreamException) {
            return null
        }
        try {
            val stack = ArrayDeque<Element>()
            var root: Element? = null
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> return null
                    XMLStreamConstants.START_ELEMENT -> {
                        if (stack.size >= MAX_DEPTH) return null
                        val attributes = (0 until reader.attributeCount).associate { reader.getAttributeLocalName(it) to reader.getAttributeValue(it) }
                        val element = Element(reader.localName, attributes)
                        val parent = stack.lastOrNull()
                        if (parent == null) {
                            if (root != null) return null
                            root = element
                        } else parent.children += element
                        stack.addLast(element)
                    }
                    XMLStreamConstants.END_ELEMENT -> stack.removeLastOrNull()
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        stack.lastOrNull()?.children?.add(Text(reader.text))
                }
            }
            return root
        } catch (_: XMLStreamException) {
            return null
        } finally {
            reader.close()
        }
    }

    private fun Element.elements(name: String): List<Element> = children.filterIsInstance<Element>().filter { it.name == name }

    private fun Element.textContent(): String = children.joinToString("") { child ->
        when (child) {
            is Text -> child.text
            is Element -> if (child.attributes["role"] in skippedRoles) "" else child.textContent()
        }
    }

    private fun Element.hasTimedDescendant(): Boolean = children.any { child ->
        child is Element && (child.attributes.containsKey("begin") || child.hasTimedDescendant())
    }

    private fun collectAgents(element: Element, voices: MutableMap<String, LyricVoice>) {
        element.children.filterIsInstance<Element>().forEach { child ->
            if (child.name == "agent") {
                val id = child.attributes["id"]
                if (id != null) {
                    val name = child.elements("name").firstOrNull()?.textContent()?.trim()?.takeIf { it.isNotEmpty() } ?: id
                    val type = if (child.attributes["type"] == "group") LyricVoiceType.GROUP else LyricVoiceType.PERSON
                    voices[id] = LyricVoice(name, type)
                }
            } else collectAgents(child, voices)
        }
    }

    private fun collectParagraphs(element: Element, agent: String?, onParagraph: (Element, String?) -> Unit) {
        element.children.filterIsInstance<Element>().forEach { child ->
            val childAgent = child.attributes["agent"] ?: agent
            if (child.name == "p") onParagraph(child, childAgent) else collectParagraphs(child, childAgent, onParagraph)
        }
    }

    private fun paragraphLines(paragraph: Element, agent: String?, group: Int): List<RawLine> {
        val lead = Collector()
        val backing = Collector()
        walk(paragraph, lead, backing)
        val begin = paragraph.attributes["begin"]?.let(::parseTime)
        val end = paragraph.attributes["end"]?.let(::parseTime)
        return listOfNotNull(
            lead.toLine(begin, end, agent, background = false, group = group),
            backing.toLine(backing.begin, backing.end, agent, background = true, group = group),
        )
    }

    private fun walk(element: Element, target: Collector, backing: Collector) {
        for (child in element.children) {
            when (child) {
                is Text -> target.appendText(child.text)
                is Element -> when {
                    child.attributes["role"] in skippedRoles -> Unit
                    child.attributes["role"] == "x-bg" -> {
                        backing.begin = backing.begin ?: child.attributes["begin"]?.let(::parseTime)
                        backing.end = child.attributes["end"]?.let(::parseTime) ?: backing.end
                        walk(child, backing, backing)
                    }
                    child.name == "br" -> target.appendText(" ")
                    child.attributes.containsKey("begin") && !child.hasTimedDescendant() -> target.addSyllable(
                        child.textContent(),
                        child.attributes["begin"]?.let(::parseTime),
                        child.attributes["end"]?.let(::parseTime),
                    )
                    else -> walk(child, target, backing)
                }
            }
        }
    }
}
