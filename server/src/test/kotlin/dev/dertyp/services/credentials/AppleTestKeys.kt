package dev.dertyp.services.credentials

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.io.encoding.Base64

object AppleTestKeys {
    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun pem(keyPair: KeyPair = keyPair()): String =
        "-----BEGIN PRIVATE KEY-----\n${Base64.Mime.encode(keyPair.private.encoded)}\n-----END PRIVATE KEY-----\n"
}
