package dev.dertyp.services.release

import dev.dertyp.data.ReleaseSource
import dev.dertyp.data.ReleaseType
import dev.dertyp.services.release.ReleaseVersions.Facet
import dev.dertyp.services.release.ReleaseVersions.Member
import dev.dertyp.services.release.ReleaseVersions.VersionKey
import dev.dertyp.services.release.ReleaseVersions.cluster
import dev.dertyp.services.release.ReleaseVersions.primary
import dev.dertyp.services.release.ReleaseVersions.versionKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ReleaseVersionsTest {

    private val artistId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val otherArtistId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun dayMs(days: Long) = days * 24L * 60 * 60 * 1000

    private fun facet(
        title: String,
        artistId: UUID = this.artistId,
        type: ReleaseType = ReleaseType.Album,
        date: Long? = null,
        id: UUID = UUID.randomUUID(),
        source: ReleaseSource = ReleaseSource.MusicBrainz,
        suspect: Boolean = false,
        hidden: Boolean = false
    ) = Facet(artistId, type, versionKey(title), date, id, source, suspect, hidden)

    @Test
    fun `versionKey ignores explicit, deluxe, remaster, edit and instrumental markers`() {
        assertEquals(VersionKey("album", 0), versionKey("Album"))
        assertEquals(VersionKey("album", 1), versionKey("Album (Explicit)"))
        assertEquals(VersionKey("album", 1), versionKey("Album (Clean)"))
        assertEquals(VersionKey("album", 1), versionKey("Album (Deluxe Edition)"))
        assertEquals(VersionKey("album", 1), versionKey("Album - 2011 Remaster"))
        assertEquals(VersionKey("album", 1), versionKey("Album (Instrumental)"))
        assertEquals(VersionKey("song", 1), versionKey("Song (Radio Edit)"))
    }

    @Test
    fun `versionKey strips the Apple single and EP suffix`() {
        assertEquals(VersionKey("song", 0), versionKey("Song - Single"))
        assertEquals(VersionKey("song", 0), versionKey("Song"))
        assertEquals(VersionKey("song", 0), versionKey("Song - EP"))
    }

    @Test
    fun `versionKey keeps live, remix and featuring markers apart`() {
        assertEquals(VersionKey("album|live", 0), versionKey("Album (Live)"))
        assertEquals(VersionKey("song|remix", 0), versionKey("Song (Remix)"))
        assertEquals(VersionKey("song|feat. drake", 0), versionKey("Song (feat. Drake)"))
    }

    @Test
    fun `versionKey counts the stripped version markers`() {
        assertEquals(0, versionKey("Album").versionTagCount)
        assertEquals(1, versionKey("Album (Explicit)").versionTagCount)
        assertEquals(1, versionKey("Album (Clean)").versionTagCount)
        assertEquals(1, versionKey("Album (Deluxe Edition)").versionTagCount)
        assertEquals(1, versionKey("Album - 2011 Remaster").versionTagCount)
        assertEquals(1, versionKey("Album (Instrumental)").versionTagCount)
        assertEquals(1, versionKey("Song (Radio Edit)").versionTagCount)
    }

    @Test
    fun `versionKey counts explicit and clean markers as version markers`() {
        assertEquals(VersionKey("album", 0), versionKey("Album"))
        assertEquals(VersionKey("album", 1), versionKey("Album (Explicit)"))
        assertEquals(VersionKey("album", 1), versionKey("Album [Clean]"))
        assertEquals(VersionKey("album", 1), versionKey("Album - Explicit"))
    }

    @Test
    fun `cluster starts a new group when the window from the earliest member is exceeded`() {
        val items = listOf("Album" to 0L, "Album" to dayMs(60), "Album" to dayMs(120))
        val groups = cluster(items) { (title, date) -> facet(title, date = date) }

        assertEquals(2, groups.size)
        assertEquals(listOf(0L, dayMs(60)), groups[0].map { it.facet.date })
        assertEquals(listOf(dayMs(120)), groups[1].map { it.facet.date })
    }

    @Test
    fun `cluster never groups undated entries`() {
        val items = listOf("Album", "Album", "Album")
        val groups = cluster(items) { facet(it) }

        assertEquals(3, groups.size)
        assertTrue(groups.all { it.size == 1 })
    }

    @Test
    fun `cluster keeps hidden and visible entries apart`() {
        val items = listOf(false, true)
        val groups = cluster(items) { hidden -> facet("Album", date = 0L, hidden = hidden) }

        assertEquals(2, groups.size)
        assertEquals(1, groups.count { it.single().facet.hidden })
        assertEquals(1, groups.count { !it.single().facet.hidden })
    }

    @Test
    fun `cluster keeps different artists and release types apart`() {
        val byArtist = cluster(listOf(artistId, otherArtistId)) { id -> facet("Album", artistId = id, date = 0L) }
        assertEquals(2, byArtist.size)

        val byType = cluster(listOf(ReleaseType.Album, ReleaseType.Single)) { type ->
            facet("Album", type = type, date = 0L)
        }
        assertEquals(2, byType.size)
    }

    @Test
    fun `primary prefers MusicBrainz, then non-suspect, then the plainest title, then the earliest date`() {
        val apple = Member("apple", facet("Album", source = ReleaseSource.Apple, date = 0L))
        val suspectMb = Member("suspect", facet("Album", source = ReleaseSource.MusicBrainz, suspect = true, date = 0L))
        val versioned = Member(
            "versioned",
            facet("Album (Deluxe Edition)", source = ReleaseSource.MusicBrainz, suspect = false, date = 0L)
        )
        val later = Member("later", facet("Album", source = ReleaseSource.MusicBrainz, suspect = false, date = dayMs(10)))
        val earliest = Member("earliest", facet("Album", source = ReleaseSource.MusicBrainz, suspect = false, date = dayMs(5)))

        assertEquals(earliest, primary(listOf(apple, suspectMb, versioned, later, earliest)))
    }

    @Test
    fun `primary prefers the plain title over the explicit edition`() {
        val explicitId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val plainId = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val explicit = Member(
            "explicit",
            facet("Album (Explicit)", source = ReleaseSource.Apple, date = 0L, id = explicitId)
        )
        val plain = Member("plain", facet("Album", source = ReleaseSource.Apple, date = 0L, id = plainId))

        assertEquals(plain, primary(listOf(explicit, plain)))
    }
}
