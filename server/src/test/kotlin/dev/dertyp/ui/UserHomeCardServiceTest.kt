package dev.dertyp.ui

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserHomeCardTable
import dev.dertyp.db.UserTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ui.UserHomeCardService
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.test.assertEquals

class UserHomeCardServiceTest {
    private val service = UserHomeCardService()
    private val accountId = UUID.randomUUID()
    private val otherAccountId = UUID.randomUUID()

    private val available = listOf("core.a", "core.b", "core.c").map {
        UiContributionInfo(it, "server", UiContributionKind.HOME_CARD, null, it, cardSize = UiCardSize.SMALL)
    }

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "home_card_test")
        dbQuery {
            SchemaUtils.create(ImageTable, UserTable, UserHomeCardTable)
            UserTable.insert {
                it[id] = accountId
                it[username] = "tester"
                it[passwordHash] = "x"
            }
            UserTable.insert {
                it[id] = otherAccountId
                it[username] = "other"
                it[passwordHash] = "x"
            }
        }
    }

    @AfterEach
    fun tearDown() = TestDatabase.cleanUp()

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `layout lists pinned cards first in order, then the rest`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val initial = service.layoutFor(accountId, available)
        assertEquals(listOf(false, false, false), initial.cards.map { it.pinned })
        assertEquals(listOf(0, 1, 2), initial.cards.map { it.position })

        service.setPinned(accountId, "core.c", true)
        service.setPinned(accountId, "core.a", true)
        var layout = service.layoutFor(accountId, available)
        assertEquals(listOf("core.c", "core.a", "core.b"), layout.cards.map { it.contributionId })
        assertEquals(listOf(true, true, false), layout.cards.map { it.pinned })
        assertEquals(UiCardSize.SMALL, layout.cards.first().size)

        service.setOrder(accountId, listOf("core.a", "core.c"))
        layout = service.layoutFor(accountId, available)
        assertEquals(listOf("core.a", "core.c", "core.b"), layout.cards.map { it.contributionId })

        service.setPinned(accountId, "core.a", false)
        layout = service.layoutFor(accountId, available)
        assertEquals(listOf("core.c", "core.a", "core.b"), layout.cards.map { it.contributionId })
        assertEquals(listOf(true, false, false), layout.cards.map { it.pinned })

        assertEquals(listOf("core.c"), service.layoutFor(accountId, available.take(0) + available[2]).cards.map { it.contributionId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `layout flow emits on changes of the same user only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val emissions = mutableListOf<UiHomeLayout>()
        val job = launch { service.layoutFlow(accountId) { available }.take(2).toList(emissions) }
        while (emissions.isEmpty()) yield()
        service.setPinned(otherAccountId, "core.a", true)
        service.setPinned(accountId, "core.b", true)
        job.join()
        assertEquals(2, emissions.size)
        assertEquals("core.b", emissions.last().cards.first().contributionId)
    }
}
