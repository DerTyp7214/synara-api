package dev.dertyp.core

import dev.dertyp.data.TaskStatus
import dev.dertyp.services.ScheduledTaskLogService
import dev.dertyp.services.schedule.CronPresets
import dev.dertyp.services.schedule.CronTrigger
import dev.dertyp.services.schedule.ScheduleTrigger
import dev.dertyp.services.schedule.ScheduledTask
import org.jetbrains.annotations.Range
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration as KDuration

typealias Task = suspend KoinComponent.() -> Unit

suspend fun KoinComponent.logTask(name: String, block: suspend () -> Map<String, Any?>) {
    val logService by inject<ScheduledTaskLogService>()
    val startTime = Instant.now().toEpochMilli()
    try {
        val details = block()
        logService.logTask(
            name,
            startTime,
            Instant.now().toEpochMilli(),
            TaskStatus.SUCCESS,
            details = details.mapValues { it.value.toString() }
        )
    } catch (e: Throwable) {
        logService.logTask(
            name,
            startTime,
            Instant.now().toEpochMilli(),
            TaskStatus.FAILURE,
            message = e.message
        )
        throw e
    }
}

infix fun Task.scheduleIn(duration: Duration) = ScheduledTask(
    trigger = ScheduleTrigger(scheduledTime = Instant.now() + duration),
    task = this
)

infix fun Task.scheduleIn(duration: KDuration) = ScheduledTask(
    trigger = ScheduleTrigger(scheduledTime = Instant.now() + duration),
    task = this
)

infix fun Task.schedule(cronExpression: String) = ScheduledTask(
    trigger = CronTrigger(cronExpression, scheduledTime = CronTrigger(cronExpression).nextExecution()),
    task = this
)

fun Task.dailyAt(
    hour: @Range(from = 0, to = 23) Int,
    minute: @Range(from = 0, to = 59) Int = 0
) = ScheduledTask(
    trigger = CronPresets.dailyAt(hour, minute),
    task = this
)

fun Task.weeklyOn(
    dayOfWeek: @Range(from = 1, to = 7) Int,
    hour: @Range(from = 0, to = 23) Int = 0,
    minute: @Range(from = 0, to = 59) Int = 0
) = ScheduledTask(
    trigger = CronPresets.weeklyOn(dayOfWeek, hour, minute),
    task = this
)

fun Task.hourlyAt(
    minute: @Range(from = 0, to = 59) Int = 0
) = ScheduledTask(
    trigger = CronPresets.hourlyAt(minute),
    task = this
)