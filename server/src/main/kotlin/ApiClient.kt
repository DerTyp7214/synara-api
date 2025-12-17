package dev.dertyp

import dev.dertyp.core.ApplicationScope
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.serialization.kotlinx.protobuf.*
import kotlinx.serialization.ExperimentalSerializationApi

object ApiClient {
    @OptIn(ExperimentalSerializationApi::class)
    val instance = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(ApplicationScope.json)
            protobuf()
        }
    }
}