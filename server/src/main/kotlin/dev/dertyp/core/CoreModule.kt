package dev.dertyp.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.dertyp.serializers.ByteArrayISO8859TypeAdapter
import dev.dertyp.serializers.DurationAdapter
import dev.dertyp.serializers.LocalDateAdapter
import dev.dertyp.serializers.OffsetDateTimeAdapter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.time.Duration

fun coreModule(application: Application, environment: ApplicationEnvironment): Module = module {
    single<Application> { application }
    single<ApplicationEnvironment> { environment }
    single { environment.config }

    singleOf(::HttpClientFactory)
    singleOf(::ChangeNotifier)
    singleOf(::HttpClientQueueService)

    single<Gson> {
        GsonBuilder()
            .registerTypeAdapter(OffsetDateTime::class.java, OffsetDateTimeAdapter())
            .registerTypeAdapter(ByteArray::class.java, ByteArrayISO8859TypeAdapter())
            .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
            .registerTypeAdapter(Duration::class.java, DurationAdapter())
            .registerTypeHierarchyAdapter(Flow::class.java, object : TypeAdapter<Flow<*>>() {
                override fun write(out: JsonWriter, value: Flow<*>?) {
                    out.nullValue()
                }

                override fun read(reader: JsonReader): Flow<*> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        reader.skipValue()
                    }
                    return emptyFlow<Any>()
                }
            })
            .create()
    }
}
