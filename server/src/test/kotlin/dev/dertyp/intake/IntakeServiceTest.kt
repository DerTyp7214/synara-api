package dev.dertyp.intake

import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IntakeOffer
import dev.dertyp.plugins.IntakeReceipt
import dev.dertyp.plugins.IntakeResolver
import dev.dertyp.plugins.UiAccess
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiIntakeStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class IntakeServiceTest {
    private val service = IntakeService(TranslationService(UiRegistry()))
    private val user = User(UUID.randomUUID(), "u", passwordHash = "", capabilities = listOf(UserCapability.IMPORT))
    private val plain = User(UUID.randomUUID(), "p", passwordHash = "")

    private val tidalUrl = IntakeItem.Url("https://tidal.com/x")
    private val appleUrl = IntakeItem.Url("https://music.apple.com/x")
    private val text = IntakeItem.Text("hello")

    private class Fake(
        override val id: String,
        private val accepts: (IntakeItem) -> Boolean,
        private val fail: Boolean = false,
        override val access: UiAccess = UiAccess(),
        private val navigational: Boolean = false,
    ) : IntakeResolver {
        override val titleKey = "importer.hook.import"
        override val jobKind = "test"
        val submitted = mutableListOf<List<IntakeItem>>()

        override suspend fun offer(items: List<IntakeItem>, user: UserInfo): IntakeOffer? {
            if (fail) throw IllegalStateException("boom")
            val accepted = items.filter(accepts)
            if (accepted.isEmpty()) return null
            return if (navigational) IntakeOffer(accepted, action = UiAction.OpenNative("externalSearch"))
            else IntakeOffer(accepted, submit = { submitted += accepted; IntakeReceipt(accepted.size, "importer.queued") })
        }
    }

    private val tidal = Fake("import.tidal", { it is IntakeItem.Url && it.url.contains("tidal") || it == appleUrl })
    private val gamdl = Fake("import.gamdl", { it == appleUrl })
    private val search = Fake("search.external", { it is IntakeItem.Text }, navigational = true)
    private val broken = Fake("broken", { true }, fail = true)
    private val adminOnly = Fake("admin.only", { true }, access = UiAccess(requiresAdmin = true))

    init {
        listOf(tidal, gamdl, search, broken, adminOnly).forEach { service.register(it, "server") }
    }

    @Test
    fun `unambiguous items are submitted immediately`() = runBlocking {
        val result = service.submit(listOf(tidalUrl, text), null, user, "en")
        assertEquals(UiIntakeStatus.OK, result.status)
        assertEquals(1, result.accepted)
        assertEquals("1 items queued", result.message)
        assertEquals(listOf(text), result.rejected)
        assertEquals(listOf("search.external"), result.handlers.map { it.id })
        assertEquals(listOf(listOf(tidalUrl)), tidal.submitted)
    }

    @Test
    fun `overlapping offers need a choice and the chosen handler submits`() = runBlocking {
        val choice = service.submit(listOf(appleUrl), null, user, "en")
        assertEquals(UiIntakeStatus.NEEDS_CHOICE, choice.status)
        assertEquals(listOf("import.gamdl", "import.tidal"), choice.handlers.map { it.id })
        val action = choice.handlers[0].action as UiAction.Intake
        assertEquals(listOf(appleUrl), action.items)
        assertEquals("import.gamdl", action.resolverId)
        assertTrue(tidal.submitted.isEmpty() && gamdl.submitted.isEmpty())

        val picked = service.submit(action.items, action.resolverId, user, "en")
        assertEquals(UiIntakeStatus.OK, picked.status)
        assertEquals(listOf(listOf(appleUrl)), gamdl.submitted)
        assertTrue(tidal.submitted.isEmpty())
    }

    @Test
    fun `text only yields navigational handlers, nothing acceptable is unhandled`() = runBlocking {
        val textOnly = service.submit(listOf(text), null, user, "en")
        assertEquals(UiIntakeStatus.NEEDS_CHOICE, textOnly.status)
        assertEquals(UiAction.OpenNative("externalSearch"), textOnly.handlers.single().action)

        val nothing = service.submit(listOf(IntakeItem.Id("nope", "1")), null, user, "en")
        assertEquals(UiIntakeStatus.UNHANDLED, nothing.status)
        assertEquals(listOf(IntakeItem.Id("nope", "1")), nothing.rejected)
    }

    @Test
    fun `access is enforced and failing resolvers are skipped`() = runBlocking {
        assertTrue(service.handlers(listOf(tidalUrl), plain, "en").none { it.id == "admin.only" })
        val admin = User(UUID.randomUUID(), "a", passwordHash = "", isAdmin = true)
        assertTrue(service.handlers(listOf(tidalUrl), admin, "en").any { it.id == "admin.only" })
        val unknown = service.submit(listOf(tidalUrl), "missing", user, "en")
        assertEquals(UiIntakeStatus.ERROR, unknown.status)
    }
}
