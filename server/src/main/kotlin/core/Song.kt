package dev.dertyp.core

import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

fun Query.withArtistNames(artistNames: List<String>): Query = this.andWhere {
    exists(
        SongArtistTable
            .innerJoin(
                ArtistTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistTable.id },
            )
            .select(SongArtistTable.songId)
            .where {
                (SongArtistTable.songId eq SongTable.id) and
                        (ArtistTable.name inList artistNames)
            }
    )
}