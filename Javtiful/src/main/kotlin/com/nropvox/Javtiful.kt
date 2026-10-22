package com.nropvox

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Javtiful : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "Javtiful"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/videos" to "Newest",
        "${mainUrl}/videos?sort=most_viewed" to "Most Viewed",
        "${mainUrl}/videos?sort=most_liked" to "Most Liked",
        "${mainUrl}/videos?sort=popular_week" to "Popular This Week",
        "${mainUrl}/videos?sort=popular_month" to "Popular This Month",
        "${mainUrl}/censored" to "Censored",
        "${mainUrl}/uncensored" to "Uncensored",
        "${mainUrl}/category/female-investigator" to "Female Investigator",
        "${mainUrl}/category/chinese-av" to "Chinese AV",
        "${mainUrl}/category/female-boss" to "Female Boss",
        "${mainUrl}/category/mature-woman" to "Mature Woman",
        "${mainUrl}/category/cosplay" to "Cosplay",
        "${mainUrl}/category/amateur" to "Amateur",
        "${mainUrl}/category/housekeeper" to "Housekeeper",
        "${mainUrl}/category/nurse" to "Nurse",
        "${mainUrl}/category/female-student" to "Female Student",
        "${mainUrl}/category/school-girls" to "School Girls",
        "${mainUrl}/category/office-lady" to "Office Lady",
        "${mainUrl}/category/sister-in-law" to "Sister-in-law",
        "${mainUrl}/category/hypnosis" to "Hypnosis",
        "${mainUrl}/category/beautiful-girl" to "Beautiful Girl",
        "${mainUrl}/category/bbw" to "BBW",
        "${mainUrl}/category/drama" to "Drama",
        "${mainUrl}/category/married-woman" to "Married Woman",
        "${mainUrl}/category/milf" to "Milf",
        "${mainUrl}/category/female-teacher" to "Female Teacher",
        "${mainUrl}/category/affair" to "Affair",
        "${mainUrl}/category/big-tits" to "Big Tits"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = if (page <= 1) emptyMap() else mapOf("page" to page.toString())
        val res = app.get(request.data, params = params).document
        val home = res.select("article.video-card:not(.video-card--partner)").mapNotNull {
            it.mainPageResults()
        }
        return newHomePageResponse(request.name, home, res.hasNextPage(page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val res = app.get("$mainUrl/search?q=$encodedQuery&page=$page").document
        val results = res.select("article.video-card:not(.video-card--partner)").mapNotNull {
            it.mainPageResults()
        }
        return newSearchResponseList(results, res.hasNextPage(page))
    }

    private fun Element.hasNextPage(page: Int): Boolean =
        select("nav.pagination a.pagination__link:not(.is-disabled)").any {
            Regex("[?&]page=(\\d+)").find(it.attr("href"))
                ?.groupValues?.get(1)?.toIntOrNull()?.let { next -> next > page } == true
        }

    private fun Element.mainPageResults(): SearchResponse? {
        val link = this.selectFirst("a.video-card__title") ?: return null
        val title = link.text().trim()
        val href = fixUrl(link.attr("href"))
        val img = this.selectFirst("img") ?: return null
        val poster = fixUrlNull(
            img.attr("data-front-lazy-src").ifBlank { img.attr("src") }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url).document
        val title = res.selectFirst("div.watch-title h1")?.text()?.trim() ?: return null
        val poster = res.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val recommendations =
            res.select("div.related-grid article.video-card:not(.video-card--partner)")
                .mapNotNull { it.mainPageResults() }

        val actorsList = res.select("a.watch-actor-card").map {
            val name = it.selectFirst("span")?.text()?.trim() ?: ""
            val image = it.selectFirst("img")?.attr("src")?.takeIf { img ->
                img.isNotEmpty() && !img.contains("profile-placeholder.png")
            }
            Actor(name, fixUrlNull(image))
        }

        val dateText =
            res.selectFirst("div.watch-detail:contains(Added on) time")?.attr("datetime")
        val year = dateText?.split("-")?.firstOrNull()?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot =
                res.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            this.year = year
            this.tags =
                res.select("div.watch-detail:contains(Categories) a, div.watch-detail:contains(Tags) a")
                    .map { it.text().trim() }
            this.recommendations = recommendations
            addActors(actorsList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        val configRaw = res.selectFirst("script#watch-config")?.data()
            ?.takeIf { it.isNotBlank() } ?: return false
        val config = runCatching {
            mapper.readValue(configRaw, WatchConfig::class.java)
        }.getOrNull() ?: return false
        val playback = config.playback ?: return false

        val sources = requestSources(playback, data)
        if (sources.isEmpty()) return false

        config.videoTitle?.substringBefore(" ")?.let { code ->
            loadExtractor("https://subtitlecat.com/?query=$code", subtitleCallback, callback)
        }

        sources.forEach { source ->
            val url = fixUrl(source.src)
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = url
                ) {
                    this.quality = source.size ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.type = if (
                        source.type?.contains("mpegurl", ignoreCase = true) == true ||
                        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                    ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }

        return true
    }

    // The watch page only carries a signed ticket; the player POSTs it to get the media URLs.
    // A stale ticket is answered with 409 and a renewTicket to retry once.
    private suspend fun requestSources(playback: Playback, pageUrl: String): List<PlayerSource> {
        var ticket = playback.ticket
        repeat(2) {
            val response = app.post(
                fixUrl(playback.endpoint),
                data = mapOf("ticket" to ticket),
                referer = pageUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            )
            val body = runCatching {
                mapper.readValue(response.text, PlaybackResponse::class.java)
            }.getOrNull() ?: return emptyList()
            if (response.code == 409 && !body.renewTicket.isNullOrBlank()) {
                ticket = body.renewTicket
                return@repeat
            }
            if (!body.success) return emptyList()
            return body.sources.orEmpty().filter { it.src.isNotBlank() }
        }
        return emptyList()
    }

    data class WatchConfig(
        val playback: Playback? = null,
        val videoTitle: String? = null
    )

    data class Playback(
        val endpoint: String,
        val ticket: String
    )

    data class PlaybackResponse(
        val success: Boolean = false,
        val sources: List<PlayerSource>? = null,
        val renewTicket: String? = null
    )

    data class PlayerSource(
        val src: String,
        val type: String? = null,
        val size: Int? = null
    )
}
