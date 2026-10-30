package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * kisskh.ovh: an Angular SPA over its own JSON API (`/api/DramaList/...`). Streams are the site's HLS on
 * cdnvideo11.shop with no headers; the episode and subtitle endpoints need a `kkey` computed from the episode id.
 * Those calls are [KissKHSource] in `shared/kisskhsource` (shared with OmniStream).
 */
class KissKH : MainAPI() {
    override var mainUrl = KissKHSource.SITE
    override var name = "KissKH"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.Anime, TvType.TvSeries)

    private val api get() = "$mainUrl/api/DramaList"

    // `List?...` rows are paged (10 per page); the others are fixed 20-item shelves of the home page.
    override val mainPage = mainPageOf(
        "List?type=0&sub=0&country=0&status=0&order=2" to "Latest updates",
        "List?type=0&sub=0&country=2&status=0&order=1" to "Popular K-Drama",
        "List?type=0&sub=0&country=1&status=0&order=1" to "Popular C-Drama",
        "TopRating?ispc=false" to "Top rated",
        "MostView?ispc=false&c=1" to "Most viewed",
        "List?type=2&sub=0&country=0&status=0&order=2" to "Movies",
        "List?type=3&sub=0&country=0&status=0&order=2" to "Anime",
        "List?type=4&sub=0&country=0&status=0&order=2" to "Hollywood",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.startsWith("List?")) {
            val items = if (page > 1) emptyArray() else app.get("$api/${request.data}").parsedSafe<Array<KissCard>>().orEmpty()
            return newHomePageResponse(HomePageList(request.name, items.mapNotNull { it.toSearchResponse() }), hasNext = false)
        }
        val result = app.get("$api/${request.data}&page=$page").parsedSafe<KissPage>()
        val items = result?.data.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = result != null && KissKHParser.hasNext(result))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    /** The search endpoint returns every match at once. */
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1 || query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val items = KissKHSource.search(query)
        return newSearchResponseList(items.mapNotNull { it.toSearchResponse() }, hasNext = false)
    }

    private fun KissCard.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return newTvSeriesSearchResponse(title, KissKHParser.dramaUrl(mainUrl, title, id), TvType.AsianDrama) {
            posterUrl = thumbnail
            year = KissKHParser.yearOf(title)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = KissKHParser.dramaId(url) ?: throw ErrorLoadingException("No drama id in $url")
        val drama = KissKHSource.drama(id)
            ?: throw ErrorLoadingException("kisskh returned no details for $id")
        val title = drama.title?.trim().orEmpty().ifEmpty { "kisskh $id" }
        val pageUrl = KissKHParser.dramaUrl(mainUrl, title, id)
        val episodes = drama.episodes.reversed().mapNotNull { episode ->
            val episodeId = episode.id ?: return@mapNotNull null
            val number = episode.number ?: return@mapNotNull null
            val label = KissKHParser.episodeNumber(number)
            newEpisode(KissKHParser.episodeUrl(mainUrl, title, id, label, episodeId)) {
                name = "Episode $label"
                this.episode = number.toInt().takeIf { number % 1.0 == 0.0 }
            }
        }

        val response = if (drama.type == "Movie" && episodes.size == 1) {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, episodes.first().data)
        } else {
            val type = when (drama.type) {
                "Anime" -> TvType.Anime
                "Hollywood" -> TvType.TvSeries
                else -> TvType.AsianDrama
            }
            newTvSeriesLoadResponse(title, pageUrl, type, episodes) {
                showStatus = when (drama.status) {
                    "Ongoing", "Upcoming" -> ShowStatus.Ongoing
                    "Completed" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
        return response.apply {
            posterUrl = drama.thumbnail
            plot = drama.description?.trim()
            year = KissKHParser.yearOf(title)
            tags = listOfNotNull(drama.country, drama.type)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = KissKHParser.episodeId(data) ?: return false
        return KissKHSource.loadEpisode(episodeId, name, subtitleCallback, callback)
    }
}
