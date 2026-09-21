package dev.dertyp.db

import dev.dertyp.services.release.ArtistSourceRuleKind
import dev.dertyp.services.release.ArtistSourceRulePolarity
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object ArtistSourceRuleTable : Table("artist_source_rule") {
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val kind = enumerationByName("kind", 32, ArtistSourceRuleKind::class)
    val value = varchar("value", 255)
    val rule = enumerationByName("rule", 16, ArtistSourceRulePolarity::class)
    val createdBy = reference("createdBy", UserTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(artistId, provider, kind, value)
}
