package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ChangeTopic
import dev.dertyp.data.UserInfo
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.testing.*
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class ListenShareServiceTest {
    private lateinit var database: Database
    private lateinit var service: ListenShareService
    private val changeNotifier = mockk<ChangeNotifier>(relaxed = true)
    private val uiRegistry = mockk<UiRegistry>(relaxed = true)

    private fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { changeNotifier }
                single { uiRegistry }
                single { ListenService() }
            })
        }
        database = TestDatabase.connect(
            dialect, "listen_share_test",
            UserTable,
            ImageTable,
            ListenBrainzUserTable,
            UserListenBrainzLinkTable,
            ListenShareTable,
        )
        service = ListenShareService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun actor(id: UUID, admin: Boolean = false) =
        UserInfo(id = id, username = "user_$id", isAdmin = admin, capabilities = emptyList())

    private fun admin() = actor(UUID.randomUUID(), admin = true)

    private fun relinkListenBrainz(userId: UUID, lbUserId: UUID) {
        UserListenBrainzLinkTable.update({ UserListenBrainzLinkTable.userId eq userId }) {
            it[listenBrainzUserId] = lbUserId
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an admin links any two users as an ordered pair`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b) = transaction(database) { insertUser() to insertUser() }

        val pair = service.link(admin(), b, a, useForRecommendations = true)

        assertEquals(listOf(a, b).sorted(), listOf(pair.userA.id, pair.userB.id))
        assertTrue(pair.userA.id < pair.userB.id)
        assertTrue(pair.useForRecommendations)
        assertEquals("user_$a", if (pair.userA.id == a) pair.userA.username else pair.userB.username)
        assertEquals(listOf(b), service.linksOf(a).map { it.partner.id })
        assertEquals(listOf(a), service.linksOf(b).map { it.partner.id })
        assertEquals(listOf(pair.id), service.allLinks().map { it.id })
        transaction(database) {
            val row = ListenShareTable.selectAll().single()
            assertEquals(pair.userA.id, row[ListenShareTable.userA].value)
            assertEquals(pair.userB.id, row[ListenShareTable.userB].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a member links itself only to a user on the same ListenBrainz account`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ids = transaction(database) {
            val shared = insertLbUser()
            val u = insertUser()
            val sameAccount = insertUser()
            val otherAccount = insertUser()
            val noAccount = insertUser()
            val stranger = insertUser()
            linkListenBrainzUser(u, shared)
            linkListenBrainzUser(sameAccount, shared)
            linkListenBrainzUser(stranger, shared)
            linkListenBrainzUser(otherAccount, insertLbUser())
            listOf(u, sameAccount, otherAccount, noAccount, stranger)
        }
        val (u, sameAccount, otherAccount, noAccount, stranger) = ids

        assertThrows<UnauthorizedException> { service.link(actor(u), u, otherAccount, false) }
        assertThrows<UnauthorizedException> { service.link(actor(u), u, noAccount, false) }
        assertThrows<UnauthorizedException> { service.link(actor(noAccount), noAccount, u, false) }
        assertThrows<UnauthorizedException> { service.link(actor(stranger), u, sameAccount, false) }

        val pair = service.link(actor(sameAccount), u, sameAccount, false)

        assertFalse(pair.useForRecommendations)
        assertEquals(listOf(sameAccount), service.linksOf(u).map { it.partner.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `after a ListenBrainz change a member can still disconnect but not connect again`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val (u, partner) = transaction(database) {
                val shared = insertLbUser()
                val u = insertUser()
                val partner = insertUser()
                linkListenBrainzUser(u, shared)
                linkListenBrainzUser(partner, shared)
                u to partner
            }
            val first = service.link(actor(u), u, partner, false)
            transaction(database) { relinkListenBrainz(partner, insertLbUser()) }

            service.unlink(actor(partner), first.id)

            assertEquals(emptyList<ListenShareLink>(), service.linksOf(u))
            assertThrows<UnauthorizedException> { service.link(actor(u), u, partner, false) }
            service.link(admin(), u, partner, false)
            assertEquals(listOf(partner), service.linksOf(u).map { it.partner.id })
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `candidates are the unlinked users on the same ListenBrainz account`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ids = transaction(database) {
            val shared = insertLbUser()
            val u = insertUser()
            val p = insertUser()
            val q = insertUser()
            val other = insertUser()
            val noAccount = insertUser()
            linkListenBrainzUser(u, shared)
            linkListenBrainzUser(p, shared)
            linkListenBrainzUser(q, shared)
            linkListenBrainzUser(other, insertLbUser())
            listOf(u, p, q, other, noAccount)
        }
        val (u, p, q, _, noAccount) = ids

        assertEquals(setOf(p, q), service.candidates(u).map { it.id }.toSet())
        assertEquals(emptyList<UUID>(), service.candidates(noAccount).map { it.id })

        service.link(actor(u), u, p, false)

        assertEquals(listOf(q), service.candidates(u).map { it.id })
        assertEquals(listOf(q), service.candidates(p).map { it.id })
        assertEquals(setOf(u, p), service.candidates(q).map { it.id }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a pair is linked once in either order and never to itself`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b) = transaction(database) { insertUser() to insertUser() }

        service.link(admin(), a, b, false)

        assertThrows<IllegalArgumentException> { service.link(admin(), a, b, false) }
        assertThrows<IllegalArgumentException> { service.link(admin(), b, a, true) }
        assertThrows<IllegalArgumentException> { service.link(admin(), a, a, false) }
        assertThrows<IllegalArgumentException> { service.link(admin(), a, UUID.randomUUID(), false) }
        assertThrows<ExposedSQLException> { transaction(database) { linkListenShare(b, a) } }
        transaction(database) { assertEquals(1L, ListenShareTable.selectAll().count()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `members and admins change a link and others are rejected`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b, stranger) = transaction(database) { Triple(insertUser(), insertUser(), insertUser()) }
        val link = service.link(admin(), a, b, false)

        service.setUseForRecommendations(actor(a), link.id, true)
        assertTrue(service.linksOf(b).single().useForRecommendations)
        service.setUseForRecommendations(actor(b), link.id, false)
        assertFalse(service.linksOf(a).single().useForRecommendations)
        service.setUseForRecommendations(admin(), link.id, true)
        assertTrue(service.linksOf(a).single().useForRecommendations)

        assertThrows<UnauthorizedException> { service.setUseForRecommendations(actor(stranger), link.id, false) }
        assertThrows<UnauthorizedException> { service.unlink(actor(stranger), link.id) }
        assertThrows<IllegalArgumentException> { service.unlink(actor(a), UUID.randomUUID()) }
        assertTrue(service.linksOf(a).single().useForRecommendations)

        service.unlink(admin(), link.id)
        assertEquals(emptyList<ListenSharePair>(), service.allLinks())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ownerIds follow the scope and the recommendations option`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (u, withRecs, withoutRecs) = transaction(database) { Triple(insertUser(), insertUser(), insertUser()) }
        service.link(admin(), u, withRecs, true)
        val plain = service.link(admin(), withoutRecs, u, false)

        assertEquals(setOf(u), service.ownerIds(u, ListenScope.OWN))
        assertEquals(setOf(u, withRecs, withoutRecs), service.ownerIds(u, ListenScope.SHARED))
        assertEquals(setOf(u, withRecs), service.ownerIds(u, ListenScope.RECOMMENDATIONS))
        assertEquals(setOf(withRecs, u), service.ownerIds(withRecs, ListenScope.SHARED))

        service.setUseForRecommendations(actor(u), plain.id, true)
        assertEquals(setOf(u, withRecs, withoutRecs), service.ownerIds(u, ListenScope.RECOMMENDATIONS))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a user removes its links`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b, c) = transaction(database) { Triple(insertUser(), insertUser(), insertUser()) }
        service.link(admin(), a, b, false)
        service.link(admin(), b, c, false)

        transaction(database) { UserTable.deleteWhere { UserTable.id eq b } }

        assertEquals(emptyList<ListenSharePair>(), service.allLinks())
        assertEquals(setOf(a), service.ownerIds(a, ListenScope.SHARED))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `every change announces listens to both users and refreshes the pages`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b, stranger) = transaction(database) { Triple(insertUser(), insertUser(), insertUser()) }

        val link = service.link(admin(), a, b, false)
        service.setUseForRecommendations(actor(a), link.id, true)
        service.unlink(actor(b), link.id)

        verify(exactly = 3) { changeNotifier.notify(a, ChangeTopic.LISTENS) }
        verify(exactly = 3) { changeNotifier.notify(b, ChangeTopic.LISTENS) }
        verify(exactly = 0) { changeNotifier.notify(stranger, any()) }
        verify(exactly = 3) { uiRegistry.invalidate(ListenShareService.SETTINGS_CONTRIBUTION_ID) }
        verify(exactly = 3) { uiRegistry.invalidate(ListenShareService.ADMIN_CONTRIBUTION_ID) }
    }
}
