package dev.dertyp.services.ui

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.UserInfo
import dev.dertyp.db.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ImageService
import dev.dertyp.services.ListenService
import dev.dertyp.services.ListenShareService
import dev.dertyp.services.UserService
import dev.dertyp.testing.*
import dev.dertyp.ui.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

class ListenShareContributionsTest {
    private lateinit var database: Database
    private lateinit var service: ListenShareService
    private lateinit var entry: ListenShareEntryContribution
    private lateinit var settings: ListenShareSettingsContribution
    private lateinit var adminEntry: ListenShareAdminEntryContribution
    private lateinit var admin: ListenShareAdminContribution
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)

    private fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single<HookBus> { mockk(relaxed = true) }
                single { mockk<ChangeNotifier>(relaxed = true) }
                single { mockk<UiRegistry>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { ListenService() }
            })
        }
        database = TestDatabase.connect(
            dialect, "listen_share_ui_test",
            UserTable,
            ImageTable,
            ImageMetadataTable,
            UserCapabilityTable,
            ListenBrainzUserTable,
            UserListenBrainzLinkTable,
            ListenShareTable,
        )
        service = ListenShareService()
        entry = ListenShareEntryContribution()
        settings = ListenShareSettingsContribution(service)
        adminEntry = ListenShareAdminEntryContribution()
        admin = ListenShareAdminContribution(service, UserService())
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun info(id: UUID, admin: Boolean = false) =
        UserInfo(id = id, username = "user_$id", isAdmin = admin, capabilities = emptyList())

    private fun scope(user: UserInfo) = UiRenderScope(
        user = user,
        context = UiContext(),
        i18n = translations.translator(UiRegistry.SERVER_SOURCE, "en"),
        settings = mockk(relaxed = true),
        clientSchemaVersion = UiSchemaVersion.CURRENT,
    )

    private fun UiComponent.flatten(): List<UiComponent> = listOf(this) + when (this) {
        is UiComponent.Column -> children.flatMap { it.flatten() }
        is UiComponent.Row -> children.flatMap { it.flatten() }
        is UiComponent.Section -> children.flatMap { it.flatten() }
        is UiComponent.Card -> (children + actions).flatMap { it.flatten() }
        is UiComponent.Form -> (children + actions).flatMap { it.flatten() }
        else -> emptyList()
    }

    private fun UiComponent.invokes(actionId: String): List<UiAction.Invoke> =
        flatten().mapNotNull { (it as? UiComponent.Button)?.action as? UiAction.Invoke }
            .filter { it.actionId == actionId }

    private fun insertNamedUser(name: String): UUID {
        val uid = UUID.randomUUID()
        UserTable.insert {
            it[id] = uid
            it[username] = name
            it[passwordHash] = "hash"
        }
        return uid
    }

    private fun giveProfileImage(userId: UUID, hash: String): UUID {
        val imageId = UUID.randomUUID()
        ImageTable.insert {
            it[id] = imageId
            it[path] = "avatar.jpg"
            it[imageHash] = "hash_$imageId"
            it[origin] = "test"
            it[blurHash] = hash
        }
        UserTable.update({ UserTable.id eq userId }) { it[profileImage] = imageId }
        return imageId
    }

    private fun values(vararg pairs: Pair<String, UiValue>) = mapOf(*pairs)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `entries open their pages and the admin entry is admin only`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val member = info(UUID.randomUUID())
        val root = info(UUID.randomUUID(), admin = true)

        assertEquals(UiSlots.SETTINGS, entry.slot)
        assertEquals(UiSlots.ADMIN_DASHBOARD, adminEntry.slot)
        assertTrue(entry.access.allows(member))
        assertFalse(adminEntry.access.allows(member))
        assertTrue(adminEntry.access.allows(root))
        assertFalse(admin.access.allows(member))
        assertTrue(admin.access.allows(root))
        assertEquals(UiContributionKind.PAGE, settings.kind)
        assertEquals(UiContributionKind.PAGE, admin.kind)

        val item = entry.render(scope(member)) as UiComponent.ListItem
        assertEquals(UiAction.OpenPage(ListenShareService.SETTINGS_CONTRIBUTION_ID), item.action)
        val adminItem = adminEntry.render(scope(root)) as UiComponent.ListItem
        assertEquals(UiAction.OpenPage(ListenShareService.ADMIN_CONTRIBUTION_ID), adminItem.action)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `settings show an empty state without links and candidates`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val u = transaction(database) { insertUser() }

        assertTrue(settings.render(scope(info(u))) is UiComponent.EmptyState)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `settings list links with toggle and disconnect and candidates with connect`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (u, partner, candidate) = transaction(database) {
            val shared = insertLbUser()
            val u = insertUser()
            val partner = insertUser()
            val candidate = insertUser()
            linkListenBrainzUser(u, shared)
            linkListenBrainzUser(partner, shared)
            linkListenBrainzUser(candidate, shared)
            Triple(u, partner, candidate)
        }
        val linkId = transaction(database) { linkListenShare(u, partner, useForRecommendations = true) }

        val page = settings.render(scope(info(u)))
        val forms = page.flatten().filterIsInstance<UiComponent.Form>()
        assertEquals(1, forms.size)
        val switch = forms.single().children.filterIsInstance<UiComponent.Switch>().single()
        assertTrue(switch.value)
        val title = forms.single().children.filterIsInstance<UiComponent.ListItem>().single().title
        assertEquals("user_$partner", title)

        val unlink = page.invokes("unlink").single()
        assertTrue(unlink.confirmText != null)
        assertEquals(UiValue.of(linkId.toString()), unlink.params.getValue("linkId"))
        val connect = page.invokes("connect").single()
        assertEquals(UiValue.of(candidate.toString()), connect.params.getValue("userId"))
        val candidateCard = page.flatten().filterIsInstance<UiComponent.Card>().single()
        assertEquals(null, candidateCard.image)
        assertEquals(UiIcon(UiIconName.USER), candidateCard.icon)

        val imageId = transaction(database) { giveProfileImage(candidate, "blur") }
        val pictured = settings.render(scope(info(u))).flatten().filterIsInstance<UiComponent.Card>().single()
        assertEquals(imageId, pictured.image?.imageId)
        assertEquals("blur", pictured.image?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `partner rows carry the profile image and fall back to the user icon`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (u, withImage, without, imageId) = transaction(database) {
            val shared = insertLbUser()
            val u = insertUser()
            val withImage = insertUser()
            val without = insertUser()
            linkListenBrainzUser(u, shared)
            linkListenBrainzUser(withImage, shared)
            linkListenBrainzUser(without, shared)
            Quad(u, withImage, without, giveProfileImage(withImage, "blur"))
        }
        transaction(database) {
            linkListenShare(u, withImage, useForRecommendations = true)
            linkListenShare(u, without, useForRecommendations = true)
        }

        val items = settings.render(scope(info(u))).flatten().filterIsInstance<UiComponent.Form>()
            .flatMap { it.children.filterIsInstance<UiComponent.ListItem>() }
            .associateBy { it.title }

        assertEquals(UiImageRef(imageId, "blur"), items.getValue("user_$withImage").image)
        assertEquals(UiIcon(UiIconName.USER), items.getValue("user_$withImage").icon)
        assertEquals(null, items.getValue("user_$without").image)
        assertEquals(UiIcon(UiIconName.USER), items.getValue("user_$without").icon)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `toggle unlink and connect invokes refresh the page`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (u, partner, candidate) = transaction(database) {
            val shared = insertLbUser()
            val ids = List(3) { insertUser() }
            ids.forEach { linkListenBrainzUser(it, shared) }
            ids
        }
        val me = scope(info(u))

        val connected = settings.invoke(me, "connect", values("userId" to UiValue.of(partner.toString())))
        assertEquals(UiInvokeStatus.OK, connected.status)
        assertTrue(connected.refresh)
        val link = service.linksOf(u).single()
        assertFalse(link.useForRecommendations)
        assertEquals(partner, link.partner.id)

        val toggled = settings.invoke(
            me, "toggle",
            values("linkId" to UiValue.of(link.id.toString()), "useForRecommendations" to UiValue.of(true))
        )
        assertTrue(toggled.refresh)
        assertTrue(service.linksOf(u).single().useForRecommendations)

        val unlinked = settings.invoke(me, "unlink", values("linkId" to UiValue.of(link.id.toString())))
        assertTrue(unlinked.refresh)
        assertTrue(service.linksOf(u).isEmpty())
        assertEquals(setOf(partner, candidate), service.candidates(u).map { it.id }.toSet())

        val missing = settings.invoke(me, "connect", emptyMap())
        assertEquals(UiInvokeStatus.ERROR, missing.status)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `admin page lists all links and a link form with search fields`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b) = transaction(database) { insertUser() to insertUser() }
        transaction(database) { linkListenShare(a, b) }

        val page = admin.render(scope(info(UUID.randomUUID(), admin = true)))
        val forms = page.flatten().filterIsInstance<UiComponent.Form>()
        val linkForm = forms.single { it.id == "link" }
        assertEquals(
            listOf("userA", "userB"),
            linkForm.children.filterIsInstance<UiComponent.SearchField>().map { it.key }
        )
        assertEquals("useForRecommendations", linkForm.children.filterIsInstance<UiComponent.Switch>().single().key)
        assertEquals(1, page.invokes("unlink").size)
        val title = forms.single { it.id != "link" }.children.filterIsInstance<UiComponent.ListItem>().single().title
        assertTrue(title.contains("user_$a") && title.contains("user_$b"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `admin link invoke links two users and reports missing selections`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (a, b) = transaction(database) { insertUser() to insertUser() }
        val root = scope(info(UUID.randomUUID(), admin = true))

        val missing = admin.invoke(root, "link", values("userA" to UiValue.of(a.toString())))
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, missing.status)
        assertEquals(setOf("userB"), missing.fieldErrors.keys)
        assertTrue(service.allLinks().isEmpty())

        val none = admin.invoke(root, "link", emptyMap())
        assertEquals(setOf("userA", "userB"), none.fieldErrors.keys)

        val linked = admin.invoke(
            root, "link",
            values(
                "userA" to UiValue.of(a.toString()),
                "userB" to UiValue.of(b.toString()),
                "useForRecommendations" to UiValue.of(true),
            )
        )
        assertEquals(UiInvokeStatus.OK, linked.status)
        assertTrue(linked.refresh)
        val pair = service.allLinks().single()
        assertTrue(pair.useForRecommendations)

        val toggled = admin.invoke(
            root, "toggle",
            values("linkId" to UiValue.of(pair.id.toString()), "useForRecommendations" to UiValue.of(false))
        )
        assertTrue(toggled.refresh)
        assertFalse(service.allLinks().single().useForRecommendations)

        val removed = admin.invoke(root, "unlink", values("linkId" to UiValue.of(pair.id.toString())))
        assertTrue(removed.refresh)
        assertTrue(service.allLinks().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `search matches usernames case insensitively and limits the results`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val alice = transaction(database) {
            repeat(25) { insertNamedUser("bulk_%02d".format(it)) }
            insertNamedUser("bob")
            insertNamedUser("malice")
            insertNamedUser("Alice")
        }
        val root = scope(info(UUID.randomUUID(), admin = true))

        val found = admin.search(root, "userA", "ALIC")
        assertEquals(setOf("Alice", "malice"), found.map { it.label }.toSet())
        assertEquals(2, found.size)
        assertEquals(alice.toString(), found.single { it.label == "Alice" }.value)
        assertEquals(20, admin.search(root, "userB", "bulk").size)
        assertTrue(admin.search(root, "other", "alice").isEmpty())
        assertTrue(admin.search(root, "userA", "  ").isEmpty())
        assertTrue(admin.search(root, "userA", "nobody").isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `search options carry the profile image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val imageId = transaction(database) { giveProfileImage(insertNamedUser("pictured"), "blur") }
        transaction(database) { insertNamedUser("plain") }
        val root = scope(info(UUID.randomUUID(), admin = true))

        val options = admin.search(root, "userA", "p").associateBy { it.label }

        assertEquals(UiImageRef(imageId, "blur"), options.getValue("pictured").image)
        assertEquals(null, options.getValue("plain").image)
        assertEquals(UiIcon(UiIconName.USER), options.getValue("plain").icon)
    }
}
