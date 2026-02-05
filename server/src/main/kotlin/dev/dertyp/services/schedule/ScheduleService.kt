package dev.dertyp.services.schedule

import dev.dertyp.core.Task
import dev.dertyp.core.plus
import dev.dertyp.services.Service
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.withTimeoutOrNull
import org.jetbrains.annotations.Range
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

object CronPresets {
    fun dailyAt(
        hour: @Range(from = 0, to = 23) Int,
        minute: @Range(from = 0, to = 59) Int = 0
    ): CronTrigger {
        return create("$minute $hour * * *")
    }

    fun weeklyOn(
        dayOfWeek: @Range(from = 1, to = 7) Int,
        hour: @Range(from = 0, to = 23) Int = 0,
        minute: @Range(from = 0, to = 59) Int = 0
    ): CronTrigger {
        require(dayOfWeek in 1..7)
        return create("$minute $hour * * $dayOfWeek")
    }

    fun hourlyAt(minute: @Range(from = 0, to = 59) Int = 0): CronTrigger {
        return create("$minute * * * *")
    }

    private fun create(expression: String): CronTrigger {
        val base = CronTrigger(expression)
        return base.copy(scheduledTime = base.nextExecution(Instant.now()))
    }
}

data class ScheduledTask(
    val id: UUID = UUID.randomUUID(),
    val trigger: Trigger,
    val task: Task
) : Comparable<ScheduledTask> {
    override fun compareTo(other: ScheduledTask): Int = trigger.scheduledTime.compareTo(other.trigger.scheduledTime)
}

@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class ScheduleService : Service() {
    private val stopped: AtomicBoolean = AtomicBoolean(true)
    private val schedules: PriorityBlockingQueue<ScheduledTask> = PriorityBlockingQueue()
    private val eventRegistry = mutableMapOf<String, MutableSet<CustomTrigger>>()

    private val queueUpdateNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(FlowPreview::class)
    override suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        logger.info("Starting service")

        coroutineScope {
            launch {
                while (!stopped.load()) {
                    val now = Instant.now()
                    val next = schedules.peek()

                    if (next == null) {
                        queueUpdateNotifier.first()
                        continue
                    }

                    val waitTime = Duration.between(now, next.trigger.scheduledTime)

                    if (waitTime <= Duration.ZERO) {
                        val scheduledTask = schedules.poll() ?: continue
                        logger.info("Executing task: ${scheduledTask.id}")
                        launch {
                            try {
                                scheduledTask.task(this@ScheduleService)
                            } catch (e: Exception) {
                                logger.error("Error executing scheduled task", e)

                                if (!scheduledTask.trigger.doesRepeat()) {
                                    logger.info("Rescheduling failed task: ${scheduledTask.id}")
                                    val nextTrigger = ScheduleTrigger(
                                        scheduledTime = Instant.now() + 10.minutes
                                    )
                                    schedule(scheduledTask.copy(trigger = nextTrigger))
                                }
                            }
                        }

                        if (scheduledTask.trigger.doesRepeat()) {
                            logger.info("Rescheduling repeating task: ${scheduledTask.id}")
                            val updateTrigger = scheduledTask.trigger.updateForNextRun()
                            schedule(scheduledTask.copy(trigger = updateTrigger))
                        }
                    } else {
                        withTimeoutOrNull(waitTime) {
                            queueUpdateNotifier.first()
                        }
                    }
                }
            }
        }

        logger.info("Stopping service")
        stopped.store(true)
    }

    override suspend fun stopService() {
        logger.info("Stopping service requested")
        stopped.store(true)
        queueUpdateNotifier.tryEmit(Unit)
    }

    fun schedule(task: ScheduledTask) {
        logger.info("Scheduling task: ${task.id} with trigger: ${task.trigger}")
        schedules.add(task)
        queueUpdateNotifier.tryEmit(Unit)
    }

    fun scheduleTask(trigger: ScheduleTrigger, task: Task): ScheduledTask {
        val scheduledTask = ScheduledTask(
            trigger = trigger,
            task = task
        )
        schedule(scheduledTask)
        return scheduledTask
    }

    fun fireEvent(id: UUID) {
        logger.info("Firing event for task: $id")
        val scheduledTask = schedules.find { it.id == id } ?: return
        val trigger = scheduledTask.trigger

        if (trigger is EventTrigger) {
            schedules.remove(scheduledTask)
            schedule(scheduledTask.copy(trigger = trigger.fire()))
        }
    }

    fun unscheduleTask(id: UUID) {
        logger.info("Unscheduling task: $id")
        val task = schedules.find { it.id == id }
        if (task != null) {
            schedules.remove(task)
            eventRegistry.values.forEach { it.remove(task.trigger) }
            queueUpdateNotifier.tryEmit(Unit)
        } else {
            logger.warn("Task with id $id not found for unscheduling")
        }
    }

    fun getScheduledTasks() = schedules.sorted()

    fun register(key: String, task: Task): ScheduledTask {
        logger.info("Registering task for key: $key")
        val trigger = CustomTrigger(true)

        eventRegistry.getOrPut(key) { mutableSetOf() }.add(trigger)

        val scheduledTask = ScheduledTask(trigger = trigger, task = task)
        schedule(scheduledTask)
        return scheduledTask
    }

    fun signal(key: String) {
        logger.info("Signaling key: $key")
        val triggers = eventRegistry[key] ?: return

        schedules
            .filter { it.trigger in triggers }.toList()
            .forEach { task ->
                schedules.remove(task)
                (task.trigger as CustomTrigger).signal()
                schedule(task)
            }

        queueUpdateNotifier.tryEmit(Unit)
    }
}
