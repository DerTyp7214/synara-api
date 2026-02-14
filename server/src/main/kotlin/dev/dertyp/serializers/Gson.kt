package dev.dertyp.serializers

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

class OffsetDateTimeAdapter : TypeAdapter<OffsetDateTime>() {
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    override fun write(out: JsonWriter, value: OffsetDateTime?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(formatter.format(value))
        }
    }

    override fun read(reader: JsonReader): OffsetDateTime? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return OffsetDateTime.parse(reader.nextString(), formatter)
    }
}

class LocalDateAdapter : TypeAdapter<LocalDate>() {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

    override fun write(out: JsonWriter, value: LocalDate?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(formatter.format(value))
        }
    }

    override fun read(reader: JsonReader): LocalDate? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return LocalDate.parse(reader.nextString(), formatter)
    }
}

class DurationAdapter : TypeAdapter<Duration>() {
    override fun write(out: JsonWriter, value: Duration?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toIsoString())
        }
    }

    override fun read(reader: JsonReader): Duration? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return Duration.parseIsoString(reader.nextString())
    }
}

class ByteArrayISO8859TypeAdapter : TypeAdapter<ByteArray>() {
    override fun write(out: JsonWriter, value: ByteArray?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(String(value, StandardCharsets.ISO_8859_1))
        }
    }

    override fun read(reader: JsonReader): ByteArray? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val stringValue = reader.nextString()
        return stringValue.toByteArray(StandardCharsets.ISO_8859_1)
    }
}