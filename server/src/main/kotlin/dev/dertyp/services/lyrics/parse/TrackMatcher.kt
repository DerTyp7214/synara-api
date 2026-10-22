package dev.dertyp.services.lyrics.parse

import dev.dertyp.core.normalizedKey
import dev.dertyp.services.models.LyricsMatch
import kotlin.math.abs

object TrackMatcher {
    const val MAX_DURATION_GAP_SEC = 8

    private val bracketedNote = Regex("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}|（[^）]*）|【[^】]*】|「[^」]*」")
    private val artistSeparator = Regex(
        "\\s*(?:[,;/&+、×]|\\s(?:feat\\.?|ft\\.?|featuring|with|x|and|vs\\.?)\\s)\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun verify(
        songTitle: String,
        songArtist: String,
        songDurationSec: Int?,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): LyricsMatch? {
        val gap = durationGapSec(songDurationSec, candidateDurationSec)
        if (gap != null && gap > MAX_DURATION_GAP_SEC) return null
        if (!titleMatches(songTitle, candidateTitle)) return null
        if (!artistMatches(songArtist, songTitle, candidateArtist)) return null
        return LyricsMatch.VERIFIED
    }

    fun durationGapSec(songDurationSec: Int?, candidateDurationSec: Int?): Int? {
        if (songDurationSec == null || candidateDurationSec == null || songDurationSec <= 0 || candidateDurationSec <= 0) return null
        return abs(songDurationSec - candidateDurationSec)
    }

    fun titleMatches(songTitle: String, candidateTitle: String): Boolean {
        val candidate = candidateTitle.replace(bracketedNote, " ").normalizedKey().ifEmpty { candidateTitle.normalizedKey() }
        return containsWords(songTitle.normalizedKey(), candidate)
    }

    fun artistMatches(songArtist: String, songTitle: String, candidateArtist: String): Boolean {
        val haystack = "$songArtist $songTitle".normalizedKey()
        val names = candidateArtist.split(artistSeparator) + candidateArtist
        return names.any { containsWords(haystack, it.normalizedKey()) }
    }

    private fun containsWords(haystack: String, needle: String): Boolean =
        needle.isNotEmpty() && " $haystack ".contains(" $needle ")
}
