package dev.dertyp.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KeyedMutex<K : Any> {
    private class Entry {
        val mutex = Mutex()
        var holders = 0
    }

    private val entries = HashMap<K, Entry>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key, ::Entry).also { it.holders++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                entry.holders--
                if (entry.holders == 0) entries.remove(key)
            }
        }
    }
}
