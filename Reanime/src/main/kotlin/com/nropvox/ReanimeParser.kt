package com.nropvox

import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure helpers, kept apart from [Reanime] so they can be unit tested without the app. */
object ReanimeParser {
    const val FLIX_REFERER = "https://flixcloud.cc/"

    private val animeSlug = Regex("""^/(?:anime|watch)/([^/?#]+)""")
    private val streamInf = Regex("""RESOLUTION=\d+x(\d+)""")
    private val uriAttr = Regex("""URI="([^"]*)"""")

    /** `https://reanime.to/anime/naruto-bjfend` or `/watch/naruto-bjfend?ep=3` to the title slug. */
    fun slugOf(url: String): String? {
        val path = url.substringAfter("://").substringAfter('/', "").let { "/$it" }
        return animeSlug.find(path)?.groupValues?.get(1)
    }

    /** Episode data: a full watch URL that also carries the ids the stream endpoint needs. */
    fun watchUrl(base: String, slug: String, episode: Int, anilistId: Int?, malId: Int?, lang: String): String =
        "$base/watch/$slug?ep=$episode&al=${anilistId ?: 0}&mal=${malId ?: 0}&lang=$lang"

    class WatchData(val slug: String, val episode: Int, val anilistId: Int, val malId: Int, val lang: String)

    fun parseWatchUrl(url: String): WatchData? {
        val slug = slugOf(url) ?: return null
        val query = url.substringAfter('?', "").substringBefore('#').split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val episode = query["ep"]?.toIntOrNull() ?: return null
        return WatchData(slug, episode, query["al"]?.toIntOrNull() ?: 0, query["mal"]?.toIntOrNull() ?: 0, query["lang"] ?: "sub")
    }

    /** `/api/flix/{anilist}/{ep}`; the site passes `mal` always and falls back to id 0 when AniList is unknown. */
    fun flixPath(anilistId: Int, malId: Int, episode: Int): String =
        "/api/flix/$anilistId/$episode" + if (malId > 0) "?mal=$malId" else ""

    /** The English title when there is one; the API leaves it as an empty string rather than null. */
    fun pickTitle(english: String?, romaji: String?, native: String?): String? =
        listOf(english, romaji, native).firstOrNull { !it.isNullOrBlank() }?.trim()

    /** Descriptions are HTML fragments (`<br>`, `<i>`). */
    fun plainText(html: String?): String? = html?.replace(Regex("""<br\s*/?>"""), "\n")?.replace(Regex("""<[^>]+>"""), "")
        ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.trim()?.takeIf { it.isNotEmpty() }

    // ---- flixcloud embed ----

    class Subtitle(val url: String, val language: String, val isDefault: Boolean)

    /** What the embed page needs from its data literal, before the token request. */
    class EmbedData(
        val seed: String,
        val wasm: ByteArray,
        val frag1: ByteArray,
        val iv: ByteArray,
        val frag2: ByteArray,
        val token: String,
        val title: String?,
        val subtitles: List<Subtitle>,
    )

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun b64(text: String): ByteArray = Base64.getMimeDecoder().decode(text.trim())

    /**
     * The embed's JS data literal uses randomised key names derived from `obfuscation_seed`
     * (see `scripts/flix_decode.mjs` in the recon notes). Keys that start with a digit are quoted.
     */
    fun parseEmbed(html: String): EmbedData {
        fun field(name: String): String? =
            Regex(""""?${Regex.escape(name)}"?:\s*"([^"]*)"""").find(html)?.groupValues?.get(1)

        val seed = field("obfuscation_seed") ?: throw IllegalStateException("Embed has no obfuscation_seed")
        val wasm = field("w_payload") ?: throw IllegalStateException("Embed has no w_payload")
        var e = seed
        repeat(3) { e = sha256Hex(e + it) }
        var a = e
        repeat(3) { a = sha256Hex(a + it) }
        val keyField = "kf_" + e.substring(8, 16)
        val ivField = "ivf_" + e.substring(16, 24)
        val tokenField = e.substring(48, 64) + "_" + e.substring(56, 64)
        val frag2Field = a.substring(0, 16) + "_" + a.substring(16, 24)
        val frag1 = field(keyField) ?: throw IllegalStateException("Embed key fragment missing")
        val iv = field(ivField) ?: throw IllegalStateException("Embed iv missing")
        val frag2 = field(frag2Field) ?: throw IllegalStateException("Embed second key fragment missing")
        val token = field(tokenField) ?: throw IllegalStateException("Embed token reference missing")

        val subtitles = Regex("""\{url:"([^"]+)",language:"((?:[^"\\]|\\.)*)",format:"[a-z]+",default:(true|false)\}""")
            .findAll(html).map { Subtitle(it.groupValues[1], it.groupValues[2].replace("\\\"", "\""), it.groupValues[3] == "true") }.toList()
        return EmbedData(seed, b64(wasm), b64(frag1), b64(iv), b64(frag2), token, field("video_title"), subtitles)
    }

    /** `{ "<10 hex>": "<base64>", … }`; every value is a plain string. */
    fun parseTokenResponse(json: String): Map<String, String> =
        Regex(""""([^"]+)"\s*:\s*"([^"]*)"""").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }

    class Decoded(val masterUrl: String, val pk: ByteArray)

    /** Runs the embed's own key derivation: token fields, the WASM mixer, PBKDF2 and AES-256-CBC. */
    fun decodeEmbed(embed: EmbedData, tokenJson: String): Decoded {
        val response = parseTokenResponse(tokenJson)
        val encrypted = response[sha256Hex(embed.token + "vid").substring(0, 10)]
            ?: throw IllegalStateException("Token response has no video field")
        val frag3 = b64(response[sha256Hex(embed.token + "key").substring(0, 10)]
            ?: throw IllegalStateException("Token response has no key field"))

        val wasm = ReanimeWasm(embed.wasm)
        val n = embed.frag1.size
        val p1 = 1000
        val p2 = p1 + n
        val p3 = p2 + n
        val out = p3 + n
        embed.frag1.copyInto(wasm.memory, p1)
        embed.frag2.copyInto(wasm.memory, p2)
        frag3.copyInto(wasm.memory, p3)
        wasm.call("_s", embed.seed.substring(0, 8).toLong(16).toInt())
        wasm.call("_r", p1, p2, p3, out, n)
        val mixed = wasm.memory.copyOfRange(out, out + n)
        val pkPtr = wasm.call("_c") ?: throw IllegalStateException("wasm _c returned nothing")
        val pk = wasm.memory.copyOfRange(pkPtr, pkPtr + 32)

        val seedBytes = embed.seed.toByteArray()
        val derived = pbkdf2Sha256(mixed, seedBytes, 1000, 32)
        for (i in derived.indices) derived[i] = (derived[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
        val key = MessageDigest.getInstance("SHA-256").digest(derived)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(embed.iv))
        val url = String(cipher.doFinal(b64(encrypted)), Charsets.UTF_8)
        if (!url.startsWith("http")) throw IllegalStateException("Decrypted embed URL looks wrong")
        return Decoded(url, pk)
    }

    /** PBKDF2-HMAC-SHA256 by hand: the JDK's factory wants a char password, and ours is raw bytes. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, offset, 0, minOf(t.size, length - offset))
            offset += t.size
            block++
        }
        return out
    }

    // ---- playlists ----

    /** The master playlist body is base64 text XOR-ed with the 32 byte key from the WASM `_c` export. */
    fun unmaskPlaylist(body: String, pk: ByteArray): String {
        val text = body.trim()
        if (text.startsWith("#EXTM3U")) return text
        val raw = b64(text)
        return String(ByteArray(raw.size) { (raw[it].toInt() xor pk[it % pk.size].toInt()).toByte() }, Charsets.UTF_8)
    }

    /** The tallest `RESOLUTION` in a master, as the quality of the link. */
    fun topHeight(master: String): Int? = streamInf.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

    /**
     * Rewrites the master for the player: every URI goes through [mapUri] (which receives it already absolute, since
     * the master's own are relative to a URL the player won't see), and audio rendition [preferLanguage] becomes the
     * default, so sub and dub are two links over one master.
     */
    fun rewriteMaster(master: String, masterUrl: String, preferLanguage: String, mapUri: (String) -> String): String {
        val base = URI(masterUrl)
        fun mapped(uri: String) = mapUri(base.resolve(uri).toString())
        return master.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO") -> {
                    val language = Regex("""LANGUAGE="([^"]*)"""").find(line)?.groupValues?.get(1)
                    val isPreferred = language == preferLanguage
                    line.replace(uriAttr) { """URI="${mapped(it.groupValues[1])}"""" }
                        .replace(Regex("""DEFAULT=(YES|NO)"""), "DEFAULT=" + if (isPreferred) "YES" else "NO")
                }
                line.isBlank() || line.startsWith("#") -> line
                else -> mapped(line.trim())
            }
        }
    }

    /**
     * Media playlists are masked too and name their key relatively (`URI="key.bin"`). Served from loopback they would
     * resolve against the wrong host, so key and segment URIs are made absolute against the playlist's real URL.
     * Playlists with an `EXT-X-KEY` use plain HLS AES-128 that the player handles itself; the others hold segments in
     * the site's own wrapping (see [unwrapSegment]), so [mapSegment] sends those through the local server.
     */
    fun absolutizeMedia(playlist: String, playlistUrl: String, mapSegment: ((String) -> String)? = null): String {
        val base = URI(playlistUrl)
        val encrypted = playlist.lines().any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }
        val mapper = if (encrypted) null else mapSegment
        return playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#") -> line.replace(uriAttr) { """URI="${base.resolve(it.groupValues[1])}"""" }
                line.isBlank() -> line
                else -> base.resolve(line.trim()).toString().let { mapper?.invoke(it) ?: it }
            }
        }
    }

    private val segmentKey = intArrayOf(157, 42, 241, 71, 179, 142, 92, 112, 166, 25, 228, 59, 216, 98, 15, 197)

    /**
     * Segments from the HD-2 style hosts are MPEG-TS dressed as an image: a WebP (12 byte `RIFF....WEBP`) or PNG
     * (8 byte magic) header in front, and unless the next byte is the TS sync byte `G`, the rest XOR-ed with a fixed
     * 16 byte key. This mirrors the site's custom hls.js loader. Anything else is returned untouched.
     */
    fun unwrapSegment(data: ByteArray): ByteArray {
        fun at(i: Int) = if (i < data.size) data[i].toInt() and 0xff else -1
        val headerSize = when {
            data.size >= 12 && at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> 12
            data.size >= 8 && at(0) == 137 && at(1) == 80 && at(2) == 78 && at(3) == 71 &&
                at(4) == 13 && at(5) == 10 && at(6) == 26 && at(7) == 10 -> 8
            else -> return data
        }
        val body = data.copyOfRange(headerSize, data.size)
        if (at(headerSize) == 'G'.code) return body
        for (i in body.indices) body[i] = (body[i].toInt() xor segmentKey[i and 15]).toByte()
        return body
    }
}
