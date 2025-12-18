package dev.dertyp.plugins

import com.google.gson.Gson
import com.ucasoft.ktor.simpleCache.SimpleCacheConfig
import com.ucasoft.ktor.simpleCache.SimpleCacheProvider
import org.koin.core.component.KoinComponent
import org.koin.java.KoinJavaComponent.inject
import redis.clients.jedis.JedisPooled
import kotlin.time.Duration

class RedisCacheProvider(config: Config) : SimpleCacheProvider(config) {

    private val jedis: JedisPooled = JedisPooled(config.host, config.port, config.ssl)

    override suspend fun getCache(key: String): Any? =
        if (jedis.exists(key)) RedisCacheObject.fromCache(jedis[key]) else null

    override suspend fun setCache(key: String, content: Any, invalidateAt: Duration?) {
        if (invalidateAt != null && !invalidateAt.isInfinite())
            jedis.psetex(key, invalidateAt.inWholeMilliseconds, RedisCacheObject.fromObject(content).toString())
        else jedis.set(key, RedisCacheObject.fromObject(content).toString())
    }

    class Config internal constructor() : SimpleCacheProvider.Config() {

        var host = "localhost"

        var port = 6379

        var ssl = false
    }
}

class RedisCacheObject(val type: String, val content: String): KoinComponent {
    override fun toString() = "$type%#%$content"

    companion object {
        private val gson by inject<Gson>(Gson::class.java)

        fun fromObject(`object`: Any) = RedisCacheObject(`object`::class.java.name, gson.toJson(`object`))

        @Suppress("UNCHECKED_CAST")
        fun <T> fromCache(cache: String): T {
            val data = cache.split("%#%")
            return gson.fromJson(data.last(), Class.forName(data.first())) as T
        }
    }
}

fun SimpleCacheConfig.redisCache(
    configure: RedisCacheProvider.Config.() -> Unit
) {
    provider = RedisCacheProvider(RedisCacheProvider.Config().apply(configure))
}