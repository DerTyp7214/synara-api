package dev.dertyp.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.Reader

fun Reader.lineFlow(): Flow<String> = flow {
    val reader = this@lineFlow.let {
        it as? BufferedReader ?: BufferedReader(it)
    }

    try {
        while (true) {
            val line = reader.readLine()
            if (line == null) break

            currentCoroutineContext().ensureActive()

            emit(line)
        }
    } finally {
        reader.close()
    }
}