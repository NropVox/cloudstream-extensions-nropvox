package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
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
import com.nropvox.VegamoviesParser.Kind

class Vegamovies : MainAPI() {
    override var mainUrl = VegamoviesSource.SITE
    override var name = "Vegamovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "web-series/netflix/" to "Netflix",
        "web-series/amazon-prime-video/" to "Amazon Prime",
        "web-series/apple-tv/" to "Apple TV+",
        "anime-series/" to "Anime Series",
        "korean-series/" to "K-Drama",
        "wwe-show/" to "WWE Shows",
        "movies-by-quality/2160p/" to "2160p 4K",
        "movies-by-genres/action/" to "Action",
        "movies-by-genres/comedy/" to "Comedy",
        "movies-by-genres/horror/" to "Horror",
        "movies-by-genres/sci-fi/" to "Sci-Fi",
        "movies-by-genres/thriller/" to "Thriller",
        "movies-by-genres/animation/" to "Animation",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        // Past the last page the site answers 404 with no cards.
        val cards = VegamoviesParser.cards(app.get(url, timeout = 30L).document)
        return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext = cards.isNotEmpty())
    }

    private fun VgCard.toSearchResponse(): SearchResponse =
        searchResponse(url, title, poster, Score.from10(rating), anime = title.contains("Anime", true))

    private fun searchResponse(url: String, rawTitle: String, poster: String?, score: Score?, anime: Boolean): SearchResponse {
        val title = VegamoviesParser.cleanTitle(rawTitle).ifBlank { rawTitle }
        val year = VegamoviesParser.year(rawTitle)
        return if (VegamoviesParser.isSeries(rawTitle)) {
            newTvSeriesSearchResponse(title, url, if (anime) TvType.Anime else TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
                this.score = score
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val result = VegamoviesSource.search(query, page) ?: throw ErrorLoadingException("Could not search $name")
        val items = result.hits.orEmpty().mapNotNull { hit ->
            val doc = hit.document ?: return@mapNotNull null
            val permalink = doc.permalink ?: return@mapNotNull null
            val title = doc.title ?: return@mapNotNull null
            searchResponse("$mainUrl$permalink", title, doc.poster, null, anime = doc.category.orEmpty().contains("Anime Series"))
        }
        return newSearchResponseList(items, hasNext = items.isNotEmpty() && page * VegamoviesSource.SEARCH_PAGE_SIZE < (result.found ?: 0))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 30L).document
        val rawTitle = doc.selectFirst("h1.post-title")?.text()?.trim() ?: return null
        val title = VegamoviesParser.cleanTitle(rawTitle).ifBlank { rawTitle }
        val groups = VegamoviesParser.groups(doc)
        val ld = VegamoviesParser.movieLd(doc)
        val poster = ld?.get("image")?.asText()?.takeIf { it.isNotBlank() }

        val response: LoadResponse = if (groups.any { it.season != null } || VegamoviesParser.isSeries(rawTitle)) {
            val type = if (VegamoviesParser.isAnime(doc)) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, type, episodes(groups))
        } else {
            val link = VgLink(groups.flatMap { VegamoviesSource.pagesOf(it) })
            newMovieLoadResponse(title, url, TvType.Movie, link.toJson())
        }
        return response.apply {
            posterUrl = poster
            backgroundPosterUrl = poster?.replace("/w500/", "/original/")
            plot = VegamoviesParser.plot(doc)
            year = VegamoviesParser.year(rawTitle)
            score = Score.from10(VegamoviesParser.rating(ld))
            tags = VegamoviesParser.genres(doc)
            addImdbId(VegamoviesParser.imdbId(doc))
        }
    }

    // One nexdrive page per season part names its episodes; every quality of that part can then play each one.
    private suspend fun episodes(groups: List<VgGroup>): List<Episode> {
        // A series page with no season heading is one season.
        val parts = groups.groupBy { it.part ?: (1 to 0) }.toSortedMap(compareBy({ it.first }, { it.second }))
        return parts.entries.chunked(4).flatMap { chunk ->
            chunk.amap { (key, partGroups) ->
                val pages = partGroups.flatMap { VegamoviesSource.pagesOf(it) }
                val probe = listOf(Kind.GDIRECT, Kind.EPISODES, Kind.VCLOUD, Kind.OTHER).firstNotNullOfOrNull { kind ->
                    pages.firstOrNull { VegamoviesParser.kind(it.label) == kind }
                } ?: return@amap emptyList()
                val numbers = safely(name, "episode list") {
                    VegamoviesParser.entries(app.get(probe.url, timeout = 30L).document).mapNotNull { it.episode }.distinct().sorted()
                }.orEmpty()
                numbers.map { number ->
                    newEpisode(VgLink(pages, number).toJson()) {
                        this.season = key.first
                        this.episode = number
                        name = "Episode $number"
                    }
                }
            }.flatten()
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = tryParseJson<VgLink>(data)?.let { VegamoviesSource.loadLinks(it, name, callback) } ?: false
}
