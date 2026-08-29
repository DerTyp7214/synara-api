package dev.dertyp.services.ui

import dev.dertyp.plugins.TranslationRegistrar
import dev.dertyp.plugins.UiTranslator
import dev.dertyp.serializers.AppJson
import io.ktor.util.logging.KtorSimpleLogger
import java.util.concurrent.ConcurrentHashMap

class TranslationService(private val registry: UiRegistry) {
    private val logger = KtorSimpleLogger("TranslationService")
    private val bundles = ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, String>>>()

    init {
        forSource(UiRegistry.SERVER_SOURCE).registerBundlesFromResources(javaClass.classLoader, "i18n/core", CORE_LOCALES)
    }

    fun registerBundle(source: String, locale: String, messages: Map<String, String>) {
        bundles.getOrPut(source) { ConcurrentHashMap() }[normalize(locale)] = messages
        registry.invalidateSource(source)
    }

    fun remove(source: String, locale: String) {
        bundles[source]?.remove(normalize(locale))
        registry.invalidateSource(source)
    }

    fun locales(source: String): Set<String> = bundles[source]?.keys?.toSet() ?: emptySet()

    fun resolve(source: String, locale: String, key: String): String? {
        val normalized = normalize(locale)
        val language = normalized.substringBefore('-')
        val chain = listOf(normalized, language, DEFAULT_LOCALE).distinct()
        val sources = if (source == UiRegistry.SERVER_SOURCE) listOf(source) else listOf(source, UiRegistry.SERVER_SOURCE)
        for (s in sources) {
            val perLocale = bundles[s] ?: continue
            for (l in chain) {
                perLocale[l]?.get(key)?.let { return it }
            }
        }
        return null
    }

    fun translator(source: String, locale: String): UiTranslator = object : UiTranslator {
        override val locale: String = normalize(locale)
        override fun t(key: String, vararg args: Pair<String, String>): String {
            val template = resolve(source, locale, key) ?: key
            return args.fold(template) { text, (name, value) -> text.replace("{$name}", value) }
        }
    }

    fun forSource(source: String): TranslationRegistrar = object : TranslationRegistrar {
        override fun registerBundle(locale: String, messages: Map<String, String>) =
            this@TranslationService.registerBundle(source, locale, messages)

        override fun registerBundlesFromResources(classLoader: ClassLoader, basePath: String, locales: List<String>) {
            locales.forEach { locale ->
                val path = "${basePath.trimEnd('/')}/$locale.json"
                val text = classLoader.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
                if (text == null) {
                    logger.warn("Translation bundle not found: $path (source $source)")
                    return@forEach
                }
                registerBundle(locale, AppJson.decodeFromString<Map<String, String>>(text))
            }
        }

        override fun remove(locale: String) = this@TranslationService.remove(source, locale)

        override fun locales(): Set<String> = this@TranslationService.locales(source)
    }

    companion object {
        const val DEFAULT_LOCALE = "en"
        val CORE_LOCALES = listOf("en", "de")

        fun normalize(locale: String): String = locale.trim().lowercase().replace('_', '-')
    }
}
