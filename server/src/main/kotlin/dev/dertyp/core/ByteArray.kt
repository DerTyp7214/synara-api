package dev.dertyp.core

import java.security.MessageDigest

fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")

    val hashBytes = digest.digest(this)

    return hashBytes.joinToString("") { "%02x".format(it) }
}