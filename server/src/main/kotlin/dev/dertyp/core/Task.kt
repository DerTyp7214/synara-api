package dev.dertyp.core

import dev.dertyp.services.schedule.CronPresets
import dev.dertyp.services.schedule.ScheduledTask
import org.jetbrains.annotations.Range
import org.koin.core.component.KoinComponent
import dev.dertyp.services.schedule.CronTrigger
import dev.dertyp.services.schedule.ScheduleTrigger
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration as KDuration

typealias Task = suspend KoinComponent.() -> Unit

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