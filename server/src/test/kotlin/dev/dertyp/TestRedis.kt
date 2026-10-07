package dev.dertyp

import org.rnorth.ducttape.ratelimits.RateLimiterBuilder
import org.rnorth.ducttape.unreliables.Unreliables
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object TestRedis {
    private const val IMAGE = "redis/redis-stack-server:7.4.0-v8"
    private const val CONTAINER_PORT = 6379
    private const val LAST_SLOT = 16383
    private const val READY_WITHIN_SECONDS = 60
    private const val READY_CHECKS_PER_SECOND = 10
    private const val CLUSTER_READY = "cluster_state:ok"

    private val redisStart by lazy {
        TestContainers.start(
            service = "Redis",
            image = IMAGE,
            version = ::serverVersion,
        ) {
            GenericContainer(DockerImageName.parse(IMAGE)).apply {
                withExposedPorts(CONTAINER_PORT)
                withEnv(
                    "REDIS_ARGS",
                    "--cluster-enabled yes --cluster-config-file /tmp/nodes.conf --cluster-node-timeout 5000 --appendonly yes --protected-mode no"
                )
                start()
                redisCli(this, "config", "set", "cluster-announce-ip", addressOf(this))
                redisCli(this, "config", "set", "cluster-announce-port", getMappedPort(CONTAINER_PORT).toString())
                redisCli(this, "cluster", "addslotsrange", "0", LAST_SLOT.toString())
                val checks = RateLimiterBuilder.newBuilder()
                    .withRate(READY_CHECKS_PER_SECOND, TimeUnit.SECONDS)
                    .withConstantThroughput()
                    .build()
                Unreliables.retryUntilTrue(READY_WITHIN_SECONDS, TimeUnit.SECONDS) {
                    checks.getWhenReady { CLUSTER_READY in redisCli(this, "cluster", "info") }
                }
            }
        }
    }

    val redisContainer: GenericContainer<*>
        get() {
            TestContainers.assumeEnabled()
            return redisStart.getOrThrow()
        }

    val host: String
        get() = addressOf(redisContainer)

    val port: Int
        get() = redisContainer.getMappedPort(CONTAINER_PORT)

    private fun addressOf(container: GenericContainer<*>): String = InetAddress.getByName(container.host).hostAddress

    private fun redisCli(container: GenericContainer<*>, vararg command: String): String {
        val result = container.execInContainer("redis-cli", "-e", "-p", CONTAINER_PORT.toString(), *command)
        check(result.exitCode == 0) { "redis-cli ${command.joinToString(" ")} failed: ${result.stdout}${result.stderr}" }
        return result.stdout
    }

    private fun serverVersion(container: GenericContainer<*>): String =
        redisCli(container, "info", "server").lineSequence()
            .first { it.startsWith("redis_version:") }
            .substringAfter(':')
            .trim()
}
