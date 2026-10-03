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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface HueCommand {
    val grouped: Boolean
    val resourceKey: String
}

data class HueLightCommand(val target: HueTarget, val update: LightUpdate) : HueCommand {
    override val grouped: Boolean get() = target.type != HueTargetType.LIGHT
    val resourceId: String get() = if (grouped) target.groupedLightId ?: target.id else target.id
    override val resourceKey: String get() = if (grouped) "grouped_light:$resourceId" else "light:$resourceId"
}

data class HueSceneCommand(val sceneId: String, val update: SceneRecallUpdate) : HueCommand {
    override val grouped: Boolean get() = true
    override val resourceKey: String get() = "scene:$sceneId"
}

class HueCommandQueue(
    private val api: HueBridgeApi,
    scope: CoroutineScope,
    private val onSent: (HueCommand) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val latest = ConcurrentHashMap<String, HueCommand>()
    private val keys = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var lastLightSend = Long.MIN_VALUE / 2
    @Volatile
    private var lastGroupSend = Long.MIN_VALUE / 2
    @Volatile
    private var penaltyUntil = Long.MIN_VALUE / 2

    private val worker: Job = scope.launch {
        for (key in keys) {
            val command = latest.remove(key) ?: continue
            pace(command.grouped)
            try {
                when (command) {
                    is HueLightCommand ->
                        if (command.grouped) api.putGroupedLight(command.resourceId, command.update)
                        else api.putLight(command.resourceId, command.update)

                    is HueSceneCommand -> api.recallScene(command.sceneId, command.update)
                }
                onSent(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HueRateLimited) {
                penaltyUntil = now() + RATE_LIMIT_PENALTY.inWholeMilliseconds
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
        val earliest = maxOf(
            penaltyUntil,
            if (grouped) lastGroupSend + GROUP_INTERVAL.inWholeMilliseconds else lastLightSend + LIGHT_INTERVAL.inWholeMilliseconds,
        )
        val wait = earliest - now()
        if (wait > 0) delay(wait)
        val sentAt = now()
        if (grouped) lastGroupSend = sentAt else lastLightSend = sentAt
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun close() {
        keys.close()
        worker.cancel()
    }

    companion object {
        private val LIGHT_INTERVAL = 100.milliseconds
        private val GROUP_INTERVAL = 1.seconds
        private val RATE_LIMIT_PENALTY = 1.seconds
    }
}
