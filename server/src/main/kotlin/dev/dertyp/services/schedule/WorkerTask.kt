package dev.dertyp.services.schedule

import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TriggerDefinition

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorkerTask(
    val key: String,
    val name: String,
    val enabled: Boolean = true,
    val cron: String = "",
    val intervalSeconds: Long = 0,
    val afterTask: String = "",
)

fun WorkerTask.defaultTrigger(): TriggerDefinition {
    val triggers = listOfNotNull(
        cron.takeIf { it.isNotEmpty() }?.let { TriggerDefinition.Cron(it) },
        intervalSeconds.takeIf { it > 0 }?.let { TriggerDefinition.Interval(it) },
        afterTask.takeIf { it.isNotEmpty() }?.let { TriggerDefinition.AfterTask(it) },
    )
    require(triggers.size <= 1) { "Worker task $key declares more than one default trigger" }
    return triggers.firstOrNull() ?: TriggerDefinition.Manual
}

fun WorkerTask.defaultConfiguration(): TaskConfiguration = TaskConfiguration(key, name, enabled, defaultTrigger())
