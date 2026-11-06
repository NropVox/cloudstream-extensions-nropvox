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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/** eporner.com: catalog/search/details from the public JSON API, streams from /xhr/video. */
class Eporner : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "Eporner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "all|latest" to "Latest",
        "all|top-weekly" to "Top Weekly",
        "all|most-popular" to "Most Popular",
        "all|top-rated" to "Top Rated",
        "asian|top-weekly" to "Asian",
        "milf|top-weekly" to "MILF",
        "amateur|top-weekly" to "Amateur",
        "japanese|top-weekly" to "Japanese",
        "4k|top-weekly" to "4K",
    )

    private fun api(query: String, order: String, page: Int): String =
        "$mainUrl/api/v2/video/search/?query=${URLEncoder.encode(query, "UTF-8")}" +
            "&per_page=36&page=$page&order=$order&thumbsize=big&gay=0&lq=0&format=json"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (query, order) = request.data.split('|')
        val res = app.get(api(query, order, page)).parsedSafe<EpornerSearch>()
        val items = res?.videos.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext(res, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val res = app.get(api(query.trim(), "most-popular", page)).parsedSafe<EpornerSearch>()
        return newSearchResponseList(res?.videos.orEmpty().mapNotNull { it.toSearchResponse() }, hasNext(res, page))
    }

    override suspend fun load(url: String): LoadResponse {
        val id = EpornerParser.videoId(url) ?: throw ErrorLoadingException("Not an Eporner video URL: $url")
        val v = app.get("$mainUrl/api/v2/video/id/?id=$id&thumbsize=big&format=json").parsedSafe<EpornerVideo>()
            ?: throw ErrorLoadingException("Could not load $id")
        val pageUrl = v.url ?: url
        return newMovieLoadResponse(v.title ?: id, pageUrl, TvType.NSFW, pageUrl) {
            posterUrl = v.default_thumb?.src
            plot = listOfNotNull(v.length_min?.let { "Duration: $it" }, v.views?.let { "Views: $it" }, v.rate?.let { "Rating: $it/5" })
                .joinToString(" · ")
            tags = EpornerParser.tags(v.keywords)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = EpornerParser.videoId(data) ?: return false
        val html = app.get(data).text
        val hash = EpornerParser.pageHash(html) ?: return false
        val json = app.get(
            "$mainUrl/xhr/video/$id?hash=${EpornerParser.xhrHash(hash)}&domain=www.eporner.com" +
                "&fallback=false&embed=false&supportedFormats=dash,mp4&_=${System.currentTimeMillis()}",
            referer = data,
        ).text
        val sources = EpornerParser.sources(json)
        sources.forEach { s ->
            callback(newExtractorLink(name, "$name ${s.label}", s.url, ExtractorLinkType.VIDEO) {
                quality = s.quality
                referer = "$mainUrl/"
            })
        }
        return sources.isNotEmpty()
    }

    private fun hasNext(res: EpornerSearch?, page: Int): Boolean =
        res != null && !res.videos.isNullOrEmpty() && page < (res.total_pages ?: 0)

    private fun EpornerVideo.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        return newMovieSearchResponse(title ?: return null, u, TvType.NSFW) {
            posterUrl = default_thumb?.src
        }
    }
}
