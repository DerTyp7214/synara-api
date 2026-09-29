package dev.dertyp.services.credentials

import dev.dertyp.core.sha256
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.logging.KtorSimpleLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class CredentialCipher(private val config: ApplicationConfig) {
    private val logger = KtorSimpleLogger("CredentialCipher")
    private val random = SecureRandom()

    private val key: SecretKeySpec by lazy { SecretKeySpec(resolveKey(), "AES") }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(aad: String, plain: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encode(iv + sealed)
    }

    fun decrypt(aad: String, stored: String): String? {
        if (!isEncrypted(stored)) return null
        return try {
            val payload = Base64.decode(stored.removePrefix(PREFIX))
            if (payload.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            logger.warn("Stored credential $aad could not be decrypted: ${e.message}")
            null
        }
    }

    private fun resolveKey(): ByteArray {
        val configured = config.propertyOrNull("credentials.encryptionKey")?.getString()?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            logger.info("Credentials are encrypted with the key from CREDENTIALS_ENCRYPTION_KEY")
            return configured.toByteArray(Charsets.UTF_8).sha256().hexToByteArray()
        }
        val file = keyFile()
        if (Files.exists(file)) {
            val decoded = Base64.decode(Files.readString(file).trim())
            check(decoded.size == KEY_BYTES) { "Credential key file $file does not hold a $KEY_BYTES byte key" }
            logger.info("Credentials are encrypted with the key file $file")
            return decoded
        }
        val generated = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(file, Base64.encode(generated))
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
        }
        logger.info("Generated a new credential key file at $file")
        return generated
    }

    private fun keyFile(): Path =
        config.propertyOrNull("credentials.keyFile")?.getString()?.trim()?.ifBlank { null }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".config", "synara", "credentials.key")

    companion object {
        const val PREFIX = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
    }
}
