package dev.dertyp.plugins

import com.google.gson.Gson
import com.ucasoft.ktor.simpleCache.SimpleCacheConfig
import com.ucasoft.ktor.simpleCache.SimpleCacheProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClusterClient
import redis.clients.jedis.params.SetParams
import kotlin.time.Duration

class RedisCacheProvider(config: Config) : SimpleCacheProvider(config) {
    private val jedis: RedisClusterClient = RedisClusterClient.create(HostAndPort(config.host, config.port))

    override suspend fun getCache(key: String): Any? =
        if (jedis.exists(key)) try {
            RedisCacheObject.fromCache(jedis[key])
        } catch (_: Exception) {
            jedis.del(key)
            null
        } else null

    override suspend fun setCache(key: String, content: Any, invalidateAt: Duration?) {
        if (invalidateAt == Duration.ZERO) return
        if (invalidateAt != null && !invalidateAt.isInfinite())
            jedis.set(
                key,
                RedisCacheObject.fromObject(content).toString(),
                SetParams().px(invalidateAt.inWholeMilliseconds)
            )
        else jedis.set(key, RedisCacheObject.fromObject(content).toString())
    }

    class Config internal constructor() : SimpleCacheProvider.Config() {

        var host = "localhost"

        var port = 6379

        var ssl = false
    }
}

class RedisCacheObject(val type: String, val content: String) : KoinComponent {
    override fun toString() = "${type.length}:$type$content"

    companion object : KoinComponent {
        private val gson by inject<Gson>()

        fun fromObject(`object`: Any) = RedisCacheObject(`object`::class.java.name, gson.toJson(`object`))

        @Suppress("UNCHECKED_CAST")
        fun <T> fromCache(cache: String): T {
            val colonIndex = cache.indexOf(":")
            val typeLength = cache.substring(0, colonIndex).toInt()

            val type = cache.substring(colonIndex + 1, colonIndex + 1 + typeLength)
            val jsonString = cache.substring(colonIndex + 1 + typeLength)
            return gson.fromJson(jsonString, Class.forName(type)) as T
        }
    }
}

fun SimpleCacheConfig.redisCache(
    configure: RedisCacheProvider.Config.() -> Unit
) {
    provider = RedisCacheProvider(RedisCacheProvider.Config().apply(configure))
}