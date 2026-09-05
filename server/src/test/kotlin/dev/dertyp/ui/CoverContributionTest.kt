package dev.dertyp.ui

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.CoverGenerationOptions
import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverPackInfo
import dev.dertyp.data.CoverStyle
import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.data.ImageSource
import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.services.cover.CoverGenerationService
import dev.dertyp.services.jobs.JobService
import dev.dertyp.services.ui.CoverSlotContribution
import dev.dertyp.services.ui.PlaylistCoverContribution
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.ServerUiRenderScope
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class CoverContributionTest {
    private val owner = User(UUID.randomUUID(), "owner", passwordHash = "")
    private val stranger = User(UUID.randomUUID(), "stranger", passwordHash = "")
    private val admin = User(UUID.randomUUID(), "admin", passwordHash = "", isAdmin = true)
    private val playlistId = UUID.randomUUID()
    private val target = CoverTarget(CoverTargetType.PLAYLIST, playlistId)
    private val client = ClientInfo(ApiVersion.CURRENT, UiSchemaVersion.CURRENT, "en")
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val settings = PluginSettingsService().forPlugin(UiRegistry.SERVER_SOURCE)

    private val service = mockk<CoverGenerationService>()
    private val contribution = PlaylistCoverContribution(service, JobService())

    private fun scope(user: User, entityId: UUID? = playlistId) = ServerUiRenderScope(
        user = UserInfo.fromUser(user),
        context = UiContext(entityType = UiEntityType.PLAYLIST, entityId = entityId),
        i18n = translations.translator(UiRegistry.SERVER_SOURCE, "en"),
        settings = settings,
        clientSchemaVersion = UiSchemaVersion.CURRENT,
        account = user,
        client = client,
        call = null,
    )

    private fun row(source: ImageSource?, imageId: UUID? = if (source == null) null else UUID.randomUUID()) =
        CoverGenerationService.TargetRow(target, "Late Night", owner.id, imageId, source, CoverStyle.GRID.takeIf { source == ImageSource.GENERATED }, 7L)

    private fun UiComponent.flatten(): List<UiComponent> = listOf(this) + when (this) {
        is UiComponent.Column -> children.flatMap { it.flatten() }
        is UiComponent.Row -> children.flatMap { it.flatten() }
        is UiComponent.Card -> (children + actions).flatMap { it.flatten() }
        is UiComponent.Section -> children.flatMap { it.flatten() }
        is UiComponent.Form -> (children + actions).flatMap { it.flatten() }
        else -> emptyList()
    }

    private fun stub(source: ImageSource?, nsfw: Boolean = false) {
        coEvery { service.row(target) } returns row(source)
        every { service.options() } returns CoverGenerationOptions(CoverStyle.entries, listOf(CoverPackInfo("grunge", "Grunge")), nsfw)
        every { service.nsfwEnabled } returns nsfw
    }

    @Test
    fun `owner sees the form with style, pack and title fields`() = runBlocking {
        stub(ImageSource.GENERATED)
        val tree = contribution.render(scope(owner)).flatten()
        val selects = tree.filterIsInstance<UiComponent.Select>()
        assertEquals(listOf(CoverSlotContribution.FIELD_STYLE, CoverSlotContribution.FIELD_PACK), selects.map { it.key })
        assertEquals(CoverStyle.GRID.name, selects[0].value)
        assertEquals(listOf("", "grunge"), selects[1].options.map { it.value })
        assertEquals(listOf(CoverSlotContribution.FIELD_TITLE), tree.filterIsInstance<UiComponent.Switch>().map { it.key })
        assertTrue(tree.filterIsInstance<UiComponent.Image>().isNotEmpty())
        assertTrue(tree.filterIsInstance<UiComponent.Badge>().any { it.text == "Generated cover" })
        assertFalse(tree.filterIsInstance<UiComponent.Button>().any { it.label == "Remove custom cover" })
    }

    @Test
    fun `user covers get a reset button and nsfw switch appears only when enabled`() = runBlocking {
        stub(ImageSource.USER, nsfw = true)
        val tree = contribution.render(scope(owner)).flatten()
        assertTrue(tree.filterIsInstance<UiComponent.Button>().any { it.label == "Remove custom cover" && (it.action as UiAction.Invoke).confirmText != null })
        assertEquals(listOf(CoverSlotContribution.FIELD_TITLE, CoverSlotContribution.FIELD_NSFW), tree.filterIsInstance<UiComponent.Switch>().map { it.key })
    }

    @Test
    fun `strangers see a hint, admins see the form, missing context renders nothing`() = runBlocking {
        stub(null)
        val hint = contribution.render(scope(stranger))
        assertTrue(hint is UiComponent.Text && hint.text == "Only the owner can change the cover.")
        assertTrue(contribution.render(scope(admin)).flatten().any { it is UiComponent.Form })
        assertTrue(contribution.render(scope(owner, entityId = null)) is UiComponent.Spacer)
    }

    @Test
    fun `apply parses form values and rejects non-owners`() = runBlocking {
        stub(null)
        coEvery { service.apply(target, any()) } returns UUID.randomUUID()
        val values = mapOf(
            CoverSlotContribution.FIELD_STYLE to UiValue.of("MOSAIC"),
            CoverSlotContribution.FIELD_PACK to UiValue.of("grunge"),
            CoverSlotContribution.FIELD_TITLE to UiValue.of(false),
            CoverSlotContribution.FIELD_NSFW to UiValue.of(true),
        )
        val result = contribution.invoke(scope(owner), CoverSlotContribution.ACTION_APPLY, values)
        assertEquals(UiInvokeStatus.OK, result.status)
        assertTrue(result.refresh)
        coVerify { service.apply(target, CoverGenerationParams(CoverStyle.MOSAIC, null, allowNsfw = true, includeTitle = false, pack = "grunge")) }

        val shuffle = contribution.invoke(scope(owner), CoverSlotContribution.ACTION_SHUFFLE, emptyMap())
        assertEquals(UiInvokeStatus.OK, shuffle.status)
        coVerify { service.apply(target, match { it.seed != null && it.style == CoverStyle.AUTO }) }

        val denied = contribution.invoke(scope(stranger), CoverSlotContribution.ACTION_APPLY, values)
        assertEquals(UiInvokeStatus.ERROR, denied.status)
    }

    @Test
    fun `reset delegates to the service`() = runBlocking {
        stub(ImageSource.USER)
        coEvery { service.reset(target) } returns true
        val result = contribution.invoke(scope(owner), CoverSlotContribution.ACTION_RESET, emptyMap())
        assertEquals(UiInvokeStatus.OK, result.status)
        coVerify { service.reset(target) }
    }
}
