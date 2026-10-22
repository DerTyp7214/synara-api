package dev.dertyp.services.lyrics.providers

import dev.dertyp.services.models.LyricsProviderInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LyricsProviderRegistryTest {

    @Nested
    inner class DefaultOrder : ProviderTestSupport() {
        @Test
        fun `enables every provider in the default order`() {
            val registry = koin<LyricsProviderRegistry>()
            assertEquals(
                listOf("betterlyrics", "unison", "lyricsplus", "kugou", "lrclib", "youtubemusic"),
                registry.enabled().map { it.id },
            )
            assertEquals(0, registry.priority("betterlyrics"))
            assertEquals(5, registry.priority("youtubemusic"))
            assertEquals(null, registry.priority("sidecar"))
            assertEquals(null, registry.provider("sidecar"))
        }
    }

    @Nested
    inner class ConfiguredOrder : ProviderTestSupport() {
        override val configuredProviders = " KuGou, lrclib ,nope,kugou,betterlyrics"

        @Test
        fun `follows the configured order and ignores unknown and repeated ids`() {
            val registry = koin<LyricsProviderRegistry>()
            assertEquals(listOf("kugou", "lrclib", "betterlyrics"), registry.enabled().map { it.id })
            assertEquals(
                listOf(
                    LyricsProviderInfo("kugou", "KuGou", "https://www.kugou.com", enabled = true, priority = 0),
                    LyricsProviderInfo("lrclib", "LRCLIB", "https://lrclib.net", enabled = true, priority = 1),
                    LyricsProviderInfo("betterlyrics", "Better Lyrics", "https://github.com/better-lyrics/better-lyrics", enabled = true, priority = 2),
                    LyricsProviderInfo("unison", "Unison", "https://unison.betterlyrics.org", enabled = false, priority = 3),
                    LyricsProviderInfo("lyricsplus", "LyricsPlus", "https://lyricsplus.binimum.org", enabled = false, priority = 4),
                    LyricsProviderInfo("youtubemusic", "YouTube Music", "https://music.youtube.com", enabled = false, priority = 5),
                ),
                registry.infos(),
            )
        }
    }

    @Nested
    inner class Empty : ProviderTestSupport() {
        override val configuredProviders = ""

        @Test
        fun `an empty list enables no network provider`() {
            val registry = koin<LyricsProviderRegistry>()
            assertEquals(emptyList<LyricsProvider>(), registry.enabled())
            assertEquals(true, registry.infos().none { it.enabled })
            assertEquals(6, registry.infos().size)
        }
    }
}
