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

class XHamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "xHamster"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/hd" to "HD",
        "$mainUrl/4k" to "4K",
        "$mainUrl/vr" to "VR",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(withPage(request.data, page)).document
        return newHomePageResponse(
            request.name,
            XHamsterParser.cards(doc, mainUrl).map { it.toSearchResponse() },
            XHamsterParser.hasNextPage(doc, page, mainUrl),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get(withPage("$mainUrl/search?q=$encoded", page)).document
        return newSearchResponseList(
            XHamsterParser.cards(doc, mainUrl).map { it.toSearchResponse() },
            XHamsterParser.hasNextPage(doc, page, mainUrl),
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val detail = XHamsterParser.detail(app.get(url, referer = "$mainUrl/").document)
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
        val detail = XHamsterParser.detail(app.get(data, referer = data).document) ?: return false
        detail.media.forEach { media ->
            callback(newExtractorLink(name, "$name ${media.quality ?: 0}p", media.url, ExtractorLinkType.VIDEO) {
                quality = media.quality ?: 0
                referer = data
            })
        }

        // The progressive MP4s stop at 720p on many videos. The HLS master carries the rest
        // (1080p, 2160p), so offer every height the MP4s don't already cover.
        var emitted = detail.media.isNotEmpty()
        val master = detail.hlsMaster
        if (master != null) {
            val covered = detail.media.mapNotNull { it.quality }.toSet()
            val playlist = app.get(master, referer = data).text
            XHamsterParser.hlsHeights(playlist).filter { it !in covered }.forEach { height ->
                val h264 = XHamsterParser.h264Variant(master, height)
                val url = if (app.get(h264, referer = data).isSuccessful) h264
                else XHamsterParser.av1Variant(master, height)
                callback(newExtractorLink(name, "$name ${height}p HLS", url, ExtractorLinkType.M3U8) {
                    quality = height
                    referer = data
                })
                emitted = true
            }
        }
        return emitted
    }

    private fun withPage(url: String, page: Int): String = when {
        page <= 1 -> url
        '?' in url -> "$url&page=$page"
        else -> "$url/$page"
    }

    private fun XHamsterParser.Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
}
