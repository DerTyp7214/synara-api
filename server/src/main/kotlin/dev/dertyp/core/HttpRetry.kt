package dev.dertyp.core

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class RetryOnError { RETRY, GIVE_UP, THROW }

class RetryPolicy(
    val maxAttempts: Int,
    val isSuccess: (HttpStatusCode) -> Boolean = { it.isSuccess() },
    val retryOn: (HttpStatusCode) -> Boolean = { false },
    val backoff: (status: HttpStatusCode?, attempt: Int) -> Duration = { _, _ -> Duration.ZERO },
    val honorRetryAfter: Boolean = false,
    val maxDelay: Duration = Duration.INFINITE,
    val onError: RetryOnError = RetryOnError.GIVE_UP,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun delayFor(response: HttpResponse?, attempt: Int): Duration {
        val retryAfter = if (honorRetryAfter) response?.retryAfter() else null
        return (retryAfter ?: backoff(response?.status, attempt)).coerceAtMost(maxDelay)
    }
}

fun HttpResponse.retryAfter(): Duration? =
    headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds

private sealed interface AttemptOutcome<out T> {
    class Done<T>(val value: T) : AttemptOutcome<T>
    class Rejected(val response: HttpResponse) : AttemptOutcome<Nothing>
    class Retry(val wait: Duration) : AttemptOutcome<Nothing>
    data object Failed : AttemptOutcome<Nothing>
}

suspend fun <T> retryingGet(
    policy: RetryPolicy,
    label: String,
    logger: Logger,
    request: suspend () -> HttpResponse,
    onGiveUp: suspend (response: HttpResponse, retries: Int) -> Unit = { _, _ -> },
    parse: suspend (HttpResponse) -> T,
): T? {
    var attempt = 0
    while (true) {
        val last = attempt + 1 >= policy.maxAttempts
        val outcome: AttemptOutcome<T> = try {
            val response = request()
            val status = response.status
            when {
                policy.isSuccess(status) -> AttemptOutcome.Done(parse(response))
                !policy.retryOn(status) || last -> AttemptOutcome.Rejected(response)
                else -> {
                    val wait = policy.delayFor(response, attempt)
                    logger.warn("$label: $status, retrying in $wait (${attempt + 1}/${policy.maxAttempts - 1})")
                    AttemptOutcome.Retry(wait)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                policy.onError == RetryOnError.THROW -> throw e
                policy.onError == RetryOnError.GIVE_UP || last -> {
                    logger.error("$label failed after ${attempt + 1} attempt(s): ${e.message}", e)
                    AttemptOutcome.Failed
                }
                else -> {
                    val wait = policy.delayFor(null, attempt)
                    logger.warn("$label failed: ${e.message}, retrying in $wait (${attempt + 1}/${policy.maxAttempts - 1})")
                    AttemptOutcome.Retry(wait)
                }
            }
        }
        when (outcome) {
            is AttemptOutcome.Done -> return outcome.value
            is AttemptOutcome.Rejected -> {
                onGiveUp(outcome.response, attempt)
                return null
            }
            AttemptOutcome.Failed -> return null
            is AttemptOutcome.Retry -> {
                delay(outcome.wait)
                attempt++
            }
        }
    }
}
