package dev.dertyp.credentials.server.crypto

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class SecretBox(key: ByteArray) {
    private val keySpec: SecretKeySpec
    private val random = SecureRandom()

    init {
        require(key.size == KEY_BYTES) { "The master key must be $KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    fun encrypt(aad: String, plain: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encode(iv + sealed)
    }

    fun decrypt(aad: String, stored: String): String {
        check(stored.startsWith(PREFIX)) { "Value for $aad is not encrypted" }
        val payload = Base64.decode(stored.removePrefix(PREFIX))
        check(payload.size > IV_BYTES) { "Value for $aad is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val PREFIX = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        const val KEY_BYTES = 32
        private val logger = LoggerFactory.getLogger(SecretBox::class.java)

        fun create(masterKey: String, keyFile: Path): SecretBox = SecretBox(resolveKey(masterKey, keyFile))

        fun keyFromMasterKey(masterKey: String): ByteArray {
            val decoded = runCatching { Base64.decode(masterKey) }.getOrNull()
            if (decoded != null && decoded.size == KEY_BYTES) return decoded
            return MessageDigest.getInstance("SHA-256").digest(masterKey.toByteArray(Charsets.UTF_8))
        }

        private fun resolveKey(masterKey: String, keyFile: Path): ByteArray {
            if (masterKey.isNotBlank()) {
                logger.info("Credentials are encrypted with the key from CREDENTIAL_SERVER_MASTER_KEY")
                return keyFromMasterKey(masterKey)
            }
            if (Files.exists(keyFile)) {
                val decoded = Base64.decode(Files.readString(keyFile).trim())
                check(decoded.size == KEY_BYTES) { "Master key file $keyFile does not hold a $KEY_BYTES byte key" }
                logger.info("Credentials are encrypted with the key file {}", keyFile)
                return decoded
            }
            val generated = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            keyFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(keyFile, Base64.encode(generated))
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
            }
            logger.warn(
                "Generated a new master key file at {}. Back it up, stored credentials are unreadable without it",
                keyFile.toAbsolutePath()
            )
            return generated
        }
    }
}
