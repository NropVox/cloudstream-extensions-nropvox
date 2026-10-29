package com.nropvox

import kotlin.io.encoding.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Movie or episode `data` of Movy; what its resolvers are queried with. */
data class MovyLink(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("imdbId") val imdbId: String? = null,
    @JsonProperty("totalSeasons") val totalSeasons: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)

/**
 * movy.sx's city-named stream resolvers, keyed by TMDB id. Their responses are encrypted with a short-lived seed
 * (see [MovyDecoder]). Shared by the Movy and OmniStream plugins; [load]'s label names the links and log lines.
 */
object MovySource {
    const val SITE = "https://www.movy.sx"
    private const val STREAM_API = "https://api.wecollege.net"
    private const val SUBTITLE_API = "https://subtitles.vidy.st"

    // The HLS CDNs reject playlists without the site's Referer.
    private val siteHeaders = mapOf("Origin" to SITE, "Referer" to "$SITE/")

    /** Resolvers in the site's picker order; the label is the audio language the site advertises. */
    private val servers = listOf(
        "miami" to "4K", "boise" to "4K", "vegas" to null, "reno" to null, "oakland" to null,
        "seattle" to null, "phoenix" to null, "atlanta" to null, "portland" to null, "austin" to null,
        "dallas" to null, "tampa" to null, "orlando" to null, "munich" to "German", "berlin" to "German",
        "paris" to "French", "delhi" to "Hindi", "cancun" to "Spanish",
    )

    private val seedLock = Mutex()
    private val seeds = HashMap<Int, CachedSeed>()

    suspend fun load(
        link: MovyLink,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val query = sourceQuery(link)
        val found = AtomicBoolean(false)
        runAllAsync(
            { loadSubtitles(link, name, subtitleCallback) },
            {
                // One seed covers every server; /seed is rate-limited per IP, so it is never fetched in parallel.
                val seed = seed(link.id, name) ?: return@runAllAsync
                servers.amap { (server, label) ->
                    val sources = sources(server, query, link.id, seed, name) ?: return@amap
                    if (emitSources(server, label, sources, name, subtitleCallback, callback)) found.set(true)
                }
            },
        )
        return found.get()
    }

    private suspend fun sources(server: String, query: String, mediaId: Int, seed: String, name: String): MovySources? {
        var currentSeed = seed
        repeat(2) { attempt ->
            try {
                val response = app.get(
                    "$STREAM_API/$server/sources?$query&enc=2&seed=${encode(currentSeed)}",
                    headers = siteHeaders,
                    timeout = 45L
                )
                // A stale seed is rejected with 401; the site then fetches a new one and retries once.
                if (response.code == 401 && attempt == 0) {
                    currentSeed = seed(mediaId, name, stale = currentSeed) ?: return null
                    return@repeat
                }
                if (!response.isSuccessful) {
                    Log.d(name, "$server: HTTP ${response.code}")
                    return null
                }
                val blob = response.text.trim().trim('"')
                val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(blob)
                val json = MovyDecoder.decrypt(bytes, currentSeed, mediaId)
                    ?: return null.also { Log.w(name, "$server: payload did not decrypt") }
                return tryParseJson<MovySources>(json)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(name, "$server: ${e.message}")
                return null
            }
        }
        return null
    }

    private suspend fun emitSources(
        server: String,
        label: String?,
        sources: MovySources,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourceName = "$name ${server.replaceFirstChar { it.uppercase() }}" + (label?.let { " ($it)" } ?: "")
        sources.subtitles.orEmpty().forEach { subtitle ->
            val url = subtitle.url ?: return@forEach
            subtitleCallback(newSubtitleFile(subtitle.language ?: subtitle.lang ?: "Unknown", url) {
                headers = siteHeaders
            })
        }

        // Miami/Boise also return a master playlist that covers every per-quality source.
        val links = sources.playlist?.let { listOf(MovyStream("Auto", it)) }
            ?: sources.sources.orEmpty()
        var found = false
        for (source in links) {
            val url = source.url ?: continue
            val path = url.substringBefore('?').lowercase()
            val type = if (path.endsWith(".mp4") || "/mp4/" in path) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            val quality = source.quality?.takeIf { it.isNotBlank() }
            callback(newExtractorLink(sourceName, listOfNotNull(sourceName, quality).joinToString(" - "), url, type) {
                referer = "$SITE/"
                this.quality = quality?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                headers = siteHeaders
            })
            found = true
        }
        return found
    }

    private suspend fun loadSubtitles(link: MovyLink, name: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val params = listOfNotNull(
            "id=${link.imdbId ?: link.id}",
            link.season?.let { "season=$it" },
            link.episode?.let { "episode=$it" },
        ).joinToString("&")
        // A bare JSON array: List<T> would come back as maps on the device, so it is read as an Array.
        val subtitles = try {
            app.get("$SUBTITLE_API/search?$params", headers = siteHeaders).parsedSafe<Array<MovySubtitle>>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(name, "Subtitles: ${e.message}")
            null
        }
        subtitles.orEmpty().forEach { subtitle ->
            val url = subtitle.url ?: return@forEach
            subtitleCallback(newSubtitleFile(subtitle.display ?: subtitle.language ?: "Unknown", url))
        }
    }

    /** Query string the resolvers expect; absent values are omitted like on the site. */
    private fun sourceQuery(link: MovyLink): String = listOfNotNull(
        // The site encodeURIComponent()s the title and then URL-encodes it again.
        "title" to encode(encodeURIComponent(link.title)),
        "mediaType" to link.type,
        link.year?.let { "year" to it.toString() },
        link.totalSeasons?.let { "totalSeasons" to it.toString() },
        link.season?.let { "seasonId" to it.toString() },
        link.episode?.let { "episodeId" to it.toString() },
        "tmdbId" to link.id.toString(),
        link.imdbId?.let { "imdbId" to encode(it) },
    ).joinToString("&") { (key, value) -> "$key=$value" }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodeURIComponent(value: String): String = encode(value)
        .replace("+", "%20").replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    /**
     * Seeds live ~30 s and are cached per title like on the site. Passing [stale] drops that seed first,
     * unless another caller already replaced it.
     */
    private suspend fun seed(mediaId: Int, name: String, stale: String? = null): String? = seedLock.withLock {
        val cached = seeds[mediaId]
        if (cached != null && cached.seed != stale && cached.expiresAt > System.currentTimeMillis()) {
            return@withLock cached.seed
        }
        seeds.remove(mediaId)
        try {
            val response = app.get("$STREAM_API/seed?mediaId=$mediaId", headers = siteHeaders)
            val result = response.parsedSafe<MovySeed>()
            val seed = result?.seed
                ?: return@withLock null.also { Log.w(name, "Seed: HTTP ${response.code} ${response.text.take(100)}") }
            seeds[mediaId] = CachedSeed(seed, System.currentTimeMillis() + (result.ttlMs ?: 30_000L) - 5_000L)
            seed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "Seed: ${e.message}")
            null
        }
    }

    private class CachedSeed(val seed: String, val expiresAt: Long)

    data class MovySeed(
        @JsonProperty("seed") val seed: String? = null,
        @JsonProperty("ttlMs") val ttlMs: Long? = null,
    )

    data class MovySources(
        @JsonProperty("sources") val sources: List<MovyStream>? = null,
        @JsonProperty("subtitles") val subtitles: List<MovySourceSubtitle>? = null,
        @JsonProperty("playlist") val playlist: String? = null,
    )

    data class MovyStream(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class MovySourceSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null,
    )

    data class MovySubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("display") val display: String? = null,
        @JsonProperty("language") val language: String? = null,
    )
}
