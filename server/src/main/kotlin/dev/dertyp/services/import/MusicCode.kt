package dev.dertyp.services.import

sealed class MusicCode {
    object Url : MusicCode()
    data class Isrc(val value: String) : MusicCode()
    data class Upc(val value: String) : MusicCode()

    companion object {
        // ISRC: 2-letter country, 3-char alphanumeric registrant, 2-digit year, 5-digit designation.
        private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

        // UPC-A (12), EAN-13 (13), EAN-8 (8) and GTIN-14 (14) barcodes.
        private val UPC_LENGTHS = setOf(8, 12, 13, 14)

        fun classify(input: String): MusicCode {
            val normalized = input.trim().replace("-", "").replace(" ", "")

            if (ISRC_REGEX.matches(normalized.uppercase())) return Isrc(normalized.uppercase())
            if (normalized.length in UPC_LENGTHS && normalized.all { it.isDigit() }) return Upc(normalized)

            return Url
        }
    }
}
