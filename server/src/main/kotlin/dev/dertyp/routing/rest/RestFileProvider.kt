package dev.dertyp.routing.rest

import dev.dertyp.StreamInfo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent

interface RestFileProvider {
    suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo?
}

class NoOutputWithContentLength(
    override val contentType: ContentType,
    override val status: HttpStatusCode? = null,
    override val contentLength: Long? = null,
) : OutgoingContent.NoContent()
