package com.nropvox

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
import org.jsoup.nodes.Element

class JavFC2 : MainAPI() {
    override var mainUrl = "https://javfc2.xyz"
    override var name = "JavFC2"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

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
        val document = app.get(url).document

        val home = document.select(".movie-container > div").mapNotNull {
            it.mainPageResults()
        }

        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/?q=$query&per_page=${(page - 1) * 24}"
        val document = app.get(url).document

        val results = document.select(".movie-container > div").mapNotNull {
            it.mainPageResults()
        }

        return newSearchResponseList(results, results.isNotEmpty())
    }

    private fun Element.mainPageResults(): SearchResponse? {
        if (selectFirst(".label")?.text()?.trim() in setOf("Actor", "Seller")) {
            return null
        }

        val link = this.selectFirst(".movie-title a") ?: return null
        val title = link.text().trim()
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrl(img.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private val srcRegex = Regex("""src:\s*['"](https?://[^'"]+[^'"]*)['"]""")

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("#info img")?.attr("src"))

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
        val document = app.get(data).document

        val code = data.substringBefore("?").substringAfterLast("/").substringBefore(".html")

        val videoUrl = document.select("#player-div script").html().let {
            srcRegex.find(it)?.groups[1]?.value
        } ?: return false

        document.select("track[kind=\"subtitles\"]").forEach { sub ->
            val subUrl = sub.attr("src")
            val label = sub.attr("label")
            subtitleCallback(newSubtitleFile(label, fixUrl(subUrl)))
        }

        // Segments sit behind fake .png names, which M3u8Helper rejects, so the player probes the playlist itself.
        callback(newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
            referer = "$mainUrl/"
        })
        loadExtractor("https://subtitlecat.com/?query=$code", subtitleCallback, callback)

        return true
    }
}