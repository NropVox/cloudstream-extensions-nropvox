package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Recovers GoPlay's encrypted JWPlayer configuration without running page JavaScript.
 *
 * The page ships a Dean Edwards packed control script that calls
 * `.setup(decoder(payload, true))`. The AES-256-CBC key is
 * `SHA256(markerClass + hex(SHA256(exact decoder function source)))`, where the marker class
 * appears in the control script's `$(".<class>")` selector; the payload's first 16 bytes are
 * the IV. String literals are scanned by hand because regex alternation over long literals
 * overflows the stack on Android.
 */
object GoPlayPlayer {
    private const val TAG = "GoPlay"
    private const val PACKER = "return p}("
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val scriptOpen = Regex("""<script\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val scriptClose = Regex("""</script\s*>""", RegexOption.IGNORE_CASE)
    private val scriptSrc = Regex("""<script\b[^>]*?\bsrc\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
    private val atobWrapper = Regex("""eval\(atob\("([A-Za-z0-9+/=]+)"\)\)""")
    private val setupCall = Regex("""\.setup\((\w+)\((\w+),true\)\)""")
    private val markerClass = Regex("""\$\("\.([\w-]{20,})"\)""")

    /**
     * Desktop pages inline the decoder; mobile pages (Android WebView user agents) load it from
     * an external GoPlay script, so [loadScript] fetches same-site scripts when it is not inline.
     */
    suspend fun extract(html: String, loadScript: suspend (String) -> String?): PlayerConfig? {
        val scripts = scripts(html)
        val control = scripts.firstOrNull { it.contains(PACKER) }?.let(::unpack)
            ?: return null.also { Log.w(TAG, "Packed player script missing") }
        val (decoder, variable) = setupCall.find(control)?.destructured
            ?: return null.also { Log.w(TAG, "Encrypted player setup missing") }
        val payload = assignedString(control, variable)
            ?.let { runCatching { base64DecodeArray(it) }.getOrNull() }
            ?.takeIf { it.size >= 32 && it.size % 16 == 0 }
            ?: return null.also { Log.w(TAG, "Player payload missing") }
        val classes = markerClass.findAll(control).map { it.groupValues[1] }.distinct().toList()

        decryptWith(scripts, decoder, payload, classes)?.let { return it }
        for (url in externalScriptUrls(html)) {
            val script = externalScripts[url]
                ?: loadScript(url)?.also { externalScripts[url] = it } ?: continue
            decryptWith(listOf(script), decoder, payload, classes)?.let { return it }
        }
        Log.w(TAG, "Player configuration could not be decrypted (decoder $decoder)")
        return null
    }

    // External scripts are versioned with ?v=<hash>, so a URL's content does not change.
    private val externalScripts = ConcurrentHashMap<String, String>()

    private fun externalScriptUrls(html: String): List<String> =
        scriptSrc.findAll(html).mapNotNull { match ->
            val src = match.groupValues[1]
            val url = if (src.startsWith("//")) "https:$src" else src
            val host = runCatching { URI(url).host }.getOrNull() ?: return@mapNotNull null
            url.takeIf { host == "goplay.su" || host.endsWith(".goplay.su") }
        }.distinct().toList()

    private fun decryptWith(
        scripts: List<String>,
        decoder: String,
        payload: ByteArray,
        classes: List<String>
    ): PlayerConfig? {
        for (script in scripts) {
            // The exact original bytes matter: whitespace or name changes change the key.
            val source = functionSource(script, decoder) ?: continue
            val sourceHash = sha256(source).toHex()
            for (className in classes) {
                val config = decrypt(payload, sha256(className + sourceHash))
                    ?.let { tryParseJson<PlayerConfig>(it) } ?: continue
                if (config.file != null || !config.sources.isNullOrEmpty() || !config.playlist.isNullOrEmpty()) {
                    return config
                }
            }
        }
        return null
    }

    private fun scripts(html: String): List<String> {
        val result = mutableListOf<String>()
        var from = 0
        while (true) {
            val open = scriptOpen.find(html, from) ?: break
            val close = scriptClose.find(html, open.range.last + 1) ?: break
            result.add(html.substring(open.range.last + 1, close.range.first))
            from = close.range.last + 1
        }
        return result
    }

    /** Reads `return p}('payload',radix,count,'words'.split('|')` as data. */
    private fun unpack(script: String): String? {
        var i = script.indexOf(PACKER).takeIf { it >= 0 }?.plus(PACKER.length) ?: return null
        val payloadEnd = literalEnd(script, i) ?: return null
        var payload = decodeSingleQuoted(script.substring(i + 1, payloadEnd))
        i = payloadEnd + 1

        fun number(): Int? {
            if (script.getOrNull(i) != ',') return null
            val start = ++i
            while (i < script.length && script[i].isDigit()) i++
            return script.substring(start, i).toIntOrNull()
        }

        val radix = number() ?: return null
        val count = number() ?: return null
        if (script.getOrNull(i++) != ',') return null
        val wordsEnd = literalEnd(script, i) ?: return null
        val words = decodeSingleQuoted(script.substring(i + 1, wordsEnd)).split("|")
        if (!script.startsWith(".split('|')", wordsEnd + 1)) return null
        if (radix !in 2..62 || count > 10_000) return null

        // Same order as the packer itself: highest index first.
        for (index in count - 1 downTo 0) {
            val word = words.getOrNull(index)
            if (!word.isNullOrEmpty()) payload = replaceWord(payload, encode(index, radix), word)
        }
        // Decode literal eval(atob("...")) wrappers as data; never evaluate them.
        return atobWrapper.replace(payload) { String(base64DecodeArray(it.groupValues[1]), Charsets.UTF_8) }
    }

    private fun encode(value: Int, radix: Int): String =
        (if (value >= radix) encode(value / radix, radix) else "") + ALPHABET[value % radix]

    private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /** JavaScript `\btoken\b` replacement; platform `\b` treats non-ASCII letters as word characters. */
    private fun replaceWord(text: String, token: String, word: String): String {
        val out = StringBuilder(text.length)
        var copied = 0
        var index = text.indexOf(token)
        while (index >= 0) {
            val end = index + token.length
            if ((index == 0 || !isWordChar(text[index - 1])) && (end == text.length || !isWordChar(text[end]))) {
                out.append(text, copied, index).append(word)
                copied = end
                index = text.indexOf(token, end)
            } else {
                index = text.indexOf(token, index + 1)
            }
        }
        return out.append(text, copied, text.length).toString()
    }

    /** Returns the index of the closing quote of the literal starting at [start]. */
    private fun literalEnd(text: String, start: Int): Int? {
        val quote = text.getOrNull(start)?.takeIf { it == '\'' || it == '"' } ?: return null
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> return i
            }
            i++
        }
        return null
    }

    private fun assignedString(control: String, variable: String): String? {
        val match = Regex("""\b${Regex.escape(variable)}="""").find(control) ?: return null
        val start = match.range.last
        val end = literalEnd(control, start) ?: return null
        return decodeDoubleQuoted(control.substring(start + 1, end))
    }

    private fun decodeSingleQuoted(body: String): String {
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            val next = body[i + 1]
            when {
                next == 'u' && hexAt(body, i + 2, 4) -> {
                    out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                }
                next == 'x' && hexAt(body, i + 2, 2) -> {
                    out.append(body.substring(i + 2, i + 4).toInt(16).toChar()); i += 4
                }
                next == '\r' && body.getOrNull(i + 2) == '\n' -> i += 3
                next == '\n' -> i += 2
                else -> {
                    out.append(
                        when (next) {
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'
                            'f' -> '\u000C'; 'v' -> '\u000B'; '0' -> '\u0000'
                            else -> next
                        }
                    )
                    i += 2
                }
            }
        }
        return out.toString()
    }

    /** JSON string semantics, as in the reference `JSON.parse` of the double-quoted literal. */
    private fun decodeDoubleQuoted(body: String): String {
        if (!body.contains('\\')) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            when (val next = body[i + 1]) {
                'u' -> {
                    if (!hexAt(body, i + 2, 4)) return body
                    out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                }
                else -> {
                    out.append(
                        when (next) {
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'
                            else -> next
                        }
                    )
                    i += 2
                }
            }
        }
        return out.toString()
    }

    private fun hexAt(text: String, start: Int, length: Int) =
        start + length <= text.length && (start until start + length).all { text[it].isHexDigit() }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Brace matching identical to the reference extractor, including its quote handling. */
    private fun functionSource(script: String, name: String): String? {
        val start = script.indexOf("function $name(").takeIf { it >= 0 } ?: return null
        val open = script.indexOf('{', start).takeIf { it >= 0 } ?: return null
        var depth = 1
        var quote: Char? = null
        var i = open + 1
        while (i < script.length) {
            val c = script[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '{') {
                depth++
            } else if (c == '}' && --depth == 0) {
                return script.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    private fun decrypt(payload: ByteArray, key: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(payload, 0, 16))
        String(cipher.doFinal(payload, 16, payload.size - 16), Charsets.UTF_8)
    } catch (e: Exception) {
        // A different marker class or a changed cipher cannot produce valid padding.
        null
    }

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    data class PlayerConfig(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("drm") val drm: PlayerDrm? = null,
        @JsonProperty("sources") val sources: List<PlayerSource>? = null,
        @JsonProperty("tracks") val tracks: List<PlayerTrack>? = null,
        @JsonProperty("playlist") val playlist: List<PlayerConfig>? = null,
    )

    data class PlayerSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("drm") val drm: PlayerDrm? = null,
    )

    data class PlayerDrm(
        @JsonProperty("clearkey") val clearkey: ClearKey? = null,
    )

    data class ClearKey(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("keyId") val keyId: String? = null,
    )

    data class PlayerTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("language") val language: String? = null,
    )
}
