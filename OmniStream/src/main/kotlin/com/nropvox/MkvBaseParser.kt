package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One `/api/links` result: a post title and the link it points to. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MkvBaseResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MkvBaseResults(@JsonProperty("results") val results: List<MkvBaseResult>? = null)

/**
 * The request signing of mkvbase.site's search (its page script, November 2026): the query is XOR-hex encoded with
 * the timestamp, a double SHA-256 proof of work is found against the `mkv_challenge` cookie, and the whole is
 * HMAC-signed with the `mkv_client_key` cookie.
 */
object MkvBaseParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** The page gives up after this many tries; so does this. */
    private const val MAX_NONCE = 500_000

    /** The page's floor for its interaction score, which is what it sends before the visitor moves or types. */
    const val ENTROPY = 10

    /** Each UTF-8 byte XOR the timestamp's low byte, as hex. */
    fun encodeQuery(query: String, time: Long): String {
        val key = (time % 256).toInt()
        return query.toByteArray().joinToString("") { ((it.toInt() and 0xff) xor key).toString(16).padStart(2, '0') }
    }

    /** The first nonce for which `sha256(sha256(challenge:nonce):query)` starts with [difficulty] hex zeros. */
    fun proofOfWork(challenge: String, difficulty: Int, query: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val tail = ":$query".toByteArray()
        var nonce = 0
        while (true) {
            val inner = hex(digest.digest("$challenge:$nonce".toByteArray())).toByteArray()
            digest.update(inner)
            if (zeroNibbles(digest.digest(tail), difficulty) || ++nonce > MAX_NONCE) return nonce
        }
    }

    /** Whether the hash's hex form starts with [count] zeros, without formatting it. */
    private fun zeroNibbles(hash: ByteArray, count: Int): Boolean {
        for (i in 0 until count) {
            val byte = hash.getOrNull(i / 2)?.toInt() ?: return false
            val nibble = if (i % 2 == 0) (byte shr 4) and 0xf else byte and 0xf
            if (nibble != 0) return false
        }
        return true
    }

    fun signature(clientKey: String, query: String, time: Long, seq: String, nonce: Int, entropy: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clientKey.toByteArray(), "HmacSHA256"))
        return hex(mac.doFinal("$query:$time:$seq:$nonce:$entropy".toByteArray()))
    }

    /** `mkv_challenge` is `<challenge>:<difficulty>`; the page reads a missing difficulty as 2. */
    fun challenge(cookie: String): Pair<String, Int> {
        val parts = cookie.split(':')
        return parts[0] to (parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 2)
    }

    /** The search URL path with every parameter, for an already encoded query. */
    fun linksPath(query: String, time: Long, seq: String, nonce: Int, entropy: Int, sig: String): String =
        "/api/links?q=$query&t=$time&seq=$seq&pow=$nonce&ent=$entropy&sig=$sig"

    fun results(json: String): List<MkvBaseResult> =
        runCatching { mapper.readValue<MkvBaseResults>(json).results.orEmpty() }.getOrDefault(emptyList())
            .filter { !it.title.isNullOrBlank() && it.url?.startsWith("http") == true }

    // --- Matching results to a title ---

    /** What a result's release name says: `The Devil Wears Prada 2 (2026) 1080p BluRay … mkv`, `Bleach S02E04 …`. */
    data class Release(val name: String, val year: Int?, val season: Int?, val episode: Int?, val pack: Boolean)

    private val apostrophe = Regex("""['’`]""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")
    // `GDFlix | `, `TitanCloud | ` before the name, `(Movies4u Foo) ` uploader tags.
    private val hostPrefix = Regex("""^[^|]{1,30}\|\s*""")
    private val groupPrefix = Regex("""^\([^)]*\)\s*""")
    private val episodeTag = Regex("""\bS(\d{1,2})\s?E(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val seasonTag = Regex("""\b(?:S|Season\s?)(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val yearTag = Regex("""\(?\b((?:19|20)\d{2})\b\)?""")
    // Where the name ends at the latest: episode and season tags, the resolution, source and bracketed audio tags.
    private val technical = Regex(
        """\bS\d{1,2}(?:\s?E\d{1,4})?\b|\bSeason\s?\d|\b\d{3,4}p\b|\b(?:DS)?4K\b|\b(?:WEB|WEBRip|BluRay|BrRip|BDRip|HDRip|HDTC|HDTS|HDTV|DVDRip|REMUX|HYBRiD)\b|\[""",
        RegexOption.IGNORE_CASE
    )

    /** The query the site's full-text search finds the name by: its stored names drop apostrophes and punctuation. */
    fun query(title: String): String = title.replace(apostrophe, "").replace(nonWord, " ").trim()

    /**
     * The name ends before the year that comes last before the technical tags, so a number in the name stays
     * (`Blade Runner 2049 2017`, `1917 2019`).
     */
    fun release(title: String): Release {
        val clean = title.trim().replace(hostPrefix, "").replace(groupPrefix, "").trim()
        val head = clean.substring(0, technical.find(clean)?.range?.first ?: clean.length)
        val year = yearTag.findAll(head).lastOrNull()?.takeIf { it.range.first > 0 }
        val name = (if (year != null) head.substring(0, year.range.first) else head).trim()
        val episode = episodeTag.find(clean)
        val season = episode?.groupValues?.get(1)?.toInt() ?: seasonTag.find(clean)?.groupValues?.get(1)?.toInt()
        val pack = clean.endsWith(" zip", true) || clean.endsWith(" pack", true) || (season != null && episode == null)
        return Release(name, year?.groupValues?.get(1)?.toInt(), season, episode?.groupValues?.get(2)?.toInt(), pack)
    }

    /**
     * The results that are this movie ([season] null) or episode, without season packs. A movie must name a year
     * close to one of [years], so a remake or the original of a sequel doesn't pass on its name alone.
     */
    fun matches(results: List<MkvBaseResult>, names: List<String>, years: List<Int>, season: Int?, episode: Int?): List<MkvBaseResult> {
        return results.filter { result ->
            val release = release(result.title ?: return@filter false)
            when {
                release.pack || !OmniStreamParser.sameTitle(release.name, names) -> false
                season == null -> release.season == null && release.year != null && OmniStreamParser.yearMatches(release.year, years)
                else -> release.season == season && release.episode == episode && OmniStreamParser.yearMatches(release.year, years)
            }
        }
    }

    private fun hex(bytes: ByteArray) = buildString(bytes.size * 2) {
        bytes.forEach { append(HEX[(it.toInt() shr 4) and 0xf]).append(HEX[it.toInt() and 0xf]) }
    }

    private const val HEX = "0123456789abcdef"
}
