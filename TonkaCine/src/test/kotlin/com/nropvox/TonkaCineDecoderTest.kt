package com.nropvox

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Payloads are sealed here with the players' scheme: b64url(iv[12] + ciphertext + tag[16]). */
class TonkaCineDecoderTest {
    private val vidrockKey = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun seal(plain: String, key: ByteArray, padded: Boolean): String {
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encoder = Base64.getUrlEncoder().let { if (padded) it else it.withoutPadding() }
        return encoder.encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    // 60 bytes of plaintext seal to 88 bytes, so the padded form ends in "==".
    private val json = """{"sources":[{"url":"https://cdn.example/master.m3u8?a=12"}]}"""

    @Test fun vidrockAcceptsPaddedAndUnpaddedPayloads() {
        assertEquals(json, TonkaCineDecoder.vidrock(seal(json, vidrockKey, padded = true)))
        assertEquals(json, TonkaCineDecoder.vidrock(seal(json, vidrockKey, padded = false)))
    }

    @Test fun vidnestKeyIsDerivedFromNonce() {
        val nonce = "AAECAwQFBgcICQoLDA0ODw"
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$nonce|F9k2mR8vL4qP7wY1zN0fG3bS6tH5jC8d_x4R7vN2mP9qL1".toByteArray())
        assertEquals(json, TonkaCineDecoder.vidnest(seal(json, key, padded = false), nonce))
    }
}
