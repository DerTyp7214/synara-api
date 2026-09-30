package dev.dertyp.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class PerUserChannels<K : Any, T>(private val factory: () -> MutableSharedFlow<T>) {
    private class Entry<T>(val flow: MutableSharedFlow<T>) {
        var refs = 0
    }

    private val locks = KeyedMutex<K>()
    private val entries = HashMap<K, Entry<T>>()

    suspend fun <R> withLock(key: K, block: suspend () -> R): R = locks.withLock(key, block)

    fun observe(key: K): Flow<T> = flow {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry(factory()) }.also { it.refs++ }
        }
        try {
            emitAll(entry.flow)
        } finally {
            release(key, entry)
        }
    }

    fun tryEmit(key: K, value: T): Boolean {
        val entry = retain(key) ?: return false
        try {
            return entry.flow.tryEmit(value)
        } finally {
            release(key, entry)
        }
    }

    suspend fun emit(key: K, value: T) {
        val entry = retain(key) ?: return
        try {
            entry.flow.emit(value)
        } finally {
            release(key, entry)
        }
    }

    private fun retain(key: K): Entry<T>? = synchronized(entries) {
        entries[key]?.also { it.refs++ }
    }

    private fun release(key: K, entry: Entry<T>) {
        synchronized(entries) {
            entry.refs--
            if (entry.refs == 0) entries.remove(key)
        }
    }
}
