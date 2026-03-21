package dev.dertyp.db

import dev.dertyp.data.TaskStatus
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object ScheduledTaskLogTable : UUIDTable("scheduled_task_log") {
    val taskName = text("taskName")
    val startTime = long("startTime")
    val endTime = long("endTime")
    val status = enumerationByName("status", 20, TaskStatus::class)
    val message = text("message").nullable()
    val details = binary("details", 65535).nullable()
    val logTime = long("logTime").clientDefault { Instant.now().toEpochMilli() }
}
