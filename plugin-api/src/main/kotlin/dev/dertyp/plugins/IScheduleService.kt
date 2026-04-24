package dev.dertyp.plugins

import java.util.UUID

interface IScheduleService {
    fun scheduleTask(trigger: ScheduleTrigger, name: String? = null, task: Task): Any
    fun triggerTask(id: UUID): Boolean
    fun schedulePostIndexTasks()
}
