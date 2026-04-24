package dev.dertyp

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi

object ApiClient {
    @OptIn(ExperimentalSerializationApi::class)
    val instance = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(ApplicationScope.json)
            protobuf()
        }
        install(HttpTimeout)
        install(ContentEncoding) {
            gzip()
        }
    }

    val queueInstance = HttpClientQueueService()
}