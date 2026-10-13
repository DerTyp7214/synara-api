package dev.dertyp.proxy

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import java.util.concurrent.CancellationException

class ClientAbortLogFilter : Filter<ILoggingEvent>() {
    override fun decide(event: ILoggingEvent): FilterReply {
        if (event.loggerName !in KTOR_APPLICATION_LOGGERS) return FilterReply.NEUTRAL
        val throwable = (event.throwableProxy as? ThrowableProxy)?.throwable
        return if (throwable is CancellationException) FilterReply.DENY else FilterReply.NEUTRAL
    }

    companion object {
        val KTOR_APPLICATION_LOGGERS = setOf("Application", "io.ktor.server.Application")
    }
}
