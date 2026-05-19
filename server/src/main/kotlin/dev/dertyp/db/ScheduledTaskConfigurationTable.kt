package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.IdTable

object ScheduledTaskConfigurationTable : IdTable<String>("scheduled_task_configuration") {
    override val id = text("key").entityId()
    override val primaryKey = PrimaryKey(id)

    val name = text("name")
    val enabled = bool("enabled")
    val trigger = text("trigger")
}

