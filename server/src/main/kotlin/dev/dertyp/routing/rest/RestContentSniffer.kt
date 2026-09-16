package dev.dertyp.routing.rest

import io.ktor.http.ContentType

private val JpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val Gif87Magic = "GIF87a".toByteArray(Charsets.US_ASCII)
private val Gif89Magic = "GIF89a".toByteArray(Charsets.US_ASCII)
private val RiffMagic = "RIFF".toByteArray(Charsets.US_ASCII)
private val WebPMagic = "WEBP".toByteArray(Charsets.US_ASCII)
private val BmpMagic = "BM".toByteArray(Charsets.US_ASCII)
private val FtypMagic = "ftyp".toByteArray(Charsets.US_ASCII)
private val MatroskaMagic = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

private val WebPType = ContentType("image", "webp")
private val BmpType = ContentType("image", "bmp")
private val WebmType = ContentType("video", "webm")
private val AvifType = ContentType("image", "avif")
private val HeicType = ContentType("image", "heic")
private val QuickTimeType = ContentType("video", "quicktime")

private val AvifBrands = setOf("avif", "avis")
private val HeicBrands = setOf("heic", "heix", "hevc", "mif1", "msf1")

private fun ByteArray.matchesAt(offset: Int, magic: ByteArray): Boolean {
    if (offset < 0 || size - offset < magic.size) return false
    for (index in magic.indices) {
        if (this[offset + index] != magic[index]) return false
    }
    return true
}

private fun ByteArray.isoBrandType(): ContentType {
    if (size < 12) return ContentType.Video.MP4
    val brand = String(this, 8, 4, Charsets.US_ASCII).lowercase()
    return when {
        brand in AvifBrands -> AvifType
        brand in HeicBrands -> HeicType
        brand == "qt  " -> QuickTimeType
        else -> ContentType.Video.MP4
    }
}

fun sniffMediaType(bytes: ByteArray): ContentType? = when {
    bytes.matchesAt(0, PngMagic) -> ContentType.Image.PNG
    bytes.matchesAt(0, JpegMagic) -> ContentType.Image.JPEG
    bytes.matchesAt(0, Gif87Magic) || bytes.matchesAt(0, Gif89Magic) -> ContentType.Image.GIF
    bytes.matchesAt(0, RiffMagic) && bytes.matchesAt(8, WebPMagic) -> WebPType
    bytes.matchesAt(0, BmpMagic) -> BmpType
    bytes.matchesAt(4, FtypMagic) -> bytes.isoBrandType()
    bytes.matchesAt(0, MatroskaMagic) -> WebmType
    else -> null
}
