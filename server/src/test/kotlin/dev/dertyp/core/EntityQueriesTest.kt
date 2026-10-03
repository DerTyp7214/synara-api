package dev.dertyp.core

import dev.dertyp.data.PaginatedResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class EntityQueriesTest {
    private data class Item(val id: UUID, val label: String)

    private fun legacyOrder(ids: List<UUID>, items: List<Item>): List<Item> =
        ids.mapNotNull { id -> items.find { it.id == id } }

    @Test
    fun `orderByIds follows the id order and skips missing ids`() {
        val ids = List(5) { UUID.randomUUID() }
        val items = listOf(ids[3], ids[0], ids[4], ids[1]).map { Item(it, it.toString()) }

        val ordered = orderByIds(ids, items) { it.id }

        assertEquals(listOf(ids[0], ids[1], ids[3], ids[4]), ordered.map { it.id })
        assertEquals(legacyOrder(ids, items), ordered)
    }

    @Test
    fun `orderByIds keeps the first item for a duplicated id and repeats duplicated ids`() {
        val id = UUID.randomUUID()
        val other = UUID.randomUUID()
        val items = listOf(Item(id, "first"), Item(other, "other"), Item(id, "second"))
        val ids = listOf(other, id, id)

        val ordered = orderByIds(ids, items) { it.id }

        assertEquals(legacyOrder(ids, items), ordered)
        assertEquals(listOf("other", "first", "first"), ordered.map { it.label })
    }

    @Test
    fun `orderByIds matches the legacy lookup on a large shuffled page`() {
        val ids = List(3000) { UUID.randomUUID() }
        val items = ids.shuffled().filterIndexed { index, _ -> index % 7 != 0 }.map { Item(it, it.toString()) }

        assertEquals(legacyOrder(ids, items), orderByIds(ids, items) { it.id })
    }

    @Test
    fun `idOrderedPage builds the page with the next page flag`() {
        val ids = List(3) { UUID.randomUUID() }
        val items = ids.reversed().map { Item(it, it.toString()) }

        val page = idOrderedPage(ids, items, total = 10, page = 1, pageSize = 3) { it.id }

        assertEquals(
            PaginatedResponse(
                data = ids.map { Item(it, it.toString()) },
                total = 10,
                page = 1,
                pageSize = 3,
                hasNextPage = true,
            ),
            page
        )
        assertEquals(false, idOrderedPage(ids, items, total = 9, page = 2, pageSize = 3) { it.id }.hasNextPage)
        assertEquals(
            false,
            idOrderedPage(ids, items, total = 9, page = 0, pageSize = Int.MAX_VALUE) { it.id }.hasNextPage
        )
    }
}
