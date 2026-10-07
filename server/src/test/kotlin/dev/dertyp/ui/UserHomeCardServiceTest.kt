package dev.dertyp.ui

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.ChangeTopic
import dev.dertyp.db.ImageTable
import dev.dertyp.db.UserHomeCardTable
import dev.dertyp.db.UserTable
import dev.dertyp.core.db.dbQuery
import dev.dertyp.services.ui.UserHomeCardService
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import kotlin.test.assertEquals

class UserHomeCardServiceTest {
    private val changeNotifier = mockk<ChangeNotifier>(relaxed = true)
    private val service = UserHomeCardService()
    private val accountId = UUID.randomUUID()
    private val otherAccountId = UUID.randomUUID()

    private val available = listOf("core.a", "core.b", "core.c").map {
        UiContributionInfo(it, "server", UiContributionKind.HOME_CARD, null, it, cardSize = UiCardSize.SMALL)
    }

    private fun setup(dialect: DbDialect) = runBlocking {
        startKoin { modules(module { single { changeNotifier } }) }
        TestDatabase.connect(dialect, "home_card_test", ImageTable, UserTable, UserHomeCardTable)
        dbQuery {
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
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

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

        assertEquals(
            listOf("core.c"),
            service.layoutFor(accountId, available.take(0) + available[2]).cards.map { it.contributionId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `pinning, ordering and forgetting announce the home cards of the user`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        service.setPinned(accountId, "core.a", true)
        verify(exactly = 1) { changeNotifier.notify(accountId, ChangeTopic.HOME_CARDS) }

        service.setOrder(accountId, listOf("core.a"))
        verify(exactly = 2) { changeNotifier.notify(accountId, ChangeTopic.HOME_CARDS) }

        service.forget(accountId, "core.a")
        verify(exactly = 3) { changeNotifier.notify(accountId, ChangeTopic.HOME_CARDS) }
        verify(exactly = 0) { changeNotifier.notify(otherAccountId, any()) }
    }
}
