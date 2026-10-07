package dev.dertyp

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.testcontainers.containers.GenericContainer
import java.util.concurrent.ConcurrentHashMap

object TestContainers : AutoCloseable {
    const val OPT_OUT_PROPERTY = "withoutContainers"
    const val REPORT_PREFIX = "TEST CONTAINERS: "

    private val services = listOf("PostgreSQL", "Redis")
    private val optedOut = System.getProperty(OPT_OUT_PROPERTY) == "true"
    private val used = ConcurrentHashMap<String, String>()

    fun assumeEnabled() {
        assumeFalse(optedOut, "PostgreSQL and Redis test containers disabled (-P$OPT_OUT_PROPERTY=true)")
    }

    fun <T : GenericContainer<*>> start(
        service: String,
        image: String,
        version: (T) -> String,
        create: () -> T,
    ): Result<T> {
        check(service in services) { "Unknown test container service $service" }
        return runCatching { create().let { it to version(it) } }.fold(
            onSuccess = { (container, serverVersion) ->
                used[service] = "$service $serverVersion (container $image, port ${container.firstMappedPort})"
                Result.success(container)
            },
            onFailure = { cause ->
                used[service] = "$service FAILED (container $image did not start)"
                Result.failure(
                    IllegalStateException(
                        "Could not start the $service test container ($image): ${cause.message}. " +
                            "The tests need a reachable Docker daemon. To skip the PostgreSQL and Redis tests, " +
                            "pass -P$OPT_OUT_PROPERTY=true.",
                        cause,
                    )
                )
            },
        )
    }

    override fun close() {
        val jvm = "test JVM ${ProcessHandle.current().pid()}"
        if (optedOut) {
            println(REPORT_PREFIX + "PostgreSQL and Redis tests skipped (-P$OPT_OUT_PROPERTY=true), $jvm")
            return
        }
        println(REPORT_PREFIX + services.joinToString { used[it] ?: "$it not used" } + ", $jvm")
    }
}
