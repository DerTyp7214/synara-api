package dev.dertyp.core

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*

suspend inline fun <reified T> HttpClient.safeGet(url: String) = try {
    get(url).body<T>()
} catch (_: Throwable) {
    null
}