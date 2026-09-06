package dev.dertyp.services.intake

import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IntakeOffer
import dev.dertyp.plugins.IntakeRegistrar
import dev.dertyp.plugins.IntakeResolver
import dev.dertyp.plugins.UiRegistration
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiHookHandler
import dev.dertyp.ui.UiHookHandlerInfo
import dev.dertyp.ui.UiHookKind
import dev.dertyp.ui.UiIntakeResult
import dev.dertyp.ui.UiIntakeStatus
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class IntakeService(private val translations: TranslationService) {
    private val logger = KtorSimpleLogger("IntakeService")

    data class Registered(val resolver: IntakeResolver, val source: String)

    class Resolved(val registered: Registered, val offer: IntakeOffer, val handler: UiHookHandler)

    private val resolvers = ConcurrentHashMap<String, Registered>()
    private val providers = CopyOnWriteArrayList<Pair<String, () -> List<IntakeResolver>>>()

    fun register(resolver: IntakeResolver, source: String): UiRegistration {
        require(UiRegistry.ID_PATTERN.matches(resolver.id)) { "Invalid intake resolver id '${resolver.id}'" }
        val existing = resolvers.putIfAbsent(resolver.id, Registered(resolver, source))
        if (existing != null) {
            logger.warn("Ignoring duplicate intake resolver registration: ${resolver.id} (from $source, already registered by ${existing.source})")
            return UiRegistration {}
        }
        return UiRegistration { resolvers.remove(resolver.id, Registered(resolver, source)) }
    }

    fun registerProvider(source: String, provider: () -> List<IntakeResolver>) {
        providers += source to provider
    }

    fun forSource(source: String): IntakeRegistrar = IntakeRegistrar { register(it, source) }

    fun all(): List<Registered> =
        (resolvers.values + providers.flatMap { (source, provider) -> provider().map { Registered(it, source) } })
            .distinctBy { it.resolver.id }
            .sortedBy { it.resolver.id }

    suspend fun resolve(items: List<IntakeItem>, user: User, locale: String): List<Resolved> {
        if (items.isEmpty()) return emptyList()
        val info = UserInfo.fromUser(user)
        val candidates = all().filter { it.resolver.access.allows(info) }
        val resolved = supervisorScope {
            candidates.map { registered ->
                async {
                    try {
                        registered.resolver.offer(items, info)?.takeIf { it.accepted.isNotEmpty() }?.let { offer ->
                            Resolved(registered, offer, handler(registered, offer, locale))
                        }
                    } catch (e: Exception) {
                        logger.error("Intake resolver ${registered.resolver.id} failed", e)
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        return resolved.sortedWith(compareByDescending<Resolved> { it.offer.priority }.thenBy { it.registered.resolver.id })
    }

    suspend fun handlers(items: List<IntakeItem>, user: User, locale: String): List<UiHookHandler> =
        resolve(items, user, locale).map { it.handler }

    fun handlerInfos(user: User, locale: String): List<UiHookHandlerInfo> {
        val info = UserInfo.fromUser(user)
        return all().filter { it.resolver.access.allows(info) }.map { registered ->
            val resolver = registered.resolver
            val t = translations.translator(registered.source, locale)
            UiHookHandlerInfo(
                id = resolver.id,
                source = registered.source,
                title = t.t(resolver.titleKey, *resolver.titleArgs.toList().toTypedArray()),
                description = resolver.descriptionKey?.let { t.t(it) },
                icon = resolver.icon,
                kinds = UiHookKind.entries,
            )
        }
    }

    private fun handler(registered: Registered, offer: IntakeOffer, locale: String): UiHookHandler {
        val resolver = registered.resolver
        val t = translations.translator(registered.source, locale)
        val args = offer.titleArgs.toList().toTypedArray()
        val confirmText = offer.confirmKey?.let { t.t(it, *args) }
        return UiHookHandler(
            id = resolver.id,
            contributionId = resolver.id,
            source = registered.source,
            title = t.t(offer.titleKey ?: resolver.titleKey, *args),
            description = (offer.descriptionKey ?: resolver.descriptionKey)?.let { t.t(it, *args) },
            icon = offer.icon ?: resolver.icon,
            action = offer.action ?: UiAction.Intake(offer.accepted, resolver.id, confirmText),
            confirmText = confirmText,
        )
    }

    suspend fun submit(items: List<IntakeItem>, resolverId: String?, user: User, locale: String): UiIntakeResult {
        if (items.isEmpty()) return UiIntakeResult(UiIntakeStatus.UNHANDLED, accepted = 0)
        var resolved = resolve(items, user, locale)
        if (resolverId != null) {
            resolved = resolved.filter { it.registered.resolver.id == resolverId }
            if (resolved.isEmpty()) {
                val known = all().any { it.resolver.id == resolverId }
                return UiIntakeResult(
                    if (known) UiIntakeStatus.UNHANDLED else UiIntakeStatus.ERROR,
                    message = translations.translator(UiRegistry.SERVER_SOURCE, locale).t(if (known) "intake.nothingAccepted" else "intake.unknownHandler"),
                    rejected = items,
                )
            }
        }

        val submitting = resolved.filter { it.offer.submit != null }
        val navigational = resolved.filter { it.offer.action != null }

        val conflicting = resolverId == null && items.any { item -> submitting.count { item in it.offer.accepted } > 1 }
        if (conflicting) {
            return UiIntakeResult(UiIntakeStatus.NEEDS_CHOICE, handlers = resolved.map { it.handler })
        }

        var accepted = 0
        val messages = mutableListOf<String>()
        var next: UiAction? = null
        var failed = false
        for (entry in submitting) {
            val t = translations.translator(entry.registered.source, locale)
            try {
                val receipt = entry.offer.submit!!.invoke()
                accepted += receipt.accepted
                receipt.messageKey?.let { messages += t.t(it, "count" to receipt.accepted.toString()) }
                if (next == null) next = receipt.next
            } catch (e: Exception) {
                logger.error("Intake submission to ${entry.registered.resolver.id} failed", e)
                failed = true
                messages += (e.message ?: e::class.simpleName.orEmpty())
            }
        }

        val acceptedItems = submitting.flatMap { it.offer.accepted }.toSet()
        val rejected = items.filter { it !in acceptedItems }

        return when {
            failed -> UiIntakeResult(UiIntakeStatus.ERROR, messages.joinToString("\n"), accepted, rejected, navigational.map { it.handler }, next)
            submitting.isEmpty() && navigational.isNotEmpty() -> UiIntakeResult(UiIntakeStatus.NEEDS_CHOICE, rejected = rejected, handlers = navigational.map { it.handler })
            submitting.isEmpty() -> UiIntakeResult(UiIntakeStatus.UNHANDLED, rejected = rejected)
            else -> UiIntakeResult(UiIntakeStatus.OK, messages.joinToString("\n").ifBlank { null }, accepted, rejected, navigational.map { it.handler }, next)
        }
    }
}
