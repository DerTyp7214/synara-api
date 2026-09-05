package dev.dertyp.services.intake

import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IntakeOffer
import dev.dertyp.plugins.IntakeResolver
import dev.dertyp.plugins.UiAccess
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiHookKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class IntakeHandlerInfoTest {
    private class NamedResolver(private val name: String, override val access: UiAccess = UiAccess()) : IntakeResolver {
        override val id = "import.${name.lowercase()}"
        override val titleKey = "intake.import.title"
        override val titleArgs = mapOf("name" to name)
        override val jobKind = "import"
        override suspend fun offer(items: List<IntakeItem>, user: UserInfo): IntakeOffer? = null
    }

    @Test
    fun `handler infos fill the title placeholders and respect access`() {
        val service = IntakeService(TranslationService(UiRegistry()))
        service.register(NamedResolver("Tidal"), UiRegistry.SERVER_SOURCE)
        service.register(NamedResolver("Restricted", UiAccess(capabilities = setOf(UserCapability.IMPORT))), UiRegistry.SERVER_SOURCE)

        val plain = User(UUID.randomUUID(), "u", passwordHash = "")
        val infos = service.handlerInfos(plain, "en")
        assertEquals(listOf("Import with Tidal"), infos.map { it.title })
        assertEquals("import.tidal", infos.single().id)
        assertTrue(infos.single().kinds.containsAll(UiHookKind.entries))

        val importer = User(UUID.randomUUID(), "i", passwordHash = "", capabilities = listOf(UserCapability.IMPORT))
        assertEquals(listOf("Import with Restricted", "Import with Tidal"), service.handlerInfos(importer, "en").map { it.title })
    }
}
