package com.nropvox

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Payload encryption used by the public players; unrelated to HLS segment encryption. */
internal object TonkaCineDecoder {
    private const val VIDROCK_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
    private const val VIDNEST_SECRET = "F9k2mR8vL4qP7wY1zN0fG3bS6tH5jC8d_x4R7vN2mP9qL1"

    fun vidrock(payload: String): String = decrypt(payload,
        VIDROCK_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    fun vidnest(payload: String, nonce: String): String = decrypt(payload,
        MessageDigest.getInstance("SHA-256").digest("$nonce|$VIDNEST_SECRET".toByteArray(Charsets.UTF_8)))

    private fun decrypt(payload: String, key: ByteArray): String {
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
        require(bytes.size >= 28) { "Incomplete player payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }
}
