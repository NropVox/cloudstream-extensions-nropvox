package com.nropvox

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Server-rendered PHP site; every file is a HubCloud drive behind an encoded redirector, resolved by
 * [FourKHDHubSource] in `shared/fourkhdhubsource` (shared with OmniStream).
 */
class FourKHDHub : MainAPI() {
    override var mainUrl = FourKHDHubSource.SITE
    override var name = "4KHDHub"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "category/movies/" to "Movies",
        "category/series/" to "Series",
        "category/anime/" to "Anime",
        "category/2160p-HDR/" to "4K HDR",
        "category/netflix/" to "Netflix",
        "category/amazon_prime_video/" to "Amazon Prime",
        "category/disney/" to "Disney+",
        "category/hbo_max/" to "HBO Max",
        "category/apple_tv/" to "Apple TV+",
        "category/hindi-movies/" to "Hindi Movies",
        "category/korean-series/" to "K-Drama",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val doc = app.get(url, timeout = 30L).document
        val cards = FourKHDHubParser.cards(doc, mainUrl)
        return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext = FourKHDHubParser.hasNext(doc))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val (cards, hasNext) = FourKHDHubSource.search(query, page)
        return newSearchResponseList(cards.map { it.toSearchResponse() }, hasNext = hasNext)
    }

    private fun FkCard.toSearchResponse(): SearchResponse = if (series) {
        newTvSeriesSearchResponse(title, url, if (anime) TvType.Anime else TvType.TvSeries) {
            posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    } else {
        newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, timeout = 30L)
        val doc = response.document
        val title = FourKHDHubParser.title(doc) ?: throw ErrorLoadingException("No title on $url")
        val meta = FourKHDHubParser.metadata(doc)

        val load: LoadResponse = if (FourKHDHubParser.isSeries(url)) {
            // Each season has one block per quality; an episode plays from every block that lists it.
            val episodes = FourKHDHubParser.episodes(doc)
                .groupBy { it.season to it.episode }
                .toSortedMap(compareBy({ it.first }, { it.second }))
                .map { (key, releases) ->
                    newEpisode(FkLink(releases.map { it.file }).toJson()) {
                        season = key.first
                        episode = key.second
                        name = "Episode ${key.second}"
                    }
                }
            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes listed on $url")
            val type = if (FourKHDHubParser.isAnime(doc)) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, type, episodes)
        } else {
            val files = FourKHDHubParser.movieFiles(doc)
            if (files.isEmpty()) throw ErrorLoadingException("No download links on $url")
            newMovieLoadResponse(title, url, TvType.Movie, FkLink(files).toJson()) {
                addTMDbId(FourKHDHubParser.tmdbId(response.text)?.toString())
            }
        }
        return load.apply {
            posterUrl = FourKHDHubParser.poster(doc)
            plot = FourKHDHubParser.plot(doc)
            year = FourKHDHubParser.year(doc)
            tags = FourKHDHubParser.genres(doc)
            addActors(FourKHDHubParser.actors(meta["Stars"]).map { (actor, role) -> Actor(actor) to role })
            addTrailer(FourKHDHubParser.trailer(doc))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = tryParseJson<FkLink>(data)?.let { FourKHDHubSource.loadLinks(it, name, callback) } ?: false
}
