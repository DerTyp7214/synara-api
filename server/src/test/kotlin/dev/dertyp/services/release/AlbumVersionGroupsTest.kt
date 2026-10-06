package dev.dertyp.services.release

import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.services.release.AlbumVersionGroups.Edition
import dev.dertyp.services.release.AlbumVersionGroups.groups
import dev.dertyp.services.release.AlbumVersionGroups.mainFirst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class AlbumVersionGroupsTest {

    private val artist = UUID.fromString("a0000000-0000-0000-0000-000000000001")
    private val otherArtist = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val releaseGroup = UUID.fromString("b0000000-0000-0000-0000-000000000001")
    private val otherReleaseGroup = UUID.fromString("b0000000-0000-0000-0000-000000000002")
    private val cover = UUID.fromString("c0000000-0000-0000-0000-000000000001")
    private val otherCover = UUID.fromString("c0000000-0000-0000-0000-000000000002")

    private val deluxe = TitleTag(TitleTagKind.VERSION, "Deluxe Edition")
    private val remaster = TitleTag(TitleTagKind.REMASTER, "2011 Remaster")

    private fun id(number: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(number))

    private fun edition(
        number: Int,
        title: String = "Album",
        tags: List<TitleTag> = emptyList(),
        artists: Set<UUID> = setOf(artist),
        image: UUID? = null,
        group: UUID? = null,
        date: LocalDate? = null,
        hasExplicitSong: Boolean = false
    ) = Edition(id(number), title, tags, artists, image, group, date, hasExplicitSong)

    private fun ids(groups: List<List<Edition>>): Set<Set<UUID>> =
        groups.map { group -> group.map { it.id }.toSet() }.toSet()

    @Test
    fun `albums of the same release group are one group`() {
        val result = groups(
            listOf(
                edition(1, group = releaseGroup),
                edition(2, title = "Album Reissue", artists = setOf(otherArtist), group = releaseGroup),
                edition(3, title = "Other", group = otherReleaseGroup),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2)), setOf(id(3))), ids(result))
    }

    @Test
    fun `unlinked albums group by name and album artists`() {
        val result = groups(
            listOf(
                edition(1),
                edition(2, tags = listOf(deluxe)),
                edition(3, artists = setOf(otherArtist)),
                edition(4, artists = setOf(artist, otherArtist)),
                edition(5, title = "Another"),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2)), setOf(id(3)), setOf(id(4)), setOf(id(5))), ids(result))
    }

    @Test
    fun `the name match ignores case and whitespace differences`() {
        val result = groups(
            listOf(
                edition(1, title = "The  Album"),
                edition(2, title = " the album "),
                edition(3, title = "THE ALBUM", group = releaseGroup),
                edition(4, title = "TheAlbum"),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2), id(3)), setOf(id(4))), ids(result))
    }

    @Test
    fun `albums without album artists never group by name`() {
        val result = groups(
            listOf(
                edition(1, artists = emptySet()),
                edition(2, artists = emptySet()),
                edition(3, artists = emptySet(), group = releaseGroup),
            )
        )

        assertEquals(setOf(setOf(id(1)), setOf(id(2)), setOf(id(3))), ids(result))
    }

    @Test
    fun `albums without album artists still group by the same cover`() {
        val result = groups(
            listOf(
                edition(1, artists = emptySet(), image = cover),
                edition(2, artists = emptySet(), image = cover),
                edition(3, artists = emptySet()),
                edition(4, title = "Linked", artists = emptySet(), image = otherCover, group = releaseGroup),
                edition(5, artists = emptySet(), image = otherCover),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2)), setOf(id(3)), setOf(id(4), id(5))), ids(result))
    }

    @Test
    fun `unlinked albums group by the same cover`() {
        val result = groups(
            listOf(
                edition(1, title = "Album", image = cover),
                edition(2, title = "Album Live", artists = setOf(otherArtist), image = cover),
                edition(3, title = "Third", image = otherCover),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2)), setOf(id(3))), ids(result))
    }

    @Test
    fun `name and cover matches chain into one cluster`() {
        val result = groups(
            listOf(
                edition(1, title = "Album"),
                edition(2, title = "Album", image = cover),
                edition(3, title = "Renamed", image = cover),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2), id(3))), ids(result))
    }

    @Test
    fun `unlinked albums without a cover and with different names stay alone`() {
        val result = groups(
            listOf(
                edition(1, title = "First"),
                edition(2, title = "Second"),
                edition(3, title = "Third"),
            )
        )

        assertEquals(setOf(setOf(id(1)), setOf(id(2)), setOf(id(3))), ids(result))
    }

    @Test
    fun `two release groups with the same name and artists stay apart`() {
        val result = groups(
            listOf(
                edition(1, title = "Self Titled", group = releaseGroup, image = cover),
                edition(2, title = "Self Titled", group = otherReleaseGroup, image = cover),
            )
        )

        assertEquals(setOf(setOf(id(1)), setOf(id(2))), ids(result))
    }

    @Test
    fun `an unlinked cluster attaches to the only release group it matches by name and artists`() {
        val result = groups(
            listOf(
                edition(1, group = releaseGroup),
                edition(2, tags = listOf(deluxe)),
                edition(3, tags = listOf(remaster)),
                edition(4, title = "Other", group = otherReleaseGroup),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2), id(3)), setOf(id(4))), ids(result))
    }

    @Test
    fun `an unlinked cluster attaches to the only release group it matches by cover`() {
        val result = groups(
            listOf(
                edition(1, title = "Album", group = releaseGroup, image = cover),
                edition(2, title = "Album Live", artists = setOf(otherArtist), image = cover),
                edition(3, title = "Other", group = otherReleaseGroup, image = otherCover),
            )
        )

        assertEquals(setOf(setOf(id(1), id(2)), setOf(id(3))), ids(result))
    }

    @Test
    fun `an unlinked cluster matching two release groups stays its own group`() {
        val result = groups(
            listOf(
                edition(1, title = "Self Titled", group = releaseGroup),
                edition(2, title = "Self Titled", group = otherReleaseGroup),
                edition(3, title = "Self Titled"),
                edition(4, title = "Self Titled", tags = listOf(deluxe)),
            )
        )

        assertEquals(setOf(setOf(id(1)), setOf(id(2)), setOf(id(3), id(4))), ids(result))
    }

    @Test
    fun `a cluster matching one release group by name and another by cover stays its own group`() {
        val result = groups(
            listOf(
                edition(1, title = "Album", group = releaseGroup),
                edition(2, title = "Other", group = otherReleaseGroup, image = cover),
                edition(3, title = "Album", image = cover),
            )
        )

        assertEquals(setOf(setOf(id(1)), setOf(id(2)), setOf(id(3))), ids(result))
    }

    @Test
    fun `groups and their members come back in id order`() {
        val result = groups(
            listOf(
                edition(4, title = "Second"),
                edition(3, group = releaseGroup),
                edition(2, title = "Second"),
                edition(1),
            )
        )

        assertEquals(listOf(listOf(id(1), id(3)), listOf(id(2), id(4))), result.map { group -> group.map { it.id } })
    }

    @Test
    fun `explicit preference puts an edition with an explicit song first`() {
        val members = listOf(
            edition(1),
            edition(2, tags = listOf(deluxe), hasExplicitSong = true),
            edition(3, tags = listOf(deluxe, remaster), hasExplicitSong = true),
        )

        assertEquals(listOf(id(2), id(3), id(1)), mainFirst(members, explicit = true).map { it.id })
    }

    @Test
    fun `clean preference puts an edition without an explicit song first`() {
        val members = listOf(
            edition(1, hasExplicitSong = true),
            edition(2, tags = listOf(deluxe)),
            edition(3, tags = listOf(deluxe, remaster)),
        )

        assertEquals(listOf(id(2), id(3), id(1)), mainFirst(members, explicit = false).map { it.id })
    }

    @Test
    fun `the preference has no effect when all editions are the same kind`() {
        val allExplicit = listOf(
            edition(1, tags = listOf(deluxe), hasExplicitSong = true),
            edition(2, hasExplicitSong = true),
        )
        val allClean = listOf(
            edition(1, tags = listOf(deluxe)),
            edition(2),
        )

        assertEquals(listOf(id(2), id(1)), mainFirst(allExplicit, explicit = true).map { it.id })
        assertEquals(listOf(id(2), id(1)), mainFirst(allExplicit, explicit = false).map { it.id })
        assertEquals(listOf(id(2), id(1)), mainFirst(allClean, explicit = true).map { it.id })
        assertEquals(listOf(id(2), id(1)), mainFirst(allClean, explicit = false).map { it.id })
    }

    @Test
    fun `the untagged edition beats tagged ones`() {
        val members = listOf(
            edition(1, tags = listOf(deluxe, remaster), date = LocalDate.of(2000, 1, 1)),
            edition(2, tags = listOf(remaster), date = LocalDate.of(2001, 1, 1)),
            edition(3, date = LocalDate.of(2002, 1, 1)),
        )

        assertEquals(listOf(id(3), id(2), id(1)), mainFirst(members, explicit = true).map { it.id })
    }

    @Test
    fun `the earliest release date wins among equally tagged editions and a missing date ranks last`() {
        val members = listOf(
            edition(1),
            edition(2, date = LocalDate.of(2012, 5, 1)),
            edition(3, date = LocalDate.of(2010, 5, 1)),
        )

        assertEquals(listOf(id(3), id(2), id(1)), mainFirst(members, explicit = true).map { it.id })
    }

    @Test
    fun `the id breaks remaining ties`() {
        val date = LocalDate.of(2010, 5, 1)
        val members = listOf(
            edition(3, date = date),
            edition(1, date = date),
            edition(2, date = date),
        )

        assertEquals(listOf(id(1), id(2), id(3)), mainFirst(members, explicit = true).map { it.id })
        assertEquals(listOf(id(1), id(2), id(3)), mainFirst(members.reversed(), explicit = true).map { it.id })
    }
}
