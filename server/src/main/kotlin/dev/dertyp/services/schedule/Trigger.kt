package dev.dertyp.services.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.ExperimentalTime

private val cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)
private val parser = CronParser(cronDefinition)
private val zoneId = ZoneId.systemDefault()

sealed class Trigger() {
    abstract val scheduledTime: Instant
    abstract fun nextExecution(from: Instant = Instant.now()): Instant
    abstract fun doesRepeat(): Boolean
    abstract fun updateForNextRun(time: Instant = scheduledTime): Trigger
}

data class CronTrigger(
    val expression: String,
    override val scheduledTime: Instant = Instant.now(),
) : Trigger() {
    private val cron = parser.parse(expression)
    private val executionTime = ExecutionTime.forCron(cron)

    override fun nextExecution(from: Instant): Instant {
        val zdt = from.atZone(zoneId)
        return executionTime.nextExecution(zdt)
            .map { it.toInstant() }
            .orElse(Instant.MIN)
    }

    override fun doesRepeat(): Boolean {
        val now = Instant.now()
        return nextExecution(now) >= now
    }

    override fun updateForNextRun(time: Instant) = copy(scheduledTime = nextExecution(time))
}

@OptIn(ExperimentalTime::class)
data class ScheduleTrigger(
    override val scheduledTime: Instant,
    val repeat: Duration = Duration.ZERO,
) : Trigger() {
    override fun nextExecution(from: Instant): Instant {
        return scheduledTime + repeat
    }

    override fun doesRepeat(): Boolean {
        return repeat > Duration.ZERO
    }

    override fun updateForNextRun(time: Instant) = copy(scheduledTime = nextExecution(time))
}

data class EventTrigger(
    override val scheduledTime: Instant = Instant.MAX,
    private val autoRepeat: Boolean = false
) : Trigger() {
    override fun nextExecution(from: Instant) = scheduledTime
    override fun doesRepeat(): Boolean = autoRepeat
    override fun updateForNextRun(time: Instant) = if (autoRepeat) copy(scheduledTime = nextExecution(time)) else this

    fun fire(): EventTrigger = copy(scheduledTime = Instant.now())
}


data class CustomTrigger(
    private val autoRepeat: Boolean = true,
) : Trigger() {
    private var activatedTime: Instant = Instant.MAX

    override val scheduledTime: Instant
        get() = activatedTime

    override fun nextExecution(from: Instant) = activatedTime
    override fun doesRepeat(): Boolean = autoRepeat
    override fun updateForNextRun(time: Instant) = CustomTrigger(autoRepeat)

    fun signal() {
        activatedTime = Instant.now()
    }
}