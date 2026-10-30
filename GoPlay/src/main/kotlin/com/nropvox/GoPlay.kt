package com.nropvox

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import org.jsoup.nodes.Document

/**
 * GoPlay's catalog. Its session, player decryption and single-use stream URLs are [GoPlaySource] in
 * `shared/goplaysource` (shared with OmniStream).
 */
class GoPlay : MainAPI() {
    override var mainUrl = GoPlaySource.SITE
    override var name = "GoPlay"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Home",
        "browse?type=1" to "Series",
        "browse?type=2" to "Movies",
        "browse?type=3" to "Variety",
        "browse?region=1" to "Korean",
        "browse?region=2" to "Chinese",
        "browse?region=3" to "Japanese",
        "browse?region=4" to "Thai",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isEmpty()) {
            val document = GoPlaySession.get("$mainUrl/").document
            // Each homepage section is an #indextitle followed by its #indexepisodelist.
            val lists = document.select("#indexepisodelist").mapNotNull { list ->
                val title = list.previousElementSiblings()
                    .firstOrNull { it.id() == "indextitle" }?.ownText()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val items = GoPlaySource.cards(list.select("#indexepisodes")).map { it.toSearchResult() }
                if (items.isEmpty()) null else HomePageList(title, items, true)
            }
            return newHomePageResponse(lists, hasNext = false)
        }

        val document = GoPlaySession.get("$mainUrl/${request.data}&page=$page").document
        val items = GoPlaySource.cards(document.select(".browse_entry")).map { it.toSearchResult() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, true)),
            hasNext = document.lastPage() > page
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // GoPlay returns every match on one page.
        return newSearchResponseList(GoPlaySource.search(query).map { it.toSearchResult() }, hasNext = false)
    }

    private fun GoPlayCard.toSearchResult(): SearchResponse =
        newTvSeriesSearchResponse(title, url, TvType.AsianDrama) { posterUrl = poster }

    private fun Document.lastPage(): Int =
        select("#search_page_select option").mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull() ?: 1

    override suspend fun load(url: String): LoadResponse? {
        val document = GoPlaySession.get(url).document
        val info = document.selectFirst("#infotitle") ?: return null
        val title = GoPlaySource.stripOriginalTitle(info.ownText().trim()).ifEmpty { return null }
        val plot = document.selectFirst("#desctext")?.text()?.trim()

        val actors = document.select(".artists_entry_main").mapNotNull { entry ->
            val name = entry.selectFirst(".artists_entry_name a")?.attr("title")?.trim()
                ?.ifEmpty { null } ?: return@mapNotNull null
            ActorData(
                Actor(name, fixUrlNull(entry.selectFirst("img.artists_img")?.attr("src"))),
                roleString = entry.selectFirst(".artists_entry_character")?.text()?.trim()?.ifEmpty { null }
            )
        }

        val episodeLinks = document.select("#episodes a[href]")
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(episodeLinks.firstOrNull()?.selectFirst("img")?.attr("src"))

        // Movies show "Feature Film" instead of an episode count and link only to themselves.
        val isMovie = document.selectFirst("#episodecount")?.text()?.trim()?.toIntOrNull() == null
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.actors = actors
            }
        }

        val episodes = episodeLinks.mapNotNull { link ->
            val href = link.attr("href")
            val number = GoPlaySource.episodeNumber.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(fixUrl(href)) {
                episode = number
                name = "Episode $number"
                posterUrl = fixUrlNull(link.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            posterUrl = poster
            this.plot = plot
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = GoPlaySource.loadLinks(data, name, name, subtitleCallback, callback)

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = GoPlaySource.interceptor
}
