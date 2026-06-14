package dev.dertyp

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

object TestRedis {
    val redisContainer: GenericContainer<*>? by lazy {
        try {
            GenericContainer(DockerImageName.parse("redis/redis-stack-server:latest")).apply {
                withNetworkMode("host")
                withEnv("REDIS_ARGS", "--cluster-enabled yes --cluster-config-file /tmp/nodes.conf --cluster-node-timeout 5000 --appendonly yes --protected-mode no")
                start()
                execInContainer("sh", "-c", "redis-cli -p 6379 cluster addslots $(seq 0 16383)")
                Thread.sleep(5000)
            }
        } catch (e: Exception) {
            println("WARNING: Could not start Redis Stack testcontainer. Reason: ${e.message}")
            null
        }
    }

    val host: String
        get() = "localhost"

    val port: Int
        get() = 6379
}
