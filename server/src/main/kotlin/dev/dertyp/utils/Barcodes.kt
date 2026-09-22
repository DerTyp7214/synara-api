package dev.dertyp.utils

object Barcodes {
    private val PADDED_LENGTHS = listOf(12, 13, 14)

    fun normalize(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        val trimmed = digits.trimStart('0')
        return trimmed.takeIf { it.length >= 8 }
    }

    fun variants(raw: String?): List<String> {
        val normalized = normalize(raw) ?: return emptyList()
        return (listOf(normalized) + PADDED_LENGTHS.mapNotNull { length ->
            if (normalized.length < length) normalized.padStart(length, '0') else null
        }).distinct()
    }
}
