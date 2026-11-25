package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element
import java.net.URLEncoder

class JavFC2 : MainAPI() {
    override var mainUrl = "https://javfc2.xyz"
    override var name = "JavFC2"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val site by lazy { Challenged(CloudflareKiller()) }

    override val mainPage = mainPageOf(
        "home/vids" to "Latest",
        "genre/eng-sub" to "English Subtitle",
        "genre/fc2" to "FC2PPV",
        "genre/jav" to "JAV",
        "genre/webcam" to "WebCam",
        "home/ranking?year=2026&month=all" to "Top 2026",
        "home/ranking?year=2025&month=all" to "Top 2025",
        "home/ranking?year=2024&month=all" to "Top 2024",
        "home/ranking?year=2023&month=all" to "Top 2023",
        "home/ranking?year=2022&month=all" to "Top 2022",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var trueData = request.data

        var truePage = page
        if ("genre" in request.data) {
            truePage = 24 * (page - 1)
        }

        if ("ranking" in request.data) {
            val params = request.data.substringAfter("?")
            trueData = "home/ranking/$page?$params"
        } else {
            trueData = "$trueData/$truePage"
        }

        val url = "$mainUrl/$trueData"
        val document = site.get(url).document
        val home = JavFC2Parser.cards(document).map { it.toSearchResponse() }

        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/?q=$encodedQuery&per_page=${(page - 1) * 24}"
        val document = site.get(url).document
        val results = JavFC2Parser.cards(document).map { it.toSearchResponse() }

        return newSearchResponseList(results, results.isNotEmpty())
    }

    private fun JavFC2Parser.Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            posterUrl = fixUrlNull(poster)
        }

    override suspend fun load(url: String): LoadResponse? {
        val document = site.get(url).document
        val title = JavFC2Parser.watchTitle(document) ?: return null
        val poster = fixUrlNull(JavFC2Parser.watchPoster(document))

        val episodes = document.select(".season a:not([data-toggle])").mapIndexedNotNull { i, a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapIndexedNotNull null
            val title = a.text().ifEmpty { "Ep ${i + 1}" }

            newEpisode(href) {
                name = title
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            posterUrl = poster
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = site.get(data).document

        val code = data.substringBefore("?").substringAfterLast("/").substringBefore(".html")

        val videoUrl = JavFC2Parser.streamUrl(document) ?: return false

        document.select("track[kind=\"subtitles\"]").forEach { sub ->
            val subUrl = sub.attr("src")
            val label = sub.attr("label")
            val fullUrl = fixUrl(subUrl)
            // The .vtt sits behind the same Cloudflare challenge, and the player fetches it on its own, so it
            // needs the clearance cookie and user agent the plugin solved for.
            subtitleCallback(newSubtitleFile(label, fullUrl) {
                headers = site.headers(fullUrl) + mapOf("Referer" to "$mainUrl/")
            })
        }

        // Segments sit behind fake .png names, which M3u8Helper rejects, so the player probes the playlist itself.
        callback(newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
            referer = "$mainUrl/"
        })
        loadExtractor("https://subtitlecat.com/?query=$code", subtitleCallback, callback)

        return true
    }
}

internal object JavFC2Parser {
    private val srcRegex = Regex("""\bsrc\s*:\s*['"](https?://[^'"\s]+)['"]""")

    data class Card(val title: String, val href: String, val poster: String?)

    fun cards(document: Element): List<Card> = document.select(".movie-container > article, .movie-container > div")
        .mapNotNull { card ->
            val label = card.selectFirst(".label, .modern-movie-card__badge")?.text()
            if (label == "Actor" || label == "Seller") return@mapNotNull null

            val link = card.selectFirst("a.modern-movie-card__link[href], .movie-title a[href]") ?: return@mapNotNull null
            val title = (link.selectFirst(".modern-movie-card__title") ?: link).text()
            val href = link.attr("href").trim()
            if (title.isBlank() || href.isEmpty()) return@mapNotNull null

            val image = card.selectFirst(".movie-img img") ?: card.selectFirst("img")
            val poster = image?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            Card(title, href, poster)
        }

    fun watchTitle(document: Element): String? =
        (document.selectFirst(".modern-watch-title") ?: document.selectFirst(".title"))
            ?.text()?.takeIf { it.isNotBlank() }

    fun watchPoster(document: Element): String? =
        (document.selectFirst(".modern-watch-poster img") ?: document.selectFirst("#info img"))?.attr("src")

    fun streamUrl(document: Element): String? = document.select("#player-div script").firstNotNullOfOrNull { script ->
        srcRegex.find(script.data().replace("\\/", "/"))?.groupValues?.get(1)
    }
}

/**
 * javfc2.xyz answers every path with a Cloudflare managed challenge. The first request solves it alone; later
 * ones replay the clearance two at a time, since 5+ parallel requests deadlock inside CloudflareKiller.
 */
private class Challenged(private val cloudflare: CloudflareKiller) {
    @Volatile private var warmUntil = 0L
    private val lock = Mutex()
    private val slots = Semaphore(2)

    fun headers(url: String): Map<String, String> = cloudflare.getCookieHeaders(url).toMap()

    suspend fun get(url: String): NiceResponse {
        if (System.currentTimeMillis() >= warmUntil) lock.withLock {
            if (System.currentTimeMillis() >= warmUntil) {
                val response = app.get(url, interceptor = cloudflare, timeout = 60L)
                if (response.code in 200..299) warmUntil = System.currentTimeMillis() + WARM_MS
                return checked(response)
            }
        }
        return checked(slots.withPermit { app.get(url, interceptor = cloudflare, timeout = 60L) })
    }

    private fun checked(response: NiceResponse): NiceResponse {
        if (response.code in listOf(403, 503) && response.headers["server"].orEmpty().startsWith("cloudflare")) {
            warmUntil = 0L
            throw ErrorLoadingException("Cloudflare check not passed, retry in a moment")
        }
        return response
    }

    private companion object {
        const val WARM_MS = 10 * 60 * 1000L
    }
}
