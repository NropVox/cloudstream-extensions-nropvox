package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder

/** Pure parsing for dulo.mov: TMDB-keyed URLs, the ZCDN and resolver contracts, labels and playlists. */
object DuloParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Where the Workers live when `dulo-config.js` can't be read. */
    const val DEFAULT_WORKER_DOMAIN = "iamwatchingyou.site"

    private val workerDomain = Regex("""WORKER_DOMAIN\s*:\s*['"]([a-z0-9.-]+)['"]""", RegexOption.IGNORE_CASE)
    private val titleRef = Regex("""/(movie|tv)/(\d+)""")
    private val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val labelQuality = Regex("""\b(\d{3,4})p\b""", RegexOption.IGNORE_CASE)
    private val fourK = Regex("""\b4K\b""", RegexOption.IGNORE_CASE)

    /** `WORKER_DOMAIN` from `https://dulo.mov/dulo-config.js`; the site moves it between Cloudflare accounts. */
    fun workerDomain(configJs: String): String? = workerDomain.find(configJs)?.groupValues?.get(1)?.lowercase()

    /** `(type, tmdbId)` from a title, watch or plugin URL such as `https://dulo.mov/title/tv/1396/`. */
    fun titleRef(url: String): Pair<String, Int>? =
        titleRef.find(url)?.destructured?.let { (type, id) -> id.toIntOrNull()?.let { type to it } }

    fun titleUrl(mainUrl: String, type: String, id: Int) = "$mainUrl/title/$type/$id/"

    fun zcdnUrl(domain: String, host: Int, link: DuloLink): String {
        val query = if (link.type == "tv") {
            "tmdb=${link.id}&type=tv&season=${link.season ?: 1}&episode=${link.episode ?: 1}"
        } else {
            "tmdb=${link.id}&type=movie"
        }
        return "https://zcdn$host.$domain/cdn?$query"
    }

    /** The play URL of a ready answer, if it has the shape the site accepts; null while ZCDN is still building. */
    fun zcdnPlayUrl(json: String, domain: String): String? {
        val answer = runCatching { mapper.readValue<ZcdnAnswer>(json) }.getOrNull() ?: return null
        if (!answer.ready) return null
        val url = answer.url ?: return null
        val shape = Regex(
            "^https://zcdn[123]\\." + Regex.escape(domain) +
                "/play/[A-Za-z0-9._-]{1,160}/(?:master\\.m3u8|~[A-Za-z0-9._-]{1,160})$"
        )
        return url.takeIf { shape.matches(it) }
    }

    /**
     * Height of the best variant in a master playlist, bucketed like the site's label (a 1920x800 scope
     * rip counts as 1080p). Null when the playlist names no resolution.
     */
    fun topHeight(master: String): Int? = resolution.findAll(master).map { match ->
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        when {
            width >= 3800 || height >= 2000 -> 2160
            width >= 1900 || height >= 1000 -> 1080
            width >= 1280 || height >= 700 -> 720
            else -> height
        }
    }.maxOrNull()

    /** Query of `/api/streams/resolve`, with the optional title/year the site sends to help matching. */
    fun resolveQuery(link: DuloLink): String = listOfNotNull(
        "tmdbId" to link.id.toString(),
        "type" to link.type,
        "progressive" to "true",
        link.season?.takeIf { link.type == "tv" }?.let { "seasonId" to it.toString() },
        link.episode?.takeIf { link.type == "tv" }?.let { "episodeId" to it.toString() },
        link.title?.let { "title" to it },
        link.year?.let { "year" to it.toString() },
    ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }

    fun resolveAnswer(json: String): ResolveAnswer? = runCatching { mapper.readValue<ResolveAnswer>(json) }.getOrNull()

    /** `"Cobalt · 720p"` → `("Cobalt", 720)`, `"Breeze"` → `("Breeze", null)`, `"Pickle · 4K"` → 2160. */
    fun splitLabel(label: String): Pair<String, Int?> {
        val server = label.split('·').first().trim()
        val quality = if (fourK.containsMatchIn(label)) 2160 else labelQuality.find(label)?.groupValues?.get(1)?.toIntOrNull()
        return server to quality
    }

    fun subtitles(json: String): List<DuloSubtitle> =
        runCatching { mapper.readValue<List<DuloSubtitle>>(json) }.getOrNull().orEmpty().filter { !it.url.isNullOrBlank() }

    /** Japanese animation, the way dulo's Anime shelf picks it (genre 16 from JP). */
    fun isAnime(genreIds: List<Int>?, originCountry: List<String>?): Boolean =
        genreIds.orEmpty().contains(16) && originCountry.orEmpty().contains("JP")
}
