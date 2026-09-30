package dev.dertyp.core

import dev.dertyp.data.Change
import dev.dertyp.data.ChangeTopic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ChangeNotifier {
    private val channels = PerUserChannels<UUID, ChangeTopic> {
        MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    fun notify(userId: UUID, topic: ChangeTopic) {
        channels.tryEmit(userId, topic)
    }

    fun observe(userId: UUID): Flow<Change> = channelFlow {
        val pending = ConcurrentHashMap.newKeySet<ChangeTopic>()
        channels.observe(userId).collect { topic ->
            if (pending.add(topic)) {
                launch {
                    delay(COALESCE_WINDOW)
                    pending.remove(topic)
                    send(Change(topic))
                }
            }
        }
    }

    companion object {
        val COALESCE_WINDOW: Duration = 100.milliseconds
    }
}
