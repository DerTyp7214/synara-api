package dev.dertyp

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

class TestDatabaseCleanupExtension : BeforeAllCallback, AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        val store = context.root.getStore(ExtensionContext.Namespace.GLOBAL)
        store.put(TestContainers::class, TestContainers)
        store.put(TestDatabase::class, AutoCloseable(TestDatabase::shutDown))
    }

    override fun afterAll(context: ExtensionContext) {
        val nested = context.parent.flatMap { it.parent }.isPresent
        if (!nested) TestDatabase.cleanUpLeftovers(context.requiredTestClass.name)
    }
}
