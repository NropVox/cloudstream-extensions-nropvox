package com.nropvox

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pure logic for kisskh: page URLs, the `kkey` token that `DramaList/Episode` and `Sub` demand, and the per-cue
 * subtitle encryption. Constants come from the site's `common.js`, `scripts.*.js` and the watch-page chunk
 * (see kisskh.ovh-recon/kisskh.ovh-api.md §6.4 and §7); a new site build may change them.
 */
object KissKHParser {
    /** Watch-page `viGuid`, for `DramaList/Episode`. */
    const val VIDEO_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"

    /** Watch-page `subGuid`, for `Sub`. */
    const val SUB_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"

    private const val APP_VER = "2.8.10"
    private const val PLATFORM_VER = "4830201"
    private const val APP_NAME = "kisskh"

    // common.js ships the expanded AES-128 key schedule; these are its first four words and the CBC IV words.
    private val KKEY_KEY = hex("4f6bdaa39e2f8cb07f5e722d9edef314")
    private val KKEY_IV = hex("01504af356e619cf2e42bba68c3f70f9")

    /** Cue keys by subtitle file extension (`b1`, `b2`, `b3` in the player); `srt` is plain. */
    private val SUB_KEYS = mapOf(
        "txt" to ("8056483646328763" to "6852612370185273"),
        "txt1" to ("AmSmZVcH93UQUezi" to "ReBKWW8cqdjPEnF6"),
    )
    private val OTHER_SUB_KEY = "sWODXX04QRTkHdlZ" to "8pwhapJeC4hrS9hO"

    private val idParam = Regex("""[?&]id=(\d+)""")
    private val epParam = Regex("""[?&]ep=(\d+)""")
    private val nonAlnum = Regex("[^A-Za-z0-9]")
    private val titleYear = Regex("""\((\d{4})\)\s*$""")
    private val base64Line = Regex("""^[A-Za-z0-9+/]{16,}={0,2}$""")

    private const val HEX = "0123456789ABCDEF"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** The site's slug: every non-alphanumeric character becomes a dash. Only `id`/`ep` matter to the API. */
    fun slug(title: String) = title.replace(nonAlnum, "-")

    fun dramaUrl(mainUrl: String, title: String, id: Int) = "$mainUrl/Drama/${slug(title)}?id=$id"

    fun episodeUrl(mainUrl: String, title: String, dramaId: Int, number: String, episodeId: Int) =
        "$mainUrl/Drama/${slug(title)}/Episode-$number?id=$dramaId&ep=$episodeId"

    fun dramaId(url: String) = idParam.find(url)?.groupValues?.get(1)?.toIntOrNull()

    fun episodeId(url: String) = epParam.find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** `24.0` → `24`, `12.5` stays `12.5`. */
    fun episodeNumber(number: Double) = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    /** Many titles carry their year, e.g. `The Lament of Autumn (2026)`; `releaseDate` is when the site added it. */
    fun yearOf(title: String) = titleYear.find(title)?.groupValues?.get(1)?.toIntOrNull()

    fun hasNext(page: KissPage) = page.page * page.pageSize < page.totalCount

    /** JS `h = (h << 5) - h + c`: only the shift is int32, so `h` itself drifts past the int range. */
    fun jsHash(s: String): Long {
        var h = 0L
        for (c in s) h = (h.toInt() shl 5).toLong() - h + c.code
        return h
    }

    /** `cryptoService.key(episodeId, null, appVer, guid, platformVer, appName × 6)` from common.js. */
    fun kkey(episodeId: Int, guid: String): String {
        val parts = mutableListOf(
            "", episodeId.toString(), "", "mg3c3b04ba", APP_VER, guid, PLATFORM_VER,
            APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME, "00", "",
        )
        parts.add(1, jsHash(parts.joinToString("|")).toString())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KKEY_KEY, "AES"), IvParameterSpec(KKEY_IV))
        val bytes = cipher.doFinal(parts.joinToString("|").toByteArray(Charsets.ISO_8859_1))
        return buildString(bytes.size * 2) {
            for (b in bytes) append(HEX[b.toInt() shr 4 and 15]).append(HEX[b.toInt() and 15])
        }
    }

    /** The player's rule: last `.` part of the URL, before any `?` or `#`. */
    fun subtitleExtension(src: String) = src.substringAfterLast('.').split('?', '#').first().lowercase()

    fun isEncryptedSubtitle(src: String) = subtitleExtension(src) != "srt"

    /** Decrypts every cue line of an SRT whose text lines are each base64 AES-128-CBC; other lines pass through. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decryptSubtitle(body: String, extension: String): String {
        val (key, iv) = SUB_KEYS[extension] ?: OTHER_SUB_KEY
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        return body.lineSequence().joinToString("\n") { line ->
            val text = line.trim()
            if (!base64Line.matches(text)) return@joinToString line
            runCatching {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                cipher.doFinal(Base64.decode(text)).decodeToString()
            }.getOrDefault(line)
        }
    }
}
