package dev.dertyp

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.HttpClientQueueService
import io.ktor.client.HttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

object ApiClient : KoinComponent {
    val instance: HttpClient get() = get<HttpClientFactory>().api

    val queueInstance: HttpClientQueueService get() = get()
}
