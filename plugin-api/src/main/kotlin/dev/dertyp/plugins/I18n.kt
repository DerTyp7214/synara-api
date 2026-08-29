package dev.dertyp.plugins

interface TranslationRegistrar {
    fun registerBundle(locale: String, messages: Map<String, String>)
    fun registerBundlesFromResources(classLoader: ClassLoader, basePath: String, locales: List<String>)
    fun remove(locale: String)
    fun locales(): Set<String>
}
