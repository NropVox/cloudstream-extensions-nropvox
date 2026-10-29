package com.nropvox

import kotlin.io.encoding.Base64
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The handshake's request token and response token share one envelope (`/_astro/CwylGxhk2.js`):
 * AES-256-GCM with key SHA-256("htv-insecure-handshake-v1") and AAD "htv-insecure-v1", wrapped as
 * b64url(JSON {v, alg, iv, tag, data}) with every field b64url without padding.
 */
object HanimeCrypto {
    private const val TAG_BYTES = 16
    private val key = SecretKeySpec(sha256("htv-insecure-handshake-v1".toByteArray()), "AES")
    private val aad = "htv-insecure-v1".toByteArray()
    private val random = SecureRandom()

    // Compiled into the signer WASM in hanime-cdn.com/js/vendor.<hash>.min.js; they change when that file does.
    private const val SALT_A = "Xkdi29"
    private const val SALT_B = "mn2"

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val out = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plain.toByteArray())
        val envelope = mapOf(
            "v" to 1,
            "alg" to "AES-256-GCM",
            "iv" to b64(iv),
            "tag" to b64(out.copyOfRange(out.size - TAG_BYTES, out.size)),
            "data" to b64(out.copyOfRange(0, out.size - TAG_BYTES)),
        )
        return b64(envelope.toJson().toByteArray())
    }

    fun decrypt(token: String): String {
        val envelope = parseJson<Envelope>(String(unb64(token)))
        val sealed = unb64(envelope.data) + unb64(envelope.tag)
        return String(cipher(Cipher.DECRYPT_MODE, unb64(envelope.iv)).doFinal(sealed))
    }

    /** `x-signature` = sha256("{t},Xkdi29,{origin},mn2,{t}"); the server allows well under two minutes of skew. */
    fun signature(time: Long, origin: String): String =
        sha256("$time,$SALT_A,$origin,$SALT_B,$time".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cipher(mode: Int, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(TAG_BYTES * 8, iv))
        updateAAD(aad)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    private fun b64(bytes: ByteArray) = base64.encode(bytes)

    private fun unb64(value: String) = base64.decode(value)

    data class Envelope(val iv: String, val tag: String, val data: String)
}
