package dev.dertyp.db

import dev.dertyp.data.CoverStyle
import dev.dertyp.data.ImageSource
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object UserPlaylistTable : UUIDTable("userPlaylist") {
    val name = text("name")
    val description = text("description")
    val customIdentifier = text("customIdentifier").nullable()
    val creator = reference("creator", UserTable.id)
    val imageId = reference("imageId", ImageTable.id).nullable()
    val origin = text("origin").nullable()
    val imageSource = enumerationByName("imageSource", 16, ImageSource::class).nullable()
    val coverStyle = enumerationByName("coverStyle", 32, CoverStyle::class).nullable()
    val coverSeed = long("coverSeed").nullable()
}
