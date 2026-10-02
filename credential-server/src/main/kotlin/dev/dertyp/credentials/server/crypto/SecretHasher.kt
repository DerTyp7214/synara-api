package dev.dertyp.credentials.server.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

object SecretHasher {
    const val CLIENT_ID_PREFIX = "syn_"
    const val SECRET_PREFIX = "cs_"
    private val random = SecureRandom()
    private val dummyHash = hash("${SECRET_PREFIX}dummy-secret-for-unknown-clients")

    fun newClientId(): String = CLIENT_ID_PREFIX + ByteArray(8).also { random.nextBytes(it) }.toHexString()

    fun newSecret(): String =
        SECRET_PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(ByteArray(32).also { random.nextBytes(it) })

    fun hash(secret: String): String = sha256(secret.toByteArray(Charsets.UTF_8)).toHexString()

    fun matches(secret: String, storedHash: String?): Boolean {
        val provided = hash(secret).toByteArray(Charsets.US_ASCII)
        val expected = (storedHash ?: dummyHash).toByteArray(Charsets.US_ASCII)
        val equal = MessageDigest.isEqual(provided, expected)
        return equal && storedHash != null
    }

    fun constantTimeEquals(provided: String, expected: String): Boolean = MessageDigest.isEqual(
        sha256(provided.toByteArray(Charsets.UTF_8)),
        sha256(expected.toByteArray(Charsets.UTF_8)),
    )

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
