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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    // Resolved by the Extractors plugin's JavtifulPage, which JavStream shares.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        loadExtractor(data, subtitleCallback) { found = true; callback(it) }
        return found
    }
}
