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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MissAV : MainAPI() {
    private companion object {
        const val TITLE_PREFIX = "Uncensored - "
        const val VIDEO_CARD_SELECTOR = "div.grid.grid-cols-2 > div"
        const val MAIN_PAGE_CARD_SELECTOR = "$VIDEO_CARD_SELECTOR, div.thumbnail.group"
        const val VIDEO_LINK_SELECTOR = "a[href*='/en/'], a[href*='/dm']"
        const val TITLE_SELECTOR = "div.my-2 a, div.title a, a.text-secondary"
        val ignoredTitles = setOf("Recent update", "Contact", "Support", "DMCA", "Home")
        val uncensoredPattern = Regex("uncensored[-_ ]?leak", RegexOption.IGNORE_CASE)
    }

    override var mainUrl = "https://missav.live"
    override var name = "MissAV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    private val cloudflareInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/dm169/en/weekly-hot?sort=weekly_views" to "Weekly Hot",
        "$mainUrl/dm263/en/monthly-hot?sort=views" to "Monthly Hot",
        "$mainUrl/en/new?sort=published_at" to "Newly Added",
        "$mainUrl/en/english-subtitle" to "English Subtitles",
        "$mainUrl/dm628/en/uncensored-leak" to "Uncensored Leak",
        "$mainUrl/dm150/en/fc2" to "FC2",
        "$mainUrl/dm35/en/madou" to "Madou",
        "$mainUrl/en/klive" to "K-Live",
        "$mainUrl/en/clive" to "C-Live",
        "$mainUrl/dm29/en/tokyohot" to "Tokyo Hot",
        "$mainUrl/dm1198483/en/heyzo" to "HEYZO",
        "$mainUrl/dm2469695/en/1pondo" to "1pondo",
        "$mainUrl/dm3959622/en/caribbeancom" to "Caribbeancom",
        "$mainUrl/dm48032/en/caribbeancompr" to "Caribbeancom Premium",
        "$mainUrl/dm3710098/en/10musume" to "10musume",
        "$mainUrl/dm1342558/en/pacopacomama" to "Pacopacomama",
        "$mainUrl/dm136/en/gachinco" to "Gachinco",
        "$mainUrl/dm29/en/xxxav" to "XXX-AV",
        "$mainUrl/dm24/en/marriedslash" to "Married Slash",
        "$mainUrl/dm20/en/naughty4610" to "Naughty 4610",
        "$mainUrl/dm22/en/naughty0930" to "Naughty 0930"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"

        val document = app.get(url, interceptor = cloudflareInterceptor).document

        val results = document.select(MAIN_PAGE_CARD_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst(VIDEO_LINK_SELECTOR) ?: return null
        val videoUrl = fixUrlNull(linkElement.attr("abs:href")) ?: return null
        val baseTitle = selectFirst(TITLE_SELECTOR)?.text()?.trim() ?: linkElement.text().trim()

        if (baseTitle.isBlank() || ignoredTitles.any { baseTitle.equals(it, ignoreCase = true) }) {
            return null
        }

        val isUncensoredLeak = uncensoredPattern.containsMatchIn(
            linkElement.attr("alt") + linkElement.attr("href") + outerHtml()
        )
        val title = when {
            isUncensoredLeak && !baseTitle.startsWith(
                TITLE_PREFIX,
                ignoreCase = true
            ) -> "$TITLE_PREFIX$baseTitle"

            else -> baseTitle
        }
        val image = selectFirst("img") ?: return null
        val posterUrl = fixUrlNull(
            image.attr("abs:data-src").ifEmpty { image.attr("abs:src") }
        ) ?: return null

        return newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "${mainUrl}/en/search/${query}"
        } else {
            "${mainUrl}/en/search/${query}?page=$page"
        }

        val document = app.get(url, interceptor = cloudflareInterceptor).document
        val results = document.select(VIDEO_CARD_SELECTOR).mapNotNull { it.toSearchResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cloudflareInterceptor).document

        val title = document.selectFirst("h1.text-base")?.text()?.trim() ?: return null
        val videoCode = title.substringBefore(" ")
        val posterUrl =
            fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val releaseYear = document.selectFirst("time")?.text()?.substringBefore("-")?.toIntOrNull()
        val tags = document.select("div.text-secondary:contains(genre) a").map { it.text().trim() }
        val actors = document.select("div.text-secondary:contains(actress) a")
            .map { Actor(it.text().trim()) }

        return newMovieLoadResponse(title, url, TvType.NSFW, "$videoCode:$url") {
            this.posterUrl = posterUrl
            this.year = releaseYear
            this.tags = tags
            addActors(actors)
        }
    }

    // Resolved by the Extractors plugin's MissAVPage, which JavStream shares. [data] is `code:url`.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        loadExtractor(data.substringAfter(":"), subtitleCallback) { found = true; callback(it) }
        return found
    }
}
