package dev.dertyp.services.ui

import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRegistrar
import dev.dertyp.plugins.UiRegistration
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

data class RegisteredContribution(val contribution: UiContribution, val source: String)

class UiRegistry {
    private val logger = KtorSimpleLogger("UiRegistry")
    private val contributions = ConcurrentHashMap<String, RegisteredContribution>()
    private val invalidationFlow = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val invalidations: Flow<String> = invalidationFlow.asSharedFlow()

    fun register(contribution: UiContribution, source: String): UiRegistration {
        require(ID_PATTERN.matches(contribution.id)) { "Invalid contribution id '${contribution.id}', expected ${ID_PATTERN.pattern}" }
        val existing = contributions.putIfAbsent(contribution.id, RegisteredContribution(contribution, source))
        if (existing != null) {
            logger.warn("Ignoring duplicate UI contribution registration: ${contribution.id} (from $source, already registered by ${existing.source})")
            return UiRegistration {}
        }
        logger.info("Registered UI contribution ${contribution.id} (${contribution.kind}${contribution.slot?.let { ", slot $it" } ?: ""}) from $source")
        return UiRegistration { contributions.remove(contribution.id, RegisteredContribution(contribution, source)) }
    }

    fun invalidate(contributionId: String) {
        invalidationFlow.tryEmit(contributionId)
    }

    fun invalidateSource(source: String) {
        bySource(source).forEach { invalidate(it.contribution.id) }
    }

    fun get(id: String): RegisteredContribution? = contributions[id]

    fun all(): List<RegisteredContribution> = contributions.values.sortedWith(compareBy({ it.contribution.order }, { it.contribution.id }))

    fun bySlot(slot: String): List<RegisteredContribution> = all().filter { it.contribution.slot == slot }

    fun bySource(source: String): List<RegisteredContribution> = all().filter { it.source == source }

    fun forSource(source: String): UiRegistrar = object : UiRegistrar {
        override fun register(contribution: UiContribution): UiRegistration = this@UiRegistry.register(contribution, source)
        override fun invalidate(contributionId: String) = this@UiRegistry.invalidate(contributionId)
    }

    companion object {
        const val SERVER_SOURCE = "server"
        val ID_PATTERN = Regex("[a-z0-9._-]+")
    }
}
