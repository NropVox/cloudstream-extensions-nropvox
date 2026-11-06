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

class Pornhub : MainAPI() {
    override var mainUrl = "https://www.pornhub.com"
    override var name = "Pornhub"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/video" to "Latest",
        "$mainUrl/video?o=tr&t=m" to "Top Rated",
        "$mainUrl/video?o=mv&t=m" to "Most Viewed",
        "$mainUrl/video?o=cm&t=m" to "Most Discussed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = withPage(request.data, page)
        val doc = app.get(url).document
        val items = PornhubParser.cards(doc, mainUrl).map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, PornhubParser.hasNextPage(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get(withPage("$mainUrl/video/search?search=$encoded", page)).document
        val items = PornhubParser.cards(doc, mainUrl).map { it.toSearchResponse() }
        return newSearchResponseList(items, PornhubParser.hasNextPage(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val detail = PornhubParser.detail(app.get(url).document) ?: throw ErrorLoadingException("Could not parse $url")
        return newMovieLoadResponse(detail.title, url, TvType.NSFW, url) {
            posterUrl = detail.poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            plot = detail.plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val detail = PornhubParser.detail(app.get(data, referer = data).document) ?: return false
        var emitted = false
        detail.media.filter { it.format == "hls" }.distinctBy { it.url }.forEach { media ->
            callback(newExtractorLink(name, "$name ${media.quality ?: 0}p", media.url, ExtractorLinkType.M3U8) {
                quality = media.quality ?: 0
                referer = data
            })
            emitted = true
        }
        detail.media.filter { it.format == "mp4" }.distinctBy { it.url }.forEach { media ->
            callback(newExtractorLink(name, "$name MP4", media.url, ExtractorLinkType.VIDEO) {
                quality = media.quality ?: 0
                referer = data
            })
            emitted = true
        }
        return emitted
    }

    private fun withPage(url: String, page: Int): String =
        if (page <= 1) url else "$url${if ('?' in url) '&' else '?'}page=$page"

    private fun PornhubParser.Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
}
