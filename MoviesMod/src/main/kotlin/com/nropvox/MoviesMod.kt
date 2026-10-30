package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
 * MoviesMod is a WordPress blog (its REST API needs a login) whose download buttons lead to modpro.blog link pages,
 * then through a SafeLink ad gate to DriveSeed, which hosts each file on R2 and behind a Google "Instant" mirror.
 * The link chain is [MoviesModSource] in `shared/moviesmodsource` (shared with OmniStream).
 */
class MoviesMod : MainAPI() {
    override var mainUrl = MoviesModSource.SITE
    override var name = "MoviesMod"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "web-series/" to "Web Series",
        "anime/" to "Anime",
        "ott/netflix/" to "Netflix",
        "ott/amazon-prime-video/" to "Amazon Prime",
        "ott/disney-plus/" to "Disney+",
        "ott/hbo-max/" to "HBO Max",
        "movies-by-genre/action/" to "Action",
        "movies-by-genre/comedy/" to "Comedy",
        "movies-by-genre/horror/" to "Horror",
        "movies-by-genre/sci-fi/" to "Sci-Fi",
        "language/korean/" to "Korean",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        // Past the last page the site answers 404 with no cards.
        val doc = app.get(url, timeout = 30L).document
        val cards = MoviesModParser.cards(doc)
        return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext = MoviesModParser.hasNext(doc))
    }

    private fun MmCard.toSearchResponse(): SearchResponse {
        val clean = MoviesModParser.cleanTitle(title).ifBlank { title }
        val year = MoviesModParser.year(title)
        return if (MoviesModParser.isSeries(title)) {
            newTvSeriesSearchResponse(clean, url, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(clean, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        // A page past the last one is a 404.
        val doc = MoviesModSource.search(query, page) ?: return newSearchResponseList(emptyList(), hasNext = false)
        return newSearchResponseList(MoviesModParser.cards(doc).map { it.toSearchResponse() }, hasNext = MoviesModParser.hasNext(doc))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 30L).document
        val rawTitle = doc.selectFirst("h1.title, h1.single-title, h1")?.text()?.trim().orEmpty()
        val details = MoviesModParser.details(doc)
        val title = details.title ?: MoviesModParser.cleanTitle(rawTitle).ifBlank { rawTitle }
        if (title.isBlank()) throw ErrorLoadingException("No title on $url")

        val sources = MoviesModSource.sources(doc)
        val episodeLists = sources.filter { MoviesModParser.isEpisodeList(it.url) }

        val response: LoadResponse = if (episodeLists.isNotEmpty()) {
            val type = if (MoviesModParser.isAnime(doc)) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, type, episodes(episodeLists))
        } else {
            if (sources.isEmpty()) throw ErrorLoadingException("No download links on $url")
            newMovieLoadResponse(title, url, TvType.Movie, MmLink(sources).toJson())
        }
        return response.apply {
            posterUrl = details.poster
            plot = details.plot
            year = details.year ?: MoviesModParser.year(rawTitle)
            tags = details.genres
            score = details.rating?.let { Score.from10(it) }
            addActors(details.actors)
            addImdbId(details.imdbId)
        }
    }

    /**
     * Each season × quality has its own episode list page, and episodes.modpro.blog answers 429 after about six
     * requests, so only one page per season is read here: the quality whose heading claims the most episodes
     * (`E06 Added`). Each episode carries every quality page of its season; [loadLinks] reads them for its gates.
     */
    private suspend fun episodes(lists: List<MmSource>): List<Episode> =
        lists.groupBy { MoviesModParser.season(it.heading) }.toSortedMap().flatMap { (season, pages) ->
            val probe = pages.maxByOrNull { MoviesModParser.addedEpisodes(it.heading) ?: 0 } ?: return@flatMap emptyList()
            val numbers = MoviesModSource.safely("episode list ${probe.url}") {
                val res = MoviesModSource.page(probe.url)
                MoviesModParser.episodes(res.document).map { it.first }.also {
                    if (it.isEmpty()) Log.w(name, "No episodes on ${probe.url}: HTTP ${res.code} '${res.document.title()}'")
                }
            }.orEmpty()
            numbers.sorted().map { number ->
                newEpisode(MmLink(pages, number).toJson()) {
                    this.season = season
                    episode = number
                    name = "Episode $number"
                }
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = tryParseJson<MmLink>(data)?.let { MoviesModSource.loadLinks(it, name, callback) } ?: false
}
