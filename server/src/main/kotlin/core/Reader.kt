package dev.dertyp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.Reader

fun Reader.lineFlow(): Flow<String> = flow {
    val reader = this@lineFlow.let {
        it as? BufferedReader ?: BufferedReader(it)
    }

    reader.use { reader ->
        while (true) {
            val line = reader.readLine() ?: break

            currentCoroutineContext().ensureActive()

            emit(line)
        }
    }
}.flowOn(Dispatchers.IO)