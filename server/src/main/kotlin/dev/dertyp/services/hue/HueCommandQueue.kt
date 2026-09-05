package dev.dertyp.services.hue

import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class HueCommand(val target: HueTarget, val update: LightUpdate) {
    val grouped: Boolean get() = target.type != HueTargetType.LIGHT
    val resourceId: String get() = if (grouped) target.groupedLightId ?: target.id else target.id
    val resourceKey: String get() = if (grouped) "grouped_light:$resourceId" else "light:$resourceId"
}

class HueCommandQueue(
    private val api: HueBridgeApi,
    scope: CoroutineScope,
    private val lightInterval: Duration = 100.milliseconds,
    private val groupInterval: Duration = 1.seconds,
    private val rateLimitPenalty: Duration = 1.seconds,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSent: (HueCommand) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    private val latest = ConcurrentHashMap<String, HueCommand>()
    private val keys = Channel<String>(Channel.UNLIMITED)

    @Volatile private var lastLightSend = Long.MIN_VALUE / 2
    @Volatile private var lastGroupSend = Long.MIN_VALUE / 2
    @Volatile private var penaltyUntil = Long.MIN_VALUE / 2

    private val worker: Job = scope.launch {
        for (key in keys) {
            val command = latest.remove(key) ?: continue
            pace(command.grouped)
            try {
                if (command.grouped) api.putGroupedLight(command.resourceId, command.update)
                else api.putLight(command.resourceId, command.update)
                onSent(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HueRateLimited) {
                penaltyUntil = clock() + rateLimitPenalty.inWholeMilliseconds
                onError(e)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun submit(command: HueCommand) {
        latest[command.resourceKey] = command
        keys.trySend(command.resourceKey)
    }

    fun submitAll(commands: Iterable<HueCommand>) = commands.forEach(::submit)

    val pending: Int get() = latest.size

    private suspend fun pace(grouped: Boolean) {
        val now = clock()
        val earliest = maxOf(
            penaltyUntil,
            if (grouped) lastGroupSend + groupInterval.inWholeMilliseconds else lastLightSend + lightInterval.inWholeMilliseconds,
        )
        val wait = earliest - now
        if (wait > 0) delay(wait)
        val sentAt = clock()
        if (grouped) lastGroupSend = sentAt else lastLightSend = sentAt
    }

    fun close() {
        keys.close()
        worker.cancel()
    }
}
