package dev.dertyp

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

class TestDatabaseCleanupExtension : AfterAllCallback {
    override fun afterAll(context: ExtensionContext) {
        val nested = context.parent.flatMap { it.parent }.isPresent
        if (!nested) TestDatabase.cleanUpLeftovers(context.requiredTestClass.name)
    }
}
