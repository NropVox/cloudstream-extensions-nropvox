package com.nropvox

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class JavStream : MainAPI() {
    override var mainUrl = "https://sextb.net"
    override var name = "JavStream"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val cloudflareInterceptor = CloudflareKiller()

    private val commonHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/jav-uncensored?genre=all&studio=all&quality=all&year=all&sort=viewed" to "Uncensored",
        "${mainUrl}/genre/beautiful-girl" to "Beautiful Girl",
        "${mainUrl}/genre/beautiful-pussy" to "Beautiful Pussy",
        "${mainUrl}/studio/fc2ppv" to "FC2PPV",
        "${mainUrl}/jav-subtitle" to "Subtitle"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            request.data.substringBefore("?").removeSuffix("/") + "/pg-$page" +
                request.data.substringAfter("?", "").takeIf { it.isNotEmpty() }
                    ?.let { "?$it" }.orEmpty()
        }

        val document = app.get(url, headers = commonHeaders, interceptor = cloudflareInterceptor).document
        val items = document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)), hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) {
            "$mainUrl/search/$encodedQuery"
        } else {
            "$mainUrl/search/$encodedQuery/pg-$page"
        }

        val document = app.get(url, headers = commonHeaders, interceptor = cloudflareInterceptor).document
        val items = document.select("div.tray-item").mapNotNull {
            it.toSearchResult()
        }

        if (items.isEmpty()) {
            Log.w("JavStream", "Search list returned empty! URL: $url")
        }

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href").trim()

        if (href.isBlank() || href.startsWith("#") || href.startsWith("/search") ||
            href.contains("javascript", ignoreCase = true) || href.startsWith("/genre")) return null

        val fullHref = fixUrl(href)
        val title =
            this.selectFirst("div.tray-item-title")?.text()?.trim() ?: this.selectFirst("img")
                ?.attr("alt")?.trim() ?: return null

        val posterUrl =
            fixUrlNull(this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, fullHref, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = commonHeaders, interceptor = cloudflareInterceptor)
        val document = res.document

        val title = document.selectFirst("h1.film-info-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("#infomation img")?.attr("data-src"))

        val description = document.selectFirst("span.full-text-desc")?.text()?.trim()
        val code = document.select(".film-info-title a").attr("href")
            .substringBefore("?").substringBefore("#").trimEnd('/')
            .substringAfterLast("/").trim().ifEmpty { return null }
        val yearText = document.selectFirst("div.description:has(i.fa-calendar) strong")?.text()
        val year =
            yearText?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val duration = document.selectFirst("div.description:has(i.fa-clock) strong")?.text()
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = document.select("div.description:has(i.fa-list) a").map { it.text() }
        val actors = document.select("div.description:has(i.fa-users) a").map { Actor(it.text()) }

        val recommendations = mutableListOf<SearchResponse>()
        val filmId = Regex("""var filmId\s*=\s*(\d+)""").find(res.text)?.groupValues?.get(1)

        if (filmId != null) {
            try {
                val apiRes = app.get(
                    "${mainUrl}/ajax/related/$filmId",
                    headers = commonHeaders,
                    interceptor = cloudflareInterceptor
                ).text
                val apiDoc = org.jsoup.Jsoup.parse(apiRes)
                recommendations.addAll(apiDoc.select(".tray-item").mapNotNull { it.toSearchResult() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("JavStream", "Recommendations failed: ${e.message}")
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, JavStreamLinks(url, code).toJson()) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = tryParseJson<JavStreamLinks>(data) ?: JavStreamParser.legacyLinks(data, mainUrl)
        val code = links.code.ifBlank { return false }
        // The page extractors look the code up on SubtitleCat too, so the same file can arrive more than once.
        val subtitles = Collections.synchronizedSet(HashSet<String>())
        val subtitle: (SubtitleFile) -> Unit = { if (subtitles.add(it.url)) subtitleCallback(it) }
        val found = AtomicBoolean(false)
        val link: (ExtractorLink) -> Unit = { found.set(true); callback(it) }

        // The player starts only once every source has returned, so a stalled site may not hold it for long.
        fun source(site: String, resolve: suspend () -> Unit): suspend () -> Unit = {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) { resolve() } ?: Log.w(name, "$site: timed out")
        }
        runAllAsync(
            source("SubtitleCat") { loadExtractor("https://subtitlecat.com/?query=$code", subtitle, link) },
            source("SexTB") { loadExtractor(links.url, "$mainUrl/", subtitle, link) },
            source("Javtiful") { JavStreamSources.javtiful(code, subtitle, link) },
            source("MissAV") { JavStreamSources.missav(code, subtitle, link) },
            source("JavGG") { JavStreamSources.javgg(code, subtitle, link) },
            source("SupJav") { JavStreamSources.supjav(code, subtitle, link) },
            source("JavFC2") { JavStreamSources.javfc2(code, subtitle, link) },
            source("JavHD") { JavStreamSources.javhd(code, subtitle, link) },
        )
        return found.get()
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L
    }
}
