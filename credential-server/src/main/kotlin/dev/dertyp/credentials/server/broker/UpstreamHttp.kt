package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
import java.util.Base64

internal fun basicAuthHeader(clientId: String, clientSecret: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

internal suspend fun HttpClient.postForm(
    url: String,
    form: Parameters,
    basicAuth: Pair<String, String>? = null,
): HttpResponse = try {
    submitForm(url, form) {
        expectSuccess = false
        basicAuth?.let { (id, secret) -> header(HttpHeaders.Authorization, basicAuthHeader(id, secret)) }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Request to $url failed: ${e.message ?: e::class.simpleName}")
}
