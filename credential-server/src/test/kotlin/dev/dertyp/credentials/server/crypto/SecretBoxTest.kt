package dev.dertyp.credentials.server.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import kotlin.io.encoding.Base64

class SecretBoxTest {
    @TempDir
    lateinit var dir: Path

    private fun box() = SecretBox(ByteArray(SecretBox.KEY_BYTES) { it.toByte() })

    @Test
    fun `round trips with the same aad`() {
        val box = box()
        val sealed = box.encrypt("credential:a", "top secret")
        assertTrue(sealed.startsWith(SecretBox.PREFIX))
        assertNotEquals(sealed, box.encrypt("credential:a", "top secret"))
        assertEquals("top secret", box.decrypt("credential:a", sealed))
    }

    @Test
    fun `decrypting with another aad fails`() {
        val box = box()
        val sealed = box.encrypt("credential:a", "top secret")
        assertThrows<AEADBadTagException> { box.decrypt("credential:b", sealed) }
    }

    @Test
    fun `decrypting with another key fails`() {
        val sealed = box().encrypt("credential:a", "top secret")
        val other = SecretBox(ByteArray(SecretBox.KEY_BYTES) { 7 })
        assertThrows<AEADBadTagException> { other.decrypt("credential:a", sealed) }
    }

    @Test
    fun `generates a private key file once and reuses it`() {
        val keyFile = dir.resolve("nested").resolve("master.key")
        val first = SecretBox.create("", keyFile)
        assertTrue(Files.exists(keyFile))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile))
        assertEquals(SecretBox.KEY_BYTES, Base64.decode(Files.readString(keyFile).trim()).size)
        val sealed = first.encrypt("credential:a", "value")
        val second = SecretBox.create("", keyFile)
        assertEquals("value", second.decrypt("credential:a", sealed))
    }

    @Test
    fun `master key accepts base64 keys and passphrases`() {
        val raw = ByteArray(SecretBox.KEY_BYTES) { (it * 3).toByte() }
        assertArrayEquals(raw, SecretBox.keyFromMasterKey(Base64.encode(raw)))
        val passphrase = "correct horse battery staple"
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray()),
            SecretBox.keyFromMasterKey(passphrase),
        )
        val keyFile = dir.resolve("unused.key")
        val sealed = SecretBox.create(passphrase, keyFile).encrypt("x", "y")
        assertEquals("y", SecretBox.create(passphrase, keyFile).decrypt("x", sealed))
        assertTrue(Files.notExists(keyFile))
    }

    @Test
    fun `hasher matches only the right secret`() {
        val secret = SecretHasher.newSecret()
        val hash = SecretHasher.hash(secret)
        assertTrue(secret.startsWith(SecretHasher.SECRET_PREFIX))
        assertTrue(SecretHasher.newClientId().matches(Regex("syn_[0-9a-f]{16}")))
        assertTrue(SecretHasher.matches(secret, hash))
        assertEquals(false, SecretHasher.matches(secret + "x", hash))
        assertEquals(false, SecretHasher.matches(secret, null))
    }
}
