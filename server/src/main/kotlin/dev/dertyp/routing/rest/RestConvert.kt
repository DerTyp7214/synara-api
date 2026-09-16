package dev.dertyp.routing.rest

import dev.dertyp.PlatformInstant
import dev.dertyp.PlatformUUID
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.services.metadata.IMetadataService
import java.time.Instant
import java.time.format.DateTimeParseException

class RestBindingException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

fun interface RestConverter<T : Any> {
    fun convert(raw: String): T
}

object RestConvert {
    val string: RestConverter<String> = RestConverter { it }

    val int: RestConverter<Int> = RestConverter { raw ->
        raw.toIntOrNull() ?: throw RestBindingException("Invalid Int value: $raw")
    }

    val long: RestConverter<Long> = RestConverter { raw ->
        raw.toLongOrNull() ?: throw RestBindingException("Invalid Long value: $raw")
    }

    val boolean: RestConverter<Boolean> = RestConverter { raw ->
        raw.toBooleanStrictOrNull() ?: throw RestBindingException("Invalid Boolean value: $raw")
    }

    val double: RestConverter<Double> = RestConverter { raw ->
        raw.toDoubleOrNull() ?: throw RestBindingException("Invalid Double value: $raw")
    }

    val float: RestConverter<Float> = RestConverter { raw ->
        raw.toFloatOrNull() ?: throw RestBindingException("Invalid Float value: $raw")
    }

    val uuid: RestConverter<PlatformUUID> = RestConverter { raw ->
        raw.toUUIDOrNull() ?: throw RestBindingException("Invalid UUID value: $raw")
    }

    val instant: RestConverter<PlatformInstant> = RestConverter { raw ->
        try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw RestBindingException("Invalid Instant value: $raw", e)
        }
    }

    val metadataType: RestConverter<IMetadataService.MetadataType> = RestConverter { raw ->
        IMetadataService.MetadataType(raw)
    }

    fun <E : Enum<E>> enum(values: Array<E>): RestConverter<E> = RestConverter { raw ->
        values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw RestBindingException("Invalid Enum value: $raw")
    }
}
