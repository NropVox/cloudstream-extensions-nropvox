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

class FreePornVideos : MainAPI() {
    override var mainUrl = "https://www.freepornvideos.xxx"
    override var name = "FreePornVideos"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest Updates",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/most-popular/week/" to "Most Popular This Week",
        "$mainUrl/most-popular/month/" to "Most Popular This Month",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(withPage(request.data, page)).document
        val items = FreePornVideosParser.cards(doc, mainUrl).map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, FreePornVideosParser.hasNextPage(doc, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val slug = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "-")
        val doc = app.get(withPage("$mainUrl/search/$slug/", page)).document
        val items = FreePornVideosParser.cards(doc, mainUrl).map { it.toSearchResponse() }
        return newSearchResponseList(items, FreePornVideosParser.hasNextPage(doc, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val detail = FreePornVideosParser.detail(app.get(url).document)
            ?: throw ErrorLoadingException("Could not parse $url")
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
        val detail = FreePornVideosParser.detail(app.get(data, referer = data).document) ?: return false
        detail.media.distinctBy { it.url }.forEach { media ->
            callback(newExtractorLink(name, "$name ${media.quality ?: 0}p", media.url, ExtractorLinkType.VIDEO) {
                quality = media.quality ?: 0
                referer = data
            })
        }
        return detail.media.isNotEmpty()
    }

    private fun withPage(url: String, page: Int): String =
        if (page <= 1) url else "$url$page/"

    private fun FreePornVideosParser.Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
}