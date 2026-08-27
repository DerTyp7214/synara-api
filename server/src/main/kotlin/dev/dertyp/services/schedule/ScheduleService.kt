package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.plus
import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TriggerDefinition
import dev.dertyp.plugins.*
import dev.dertyp.services.Service
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.time.withTimeoutOrNull
import org.jetbrains.annotations.Range
import org.koin.core.component.get
import java.time.Duration
import java.time.Instant
import java.util.UUID
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

    fun create(expression: String): CronTrigger {
        val base = CronTrigger(expression)
        return base.copy(scheduledTime = base.nextExecution(Instant.now()))
    }
}

data class ScheduledTask(
    val id: UUID = UUID.randomUUID(),
    val key: String? = null,
    val name: String? = null,
    val trigger: Trigger,
    val task: Task
) : Comparable<ScheduledTask> {
    override fun compareTo(other: ScheduledTask): Int = trigger.scheduledTime.compareTo(other.trigger.scheduledTime)
}

@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class ScheduleService : IScheduleService, Service() {
    private val stopped: AtomicBoolean = AtomicBoolean(true)
    private val schedules: PriorityBlockingQueue<ScheduledTask> = PriorityBlockingQueue()
    private val eventRegistry = mutableMapOf<String, MutableSet<CustomTrigger>>()
    
    private val managedTasks = mutableMapOf<String, ManagedTask>()

    private val queueUpdateNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    
    data class ManagedTask(
        val key: String,
        val name: String,
        val task: Task
    )

    override fun registerManagedTask(key: String, name: String, task: Task) {
        logger.info("Registering managed task: $name ($key)")
        managedTasks[key] = ManagedTask(key, name, task)
    }

    private fun updateFromConfig(configurations: List<TaskConfiguration>) {
        val configsByKey = configurations.associateBy { it.key }

        schedules.filter { it.key != null }.toList().forEach { task ->
            val config = configsByKey[task.key!!]
            val currentTrigger = task.trigger
            if (config == null || !config.enabled || !isSameTrigger(config.trigger, currentTrigger)) {
                unscheduleTask(task.id)
            }
        }

        managedTasks.forEach { (key, managedTask) ->
            val config = configsByKey[key] ?: return@forEach
            if (!config.enabled) return@forEach

            val isScheduled = schedules.any { it.key == key }
            if (!isScheduled) {
                schedule(
                    ScheduledTask(
                        key = key,
                        name = config.name,
                        trigger = toTrigger(config.trigger),
                        task = managedTask.task
                    )
                )
            }
        }
    }

    private fun isSameTrigger(definition: TriggerDefinition, trigger: Trigger): Boolean {
        return when (definition) {
            is TriggerDefinition.Cron -> trigger is CronTrigger && trigger.expression == definition.expression
            is TriggerDefinition.Interval -> trigger is ScheduleTrigger && trigger.repeat.seconds == definition.intervalSeconds
            is TriggerDefinition.AfterTask -> trigger is TaskCompletionTrigger && trigger.dependencyKey == definition.dependencyKey
            is TriggerDefinition.Manual -> trigger is ScheduleTrigger && trigger.scheduledTime == Instant.MAX
        }
    }

    private fun toTrigger(definition: TriggerDefinition): Trigger {
        return when (definition) {
            is TriggerDefinition.Cron -> CronPresets.create(definition.expression)
            is TriggerDefinition.Interval -> ScheduleTrigger(
                Instant.now() + Duration.ofSeconds(definition.intervalSeconds),
                repeat = Duration.ofSeconds(definition.intervalSeconds)
            )

            is TriggerDefinition.AfterTask -> TaskCompletionTrigger(dependencyKey = definition.dependencyKey)
            is TriggerDefinition.Manual -> ScheduleTrigger(Instant.MAX)
        }
    }

    @OptIn(FlowPreview::class)
    override suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        logger.info("Starting service")

        val configService = get<ScheduledTaskConfigurationService>()
        configService.configurationsFlow.onEach {
            updateFromConfig(it)
        }.launchIn(ApplicationScope.scope)

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
                        val taskName = if (scheduledTask.name != null) "${scheduledTask.name} (${scheduledTask.id})" else "${scheduledTask.id}"
                        logger.info("Executing task: $taskName")
                        launch {
                            try {
                                scheduledTask.task()
                                notifyTaskCompletion(scheduledTask.id, scheduledTask.key)
                            } catch (e: Exception) {
                                logger.error("Error executing scheduled task", e)

                                if (!scheduledTask.trigger.doesRepeat()) {
                                    logger.info("Rescheduling failed task: $taskName")
                                    val nextTrigger = ScheduleTrigger(
                                        scheduledTime = Instant.now() + 10.minutes
                                    )
                                    schedule(scheduledTask.copy(trigger = nextTrigger))
                                }
                            }
                        }

                        if (scheduledTask.trigger.doesRepeat()) {
                            logger.info("Rescheduling repeating task: $taskName")
                            val updateTrigger = scheduledTask.trigger.updateForNextRun()
                            schedule(scheduledTask.copy(trigger = updateTrigger))
                        }
                    } else {
                        logger.info("Next task in $waitTime")
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

    fun schedule(task: ScheduledTask): ScheduledTask {
        val taskName = if (task.name != null) "${task.name} (${task.id})" else "${task.id}"
        logger.info("Scheduling task: $taskName with trigger: ${task.trigger}")
        schedules.add(task)
        queueUpdateNotifier.tryEmit(Unit)
        return task
    }

    override fun scheduleTask(trigger: ScheduleTrigger, name: String?, task: Task): ScheduledTask {
        val scheduledTask = ScheduledTask(
            trigger = trigger,
            name = name,
            task = task
        )
        schedule(scheduledTask)
        return scheduledTask
    }

    override fun schedulePostIndexTasks() {
        val musicBrainzWorker = get<MusicBrainzWorker>()
        val imageAnalysisWorker = get<ImageAnalysisWorker>()
        val audioStartAnalysisWorker = get<AudioStartAnalysisWorker>()
        scheduleTask(
            trigger = ScheduleTrigger(Instant.now()),
            name = "MusicBrainzWorker-AfterIndex",
            task = { musicBrainzWorker.run { _, _ -> } }
        )
        scheduleTask(
            trigger = ScheduleTrigger(Instant.now()),
            name = "ImageAnalysisWorker-AfterIndex",
            task = { imageAnalysisWorker.run { _, _ -> } }
        )
        scheduleTask(
            trigger = ScheduleTrigger(Instant.now()),
            name = "AudioStartAnalysisWorker-AfterIndex",
            task = { audioStartAnalysisWorker.run { _, _ -> } }
        )
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

    override fun triggerTask(id: UUID): Boolean {
        val scheduledTask = schedules.find { it.id == id } ?: return false
        val taskName = if (scheduledTask.name != null) "${scheduledTask.name} (${scheduledTask.id})" else "${scheduledTask.id}"
        logger.info("Manually triggering task: $taskName")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scheduledTask.task()
                notifyTaskCompletion(scheduledTask.id, scheduledTask.key)
            } catch (e: Exception) {
                logger.error("Error executing manually triggered task: $taskName", e)
            }
        }
        return true
    }

    override fun triggerTask(key: String): Boolean {
        val managedTask = managedTasks[key] ?: return false
        val taskName = managedTask.name
        logger.info("Manually triggering managed task: $taskName")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                managedTask.task()
                notifyTaskCompletion(UUID.randomUUID(), key)
            } catch (e: Exception) {
                logger.error("Error executing manually triggered task: $taskName", e)
            }
        }
        return true
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
    fun getManagedTasks() = managedTasks.toMap()

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

    private fun notifyTaskCompletion(completedTaskId: UUID, completedTaskKey: String?) {
        val dependentTasks = schedules.filter {
            val trigger = it.trigger
            trigger is TaskCompletionTrigger && (trigger.dependencyId == completedTaskId || (completedTaskKey != null && trigger.dependencyKey == completedTaskKey))
        }

        dependentTasks.forEach { task ->
            schedules.remove(task)
            (task.trigger as TaskCompletionTrigger).activate()
            schedule(task)
        }
    }
}
