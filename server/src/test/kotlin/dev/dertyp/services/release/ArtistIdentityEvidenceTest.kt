package dev.dertyp.services.release

import dev.dertyp.services.release.ArtistIdentityEvidence.KnownEvidence
import dev.dertyp.services.release.ArtistIdentityEvidence.Signals
import dev.dertyp.services.release.ArtistIdentityEvidence.TaughtRule
import dev.dertyp.services.release.ArtistIdentityEvidence.TaughtRules
import dev.dertyp.services.release.ArtistIdentityEvidence.evaluate
import dev.dertyp.services.release.ArtistIdentityEvidence.evidence
import dev.dertyp.services.release.ArtistIdentityEvidence.isrcRegistrant
import dev.dertyp.services.release.ArtistIdentityEvidence.namesMatch
import dev.dertyp.services.release.ArtistIdentityEvidence.normalizeCopyrightHolder
import dev.dertyp.services.release.ArtistIdentityEvidence.normalizeLabel
import dev.dertyp.services.release.ArtistIdentityEvidence.relatedKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtistIdentityEvidenceTest {

    private fun signals(
        holder: String? = null,
        label: String? = null,
        registrants: Set<String> = emptySet(),
        foreignArtistIds: List<String> = emptyList(),
        compilation: Boolean = false,
        genreHint: String? = null
    ) = Signals(holder, label, registrants, foreignArtistIds, compilation, genreHint)

    @Test
    fun `normalizeCopyrightHolder strips notices, years, distribution clauses and legal suffixes`() {
        val cases = listOf<Pair<String?, String?>>(
            "℗ 2026 DIVISION / RCA & GOLD LEAGUE distributed by Sony Music Entertainment" to
                "division / rca & gold league",
            "℗ 2026 13652890 Records DK" to "13652890 records dk",
            "℗ 2024 Division Recordings" to "division recordings",
            "(P) 2019 Sony Music Entertainment Germany GmbH" to "sony music entertainment germany",
            "2021 Division" to "division",
            "℗ 2024 Universal Music GmbH" to "universal music",
            "℗ & © 2023 Division, a Sony Music company" to "division",
            "© 2022 Warner Music Group Germany Holding GmbH, under exclusive license to Warner Music Central Europe" to
                "warner music group germany holding",
            "Copyright 2020 Chapter ONE, a division of Universal Music GmbH" to "chapter one",
            "℗ 2024" to null,
            "" to null,
            "   " to null,
            null to null
        )
        cases.forEach { (raw, expected) ->
            assertEquals(expected, normalizeCopyrightHolder(raw), "normalize of ${raw ?: "null"}")
        }
    }

    @Test
    fun `normalizeLabel behaves like normalizeCopyrightHolder`() {
        assertEquals("division recordings", normalizeLabel("℗ 2024 Division Recordings"))
        assertNull(normalizeLabel(null))
    }

    @Test
    fun `isrcRegistrant keeps the five char prefix of a well formed code`() {
        assertEquals("DEUM7", isrcRegistrant("DEUM72400123"))
        assertEquals("DEUM7", isrcRegistrant("de-um7-24-00123"))
        assertEquals("QZK6P", isrcRegistrant("QZK6P2600001"))
        assertNull(isrcRegistrant("abc"))
        assertNull(isrcRegistrant("123456789012"))
        assertNull(isrcRegistrant(null))
    }

    @Test
    fun `namesMatch compares meaningful tokens in both directions`() {
        assertTrue(namesMatch("division recordings", "division / rca & gold league"))
        assertTrue(namesMatch("division / rca & gold league", "division recordings"))
        assertTrue(namesMatch("rca", "rca records"))
        assertTrue(namesMatch("rca records", "rca"))
        assertTrue(namesMatch("division", "division"))
        assertFalse(namesMatch("sony music entertainment germany", "universal music"))
        assertFalse(namesMatch("universal music", "sony music entertainment germany"))
        assertFalse(namesMatch("13652890 records dk", "division recordings"))
        assertFalse(namesMatch("division recordings", "13652890 records dk"))
    }

    @Test
    fun `TaughtRules from normalizes values and drops empty ones`() {
        val rules = TaughtRules.from(
            listOf(
                TaughtRule(
                    ArtistSourceRuleKind.COPYRIGHT_HOLDER,
                    "℗ 2024 Division Recordings",
                    ArtistSourceRulePolarity.TRUST
                ),
                TaughtRule(ArtistSourceRuleKind.LABEL, "Division GmbH", ArtistSourceRulePolarity.TRUST),
                TaughtRule(ArtistSourceRuleKind.ISRC_REGISTRANT, " deum7 ", ArtistSourceRulePolarity.TRUST),
                TaughtRule(
                    ArtistSourceRuleKind.COPYRIGHT_HOLDER,
                    "℗ 2026 13652890 Records DK",
                    ArtistSourceRulePolarity.BLOCK
                ),
                TaughtRule(ArtistSourceRuleKind.ISRC_REGISTRANT, "qzk6p", ArtistSourceRulePolarity.BLOCK),
                TaughtRule(ArtistSourceRuleKind.COPYRIGHT_HOLDER, "℗ 2024", ArtistSourceRulePolarity.TRUST),
                TaughtRule(ArtistSourceRuleKind.ISRC_REGISTRANT, "   ", ArtistSourceRulePolarity.BLOCK)
            )
        )
        assertEquals(setOf("division recordings", "division"), rules.trustNames)
        assertEquals(setOf("DEUM7"), rules.trustRegistrants)
        assertEquals(setOf("13652890 records dk"), rules.blockNames)
        assertEquals(setOf("QZK6P"), rules.blockRegistrants)
        assertEquals(TaughtRules(emptySet(), emptySet(), emptySet(), emptySet()), TaughtRules.NONE)
        assertEquals(TaughtRules.NONE, TaughtRules.from(emptyList()))
    }

    @Test
    fun `KnownEvidence NONE is empty`() {
        assertTrue(KnownEvidence.NONE.isEmpty)
        assertFalse(KnownEvidence(setOf("division"), emptySet()).isEmpty)
        assertFalse(KnownEvidence(emptySet(), setOf("DEUM7")).isEmpty)
    }

    @Test
    fun `relatedKey prefers the copyright holder over the label`() {
        assertEquals(
            ArtistSourceRuleKind.COPYRIGHT_HOLDER to "division recordings",
            relatedKey("division recordings", "Division")
        )
        assertEquals(ArtistSourceRuleKind.LABEL to "Division", relatedKey("   ", " Division "))
        assertEquals(ArtistSourceRuleKind.LABEL to "Division", relatedKey(null, "Division"))
        assertNull(relatedKey(null, null))
        assertNull(relatedKey(" ", "  "))
    }

    @Test
    fun `evidence lists holder, label and every registrant`() {
        assertEquals(
            listOf(
                ArtistSourceRuleKind.COPYRIGHT_HOLDER to "division recordings",
                ArtistSourceRuleKind.LABEL to "Division",
                ArtistSourceRuleKind.ISRC_REGISTRANT to "DEUM7",
                ArtistSourceRuleKind.ISRC_REGISTRANT to "DEA62"
            ),
            evidence("division recordings", " Division ", "DEUM7, DEA62, ")
        )
        assertEquals(emptyList<Pair<ArtistSourceRuleKind, String>>(), evidence("  ", "", null))
        assertEquals(
            listOf(ArtistSourceRuleKind.ISRC_REGISTRANT to "DEUM7"),
            evidence(null, null, " DEUM7 ")
        )
    }

    @Test
    fun `block rules win over trust rules`() {
        val rules = TaughtRules(
            trustNames = setOf("13652890 records dk"),
            trustRegistrants = setOf("QZK6P"),
            blockNames = setOf("13652890 records dk"),
            blockRegistrants = setOf("QZK6P")
        )
        val verdict = evaluate(
            signals(holder = "13652890 records dk", label = "13652890 Records DK", registrants = setOf("QZK6P")),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            rules
        )
        assertTrue(verdict.suspect)
        assertTrue(verdict.blocked)
        assertEquals(
            "blocked copyright holder \"13652890 records dk\", blocked label \"13652890 Records DK\", " +
                "blocked ISRC registrant \"QZK6P\"",
            verdict.reason
        )
    }

    @Test
    fun `trust rules clear an otherwise unknown source`() {
        val rules = TaughtRules(setOf("division"), emptySet(), emptySet(), emptySet())
        val verdict = evaluate(
            signals(holder = "division / rca & gold league", registrants = setOf("QZK6P")),
            KnownEvidence(setOf("universal music"), setOf("DEUM7")),
            rules
        )
        assertEquals(ArtistIdentityEvidence.Verdict.CLEAR, verdict)
    }

    @Test
    fun `a trusted registrant clears foreign artist credits`() {
        val rules = TaughtRules(emptySet(), setOf("DEUM7"), emptySet(), emptySet())
        val verdict = evaluate(
            signals(registrants = setOf("DEUM7"), foreignArtistIds = listOf("111", "222")),
            KnownEvidence.NONE,
            rules
        )
        assertFalse(verdict.suspect)
        assertNull(verdict.reason)
    }

    @Test
    fun `foreign artist credits flag a release even when the source is known`() {
        val verdict = evaluate(
            signals(holder = "division recordings", foreignArtistIds = listOf("111", "222")),
            KnownEvidence(setOf("division recordings"), emptySet()),
            TaughtRules.NONE
        )
        assertTrue(verdict.suspect)
        assertFalse(verdict.blocked)
        assertEquals("credited to Apple artist(s) 111, 222 instead of the resolved artist", verdict.reason)
    }

    @Test
    fun `compilations clear but block rules still flag them`() {
        val clear = evaluate(
            signals(holder = "13652890 records dk", registrants = setOf("QZK6P"), compilation = true),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertFalse(clear.suspect)

        val blocked = evaluate(
            signals(holder = "13652890 records dk", registrants = setOf("QZK6P"), compilation = true),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            TaughtRules(emptySet(), emptySet(), setOf("13652890 records dk"), emptySet())
        )
        assertTrue(blocked.suspect)
        assertTrue(blocked.blocked)
        assertEquals("blocked copyright holder \"13652890 records dk\"", blocked.reason)
    }

    @Test
    fun `a release without any signal is clear`() {
        val verdict = evaluate(
            signals(),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertEquals(ArtistIdentityEvidence.Verdict.CLEAR, verdict)
    }

    @Test
    fun `without known evidence nothing is suspect`() {
        val verdict = evaluate(
            signals(holder = "13652890 records dk", label = "13652890 Records DK", registrants = setOf("QZK6P")),
            KnownEvidence.NONE,
            TaughtRules.NONE
        )
        assertFalse(verdict.suspect)
        assertNull(verdict.reason)
    }

    @Test
    fun `signals of a kind that cannot be compared are clear`() {
        val registrantOnlySignal = evaluate(
            signals(registrants = setOf("QZK6P")),
            KnownEvidence(setOf("division recordings"), emptySet()),
            TaughtRules.NONE
        )
        assertFalse(registrantOnlySignal.suspect)

        val nameOnlyKnown = evaluate(
            signals(holder = "13652890 records dk"),
            KnownEvidence(emptySet(), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertFalse(nameOnlyKnown.suspect)
    }

    @Test
    fun `one matching kind clears even when the other is unknown`() {
        val registrantKnown = evaluate(
            signals(holder = "13652890 records dk", registrants = setOf("DEUM7")),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertFalse(registrantKnown.suspect)

        val holderKnown = evaluate(
            signals(holder = "division / rca & gold league", registrants = setOf("QZK6P")),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertFalse(holderKnown.suspect)
    }

    @Test
    fun `an entirely unknown source is suspect with a listing reason`() {
        val verdict = evaluate(
            signals(holder = "13652890 records dk", label = "13652890 Records DK", registrants = setOf("QZK6P")),
            KnownEvidence(setOf("division recordings"), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertTrue(verdict.suspect)
        assertFalse(verdict.blocked)
        assertEquals(
            "copyright holder \"13652890 records dk\", label \"13652890 Records DK\" and " +
                "ISRC registrant \"QZK6P\" never seen for this artist",
            verdict.reason
        )
    }

    @Test
    fun `several unknown registrants and a genre hint are appended`() {
        val verdict = evaluate(
            signals(
                registrants = setOf("QZK6P", "QMDA7"),
                genreHint = "genre Pop, artist mostly Hip-Hop/Rap"
            ),
            KnownEvidence(emptySet(), setOf("DEUM7")),
            TaughtRules.NONE
        )
        assertTrue(verdict.suspect)
        assertEquals(
            "ISRC registrants \"QMDA7\", \"QZK6P\" never seen for this artist " +
                "(genre Pop, artist mostly Hip-Hop/Rap)",
            verdict.reason
        )
    }
}
