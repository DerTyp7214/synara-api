package dev.dertyp.routing.rest

class CapturedArguments(vararg val args: Any?) : RuntimeException(null, null, false, false) {
    @Suppress("UNCHECKED_CAST")
    fun <T> arg(index: Int): T = args[index] as T
}

inline fun captureDefaults(block: () -> Any?): CapturedArguments =
    try {
        block()
        error("defaults probe returned")
    } catch (captured: CapturedArguments) {
        captured
    }
