package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.services.EntityChangeService
import org.koin.core.component.inject

@Migration("3.31")
class StartEntityChangeTracking : CustomMigration() {
    private val entityChangeService by inject<EntityChangeService>()

    override suspend fun migrate() {
        val startedAt = entityChangeService.trackingStartedAt()
        logger.info("Entity change tracking started at $startedAt.")
    }
}
