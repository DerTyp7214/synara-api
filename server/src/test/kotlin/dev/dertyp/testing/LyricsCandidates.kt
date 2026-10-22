package dev.dertyp.testing

import dev.dertyp.services.lyrics.LyricsCandidateService
import io.mockk.coEvery
import io.mockk.mockk

fun noLyricsCandidates(): LyricsCandidateService = mockk {
    coEvery { lyricsInfoFor(any()) } returns emptyMap()
}
