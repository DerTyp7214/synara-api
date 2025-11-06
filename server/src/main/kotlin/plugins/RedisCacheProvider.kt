package dev.dertyp.plugins

import com.google.gson.Gson
import com.ucasoft.ktor.simpleCache.SimpleCacheConfig
import com.ucasoft.ktor.simpleCache.SimpleCacheProvider
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

private class RedisCacheObject(val type: String, val content: String) {

    override fun toString() = "$type%#%$content"

    companion object {

        fun fromObject(`object`: Any) = RedisCacheObject(`object`::class.java.name, Gson().toJson(`object`))

        fun fromCache(cache: String): Any {
            val data = cache.split("%#%")
            return Gson().fromJson(data.last(), Class.forName(data.first()))
        }
    }
}

fun SimpleCacheConfig.redisCache(
    configure: RedisCacheProvider.Config.() -> Unit
) {
    provider = RedisCacheProvider(RedisCacheProvider.Config().apply(configure))
}