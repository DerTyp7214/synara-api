package dev.dertyp.services.release

enum class ArtistSourceRuleKind { COPYRIGHT_HOLDER, LABEL, ISRC_REGISTRANT }

enum class ArtistSourceRulePolarity { TRUST, BLOCK }

object ArtistIdentityEvidence {

    private const val MAX_VALUE_LENGTH = 255
    private const val EDGE_CHARS = " ,.;:/&+-"

    private val LEADING_NOISE =
        Regex("^(?:℗|©|\\(p\\)|\\(c\\)|copyright\\b|and\\b|[&,.\\-]|\\s+|(?:19|20)\\d{2}\\b)")

    private val TRAILING_CLAUSE = Regex(
        "\\b(?:manufactured and distributed by|exclusively distributed by|distributed by" +
            "|under exclusive licen[cs]e to|under licen[cs]e to|exclusive licen[cs]e to" +
            "|licensed to|a division of|marketed by|all rights reserved)\\b"
    )

    private val TRAILING_COMPANY_CLAUSE = Regex(",\\s+an?\\b.*\\b(?:company|label|division)\\b.*$")

    private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")

    private val LEGAL_SUFFIX = Regex("\\b(?:gmbh|ltd|llc|inc|corporation|corp|limited|plc|kg|ag|co)\\b\\.?")

    private val WHITESPACE = Regex("\\s+")

    private val TOKEN_SEPARATOR = Regex("[^a-z0-9]+")

    private val NAME_STOPWORDS = setOf(
        "records", "recordings", "recording", "music", "entertainment", "group", "gmbh", "ltd", "llc",
        "inc", "label", "labels", "distributed", "distribution", "under", "exclusive", "license",
        "licence", "the", "and", "digital", "media", "publishing", "company", "international",
        "germany", "deutschland", "europe", "worldwide", "global", "holding"
    )

    data class TaughtRule(val kind: ArtistSourceRuleKind, val value: String, val polarity: ArtistSourceRulePolarity)

    data class TaughtRules(
        val trustNames: Set<String>,
        val trustRegistrants: Set<String>,
        val blockNames: Set<String>,
        val blockRegistrants: Set<String>
    ) {
        companion object {
            val NONE = TaughtRules(emptySet(), emptySet(), emptySet(), emptySet())

            fun from(rules: List<TaughtRule>): TaughtRules {
                val trustNames = mutableSetOf<String>()
                val trustRegistrants = mutableSetOf<String>()
                val blockNames = mutableSetOf<String>()
                val blockRegistrants = mutableSetOf<String>()
                rules.forEach { rule ->
                    val trust = rule.polarity == ArtistSourceRulePolarity.TRUST
                    when (rule.kind) {
                        ArtistSourceRuleKind.COPYRIGHT_HOLDER, ArtistSourceRuleKind.LABEL -> {
                            val value = normalizeCopyrightHolder(rule.value) ?: return@forEach
                            if (trust) trustNames += value else blockNames += value
                        }

                        ArtistSourceRuleKind.ISRC_REGISTRANT -> {
                            val value = rule.value.trim().uppercase()
                            if (value.isEmpty()) return@forEach
                            if (trust) trustRegistrants += value else blockRegistrants += value
                        }
                    }
                }
                return TaughtRules(trustNames, trustRegistrants, blockNames, blockRegistrants)
            }
        }
    }

    data class KnownEvidence(val names: Set<String>, val registrants: Set<String>) {
        val isEmpty: Boolean get() = names.isEmpty() && registrants.isEmpty()

        companion object {
            val NONE = KnownEvidence(emptySet(), emptySet())
        }
    }

    data class Signals(
        val holder: String?,
        val label: String?,
        val registrants: Set<String>,
        val foreignArtistIds: List<String>,
        val compilation: Boolean,
        val genreHint: String? = null
    )

    data class Verdict(val suspect: Boolean, val reason: String?, val blocked: Boolean = false) {
        companion object {
            val CLEAR = Verdict(false, null)
        }
    }

    fun normalizeCopyrightHolder(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        var value = trimmed.lowercase()
        while (value.isNotEmpty()) {
            val match = LEADING_NOISE.find(value) ?: break
            if (match.value.isEmpty()) break
            value = value.substring(match.value.length)
        }
        TRAILING_CLAUSE.find(value)?.let { value = value.substring(0, it.range.first) }
        value = TRAILING_COMPANY_CLAUSE.replace(value, "")
        value = YEAR.replace(value, "")
        value = LEGAL_SUFFIX.replace(value, "")
        value = WHITESPACE.replace(value, " ").trim { it in EDGE_CHARS }
        val result = value.take(MAX_VALUE_LENGTH)
        return result.ifEmpty { null }
    }

    fun normalizeLabel(raw: String?): String? = normalizeCopyrightHolder(raw)

    fun isrcRegistrant(isrc: String?): String? {
        val cleaned = isrc?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()
        if (cleaned.length != 12) return null
        if (!cleaned[0].isLetter() || !cleaned[1].isLetter()) return null
        return cleaned.take(5)
    }

    fun namesMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val left = tokens(a)
        val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left.all { it in right } || right.all { it in left }) return true
        return left.any { it.length >= 4 && it in right }
    }

    fun evaluate(signals: Signals, known: KnownEvidence, rules: TaughtRules): Verdict {
        val holder = signals.holder?.takeIf { it.isNotBlank() }
        val label = signals.label?.takeIf { it.isNotBlank() }

        val blockHits = buildList {
            if (matchesName(holder, rules.blockNames)) add("blocked copyright holder \"$holder\"")
            if (matchesName(label, rules.blockNames)) add("blocked label \"$label\"")
            signals.registrants.filter { it in rules.blockRegistrants }.sorted()
                .forEach { add("blocked ISRC registrant \"$it\"") }
        }
        if (blockHits.isNotEmpty()) return Verdict(true, blockHits.joinToString(", "), blocked = true)

        val trusted = matchesName(holder, rules.trustNames) ||
            matchesName(label, rules.trustNames) ||
            signals.registrants.any { it in rules.trustRegistrants }
        if (trusted) return Verdict.CLEAR

        if (signals.foreignArtistIds.isNotEmpty()) {
            val ids = signals.foreignArtistIds.joinToString(", ")
            return Verdict(true, "credited to Apple artist(s) $ids instead of the resolved artist")
        }

        if (signals.compilation) return Verdict.CLEAR
        if (holder == null && label == null && signals.registrants.isEmpty()) return Verdict.CLEAR

        val namesComparable = (holder != null || label != null) && known.names.isNotEmpty()
        val registrantsComparable = signals.registrants.isNotEmpty() && known.registrants.isNotEmpty()
        if (!namesComparable && !registrantsComparable) return Verdict.CLEAR

        if (namesComparable && (matchesKnownName(holder, known.names) || matchesKnownName(label, known.names))) {
            return Verdict.CLEAR
        }
        if (registrantsComparable && signals.registrants.any { it in known.registrants }) return Verdict.CLEAR

        val parts = buildList {
            if (holder != null) add("copyright holder \"$holder\"")
            if (label != null) add("label \"$label\"")
            if (signals.registrants.isNotEmpty()) {
                val listed = signals.registrants.sorted().joinToString(", ") { "\"$it\"" }
                val noun = if (signals.registrants.size == 1) "ISRC registrant" else "ISRC registrants"
                add("$noun $listed")
            }
        }
        val hint = signals.genreHint?.let { " ($it)" }.orEmpty()
        return Verdict(true, "${joinReasonParts(parts)} never seen for this artist$hint")
    }

    fun relatedKey(copyrightHolder: String?, recordLabel: String?): Pair<ArtistSourceRuleKind, String>? {
        copyrightHolder?.takeIf { it.isNotBlank() }?.let { return ArtistSourceRuleKind.COPYRIGHT_HOLDER to it }
        recordLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return ArtistSourceRuleKind.LABEL to it.take(MAX_VALUE_LENGTH) }
        return null
    }

    fun evidence(
        copyrightHolder: String?,
        recordLabel: String?,
        isrcRegistrants: String?
    ): List<Pair<ArtistSourceRuleKind, String>> = buildList {
        copyrightHolder?.takeIf { it.isNotBlank() }?.let { add(ArtistSourceRuleKind.COPYRIGHT_HOLDER to it) }
        recordLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ArtistSourceRuleKind.LABEL to it) }
        isrcRegistrants?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { add(ArtistSourceRuleKind.ISRC_REGISTRANT to it) }
    }

    private fun tokens(value: String): List<String> =
        value.lowercase().split(TOKEN_SEPARATOR).filter { it.isNotEmpty() && it !in NAME_STOPWORDS }

    private fun matchesName(value: String?, candidates: Set<String>): Boolean {
        if (value == null || candidates.isEmpty()) return false
        return candidates.any { it == value || namesMatch(value, it) }
    }

    private fun matchesKnownName(value: String?, names: Set<String>): Boolean {
        if (value == null) return false
        return names.any { namesMatch(value, it) }
    }

    private fun joinReasonParts(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts.first()
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
