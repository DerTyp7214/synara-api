package dev.dertyp.services.import

import dev.dertyp.core.cleanTitle
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.data.MusicBrainzTrack
import dev.dertyp.utils.Isrcs

fun MusicBrainzRelease.hasTracks(): Boolean = media.orEmpty().any { it.tracks.orEmpty().isNotEmpty() }

fun MusicBrainzRelease.findTrack(isrcs: List<String>, titles: List<String>): MusicBrainzTrack? {
    val tracks = media.orEmpty().flatMap { it.tracks.orEmpty() }
    val wantedIsrcs = isrcs.map(Isrcs::normalize).filter { it.isNotEmpty() }.toSet()
    val wantedTitles = titles.map { it.cleanTitle() }
    return tracks.find { track ->
        track.recording?.isrcs.orEmpty().any { Isrcs.normalize(it) in wantedIsrcs }
    } ?: tracks.find { track ->
        val title = track.title?.cleanTitle() ?: return@find false
        wantedTitles.any { it.equals(title, true) }
    }
}
