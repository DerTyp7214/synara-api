package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

enum class SearchIndexEntityType {
    SONG, ALBUM, ARTIST
}

object SearchIndexQueueTable : Table("search_index_queue") {
    val id = integer("id").autoIncrement()
    val entityType = enumerationByName("entity_type", 20, SearchIndexEntityType::class)
    val entityId = javaUUID("entity_id")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("unique_pending_entity", entityType, entityId)
    }
}
