package dev.dertyp.services.credentials

import io.ktor.server.config.MapApplicationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.encoding.Base64

class CredentialCipherTest {
    @TempDir
    lateinit var dir: Path

    private fun withKey(key: String) = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to key))

    private fun withFile(file: Path, key: String = "") = CredentialCipher(
        MapApplicationConfig("credentials.encryptionKey" to key, "credentials.keyFile" to file.toString())
    )

    @Test
    fun `values round trip and never contain the plain text`() {
        val cipher = withKey("secret")
        val sealed = cipher.encrypt("apiKey", "key123")

        assertTrue(sealed.startsWith(CredentialCipher.PREFIX))
        assertTrue(cipher.isEncrypted(sealed))
        assertFalse(sealed.contains("key123"))
        assertEquals("key123", cipher.decrypt("apiKey", sealed))
        assertNotEquals(sealed, cipher.encrypt("apiKey", "key123"))
    }

    @Test
    fun `plain text is rejected`() {
        val cipher = withKey("secret")
        assertFalse(cipher.isEncrypted("key123"))
        assertNull(cipher.decrypt("apiKey", "key123"))
        assertNull(cipher.decrypt("apiKey", ""))
    }

    @Test
    fun `tampering, a wrong key and a different setting key all fail`() {
        val cipher = withKey("secret")
        val sealed = cipher.encrypt("apiKey", "key123")

        val payload = Base64.decode(sealed.removePrefix(CredentialCipher.PREFIX))
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt("apiKey", CredentialCipher.PREFIX + Base64.encode(payload)))

        assertNull(withKey("other").decrypt("apiKey", sealed))
        assertNull(cipher.decrypt("apiSecret", sealed))
        assertNull(cipher.decrypt("apiKey", CredentialCipher.PREFIX + "not base64 !"))
        assertNull(cipher.decrypt("apiKey", CredentialCipher.PREFIX + Base64.encode(ByteArray(4))))
    }

    @Test
    fun `the configured key wins over the key file`() {
        val file = dir.resolve("credentials.key")
        val sealed = withFile(file, key = "secret").encrypt("apiKey", "key123")

        assertFalse(Files.exists(file))
        assertEquals("key123", withKey("secret").decrypt("apiKey", sealed))
        assertNull(withFile(file).decrypt("apiKey", sealed))
    }

    @Test
    fun `the key file is generated once and reused`() {
        val file = dir.resolve("nested").resolve("credentials.key")
        val sealed = withFile(file).encrypt("apiKey", "key123")

        assertTrue(Files.exists(file))
        val content = Files.readString(file)
        assertEquals(32, Base64.decode(content.trim()).size)
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }

        val second = withFile(file)
        assertEquals("key123", second.decrypt("apiKey", sealed))
        second.encrypt("apiKey", "other")
        assertEquals(content, Files.readString(file))
    }
}
