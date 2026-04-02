package dev.dertyp.services

import dev.dertyp.db.GenreTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import java.util.*

class GenreService : Service() {
    suspend fun getOrCreateGenres(names: List<String>): List<UUID> {
        if (names.isEmpty()) return emptyList()
        
        val normalizedNames = names.map { it.lowercase().trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedNames.isEmpty()) return emptyList()

        return dbQuery {
            val existingGenres = GenreTable
                .select(GenreTable.id, GenreTable.name)
                .where { GenreTable.name inList normalizedNames }
                .associate { it[GenreTable.name] to it[GenreTable.id].value }

            val newNames = normalizedNames.filter { it !in existingGenres.keys }
            
            val newGenreIds = if (newNames.isNotEmpty()) {
                GenreTable.batchInsert(newNames) { name ->
                    this[GenreTable.name] = name
                }.map { it[GenreTable.id].value }
            } else {
                emptyList()
            }

            existingGenres.values + newGenreIds
        }
    }
}
