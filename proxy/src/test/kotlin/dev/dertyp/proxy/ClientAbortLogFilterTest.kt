package dev.dertyp.proxy

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.spi.FilterReply
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClientAbortLogFilterTest {

    private val context = LoggerContext()
    private val filter = ClientAbortLogFilter()

    private fun event(loggerName: String, throwable: Throwable?): ILoggingEvent {
        return LoggingEvent(
            "fqcn",
            context.getLogger(loggerName),
            Level.ERROR,
            "Unhandled server error",
            throwable,
            null
        )
    }

    @Test
    fun `cancellation from the ktor application logger is denied`() {
        val reply = filter.decide(
            event("io.ktor.server.Application", CancellationException("Call context was cancelled"))
        )
        assertEquals(FilterReply.DENY, reply)
    }

    @Test
    fun `cancellation from the default application logger name is denied`() {
        assertEquals(FilterReply.DENY, filter.decide(event("Application", CancellationException("cancelled"))))
    }

    @Test
    fun `subclass of cancellation is denied`() {
        class Aborted : CancellationException("aborted")
        assertEquals(FilterReply.DENY, filter.decide(event("io.ktor.server.Application", Aborted())))
    }

    @Test
    fun `other exception from the ktor application logger passes`() {
        val reply = filter.decide(event("io.ktor.server.Application", IllegalStateException("boom")))
        assertEquals(FilterReply.NEUTRAL, reply)
    }

    @Test
    fun `event without throwable passes`() {
        assertEquals(FilterReply.NEUTRAL, filter.decide(event("io.ktor.server.Application", null)))
    }

    @Test
    fun `cancellation from another logger passes`() {
        val reply = filter.decide(event("dev.dertyp.proxy.Other", CancellationException("cancelled")))
        assertEquals(FilterReply.NEUTRAL, reply)
    }
}
