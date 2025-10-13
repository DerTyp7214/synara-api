package dev.dertyp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*

object ApiClient {
    val instance = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
    }
}