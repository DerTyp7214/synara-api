package dev.dertyp.core

import dev.dertyp.db.ProviderColumns
import dev.dertyp.services.import.Type
import dev.dertyp.utils.parsers.ParserFactory
import org.jetbrains.exposed.v1.core.*
import java.util.UUID

const val PROVIDER_LOOKUP_CHUNK_SIZE = 5000

data class ProviderLookup(val provider: String, val externalId: String)

suspend fun providerLookup(id: String, type: Type? = null): ProviderLookup? {
    val parser = ParserFactory.getParser(id) ?: return null
    val parsed = parser.parse(id) ?: return null
    if (type != null && parsed.second != null && parsed.second != type) return null
    return ProviderLookup(parser.name, parsed.first)
}

fun ProviderColumns.matchesAny(lookups: Collection<ProviderLookup>): Op<Boolean> =
    if (lookups.isEmpty()) Op.FALSE
    else lookups
        .groupBy({ it.provider }, { it.externalId })
        .map { (name, externalIds) -> (provider eq name) and (externalId inList externalIds.distinct()) }
        .reduce { acc, op -> acc or op }

data class ProviderUrlRow(val entityId: UUID, val rawUrl: String, val provider: String, val externalId: String)

fun resolveUrlWinners(
    urls: Collection<String>,
    lookups: Map<String, ProviderLookup>,
    exactMatches: Collection<Pair<UUID, String>>,
    providerRows: Collection<ProviderUrlRow>,
    order: Comparator<UUID>,
): Map<String, UUID?> {
    val exactByUrl = exactMatches.groupBy({ it.second }, { it.first })
    val providerByRawUrl = providerRows.groupBy({ it.rawUrl }, { it.entityId })
    val providerByLookup = providerRows.groupBy({ ProviderLookup(it.provider, it.externalId) }, { it.entityId })

    return urls.associateWith { url ->
        exactByUrl[url]?.minWithOrNull(order)
            ?: ((providerByRawUrl[url] ?: emptyList()) + (lookups[url]?.let { providerByLookup[it] } ?: emptyList()))
                .minWithOrNull(order)
    }
}
