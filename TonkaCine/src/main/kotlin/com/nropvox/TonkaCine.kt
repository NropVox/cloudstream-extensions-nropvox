package com.nropvox

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class TonkaCine : MainAPI() {
    override var mainUrl = "https://multi.tonkacine.watch"
    override var name = "TonkaCine"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val api get() = "$mainUrl/tmdb"
    private val titlePath = Regex("""/(?:watch/)?(movie|tv)/(\d+)""")

    override val mainPage = mainPageOf(
        "trending/all/week" to "Trending this week",
        "movie/popular" to "Popular movies",
        "tv/popular" to "Popular series",
        "movie/now_playing" to "Now playing",
        "tv/on_the_air" to "Airing series",
        "movie/top_rated" to "Top rated movies",
        "tv/top_rated" to "Top rated series",
        "discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime series",
        "discover/movie?with_genres=16&with_original_language=ja&sort_by=popularity.desc" to "Anime movies",
        "discover/tv?with_genres=16&with_original_language=en&sort_by=popularity.desc" to "Cartoons",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if ('?' in request.data) '&' else '?'
        val result = get<TonkaPage>("$api/${request.data}${separator}language=en-US&page=$page&include_adult=false")
        val fallback = when {
            request.data.contains("/all/") -> null
            request.data.startsWith("tv/") || request.data.startsWith("discover/tv") -> "tv"
            else -> "movie"
        }
        return newHomePageResponse(HomePageList(request.name,
            result.results.orEmpty().mapNotNull { it.toSearchResponse(fallback) }),
            hasNext = page < (result.totalPages ?: 0))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = get<TonkaPage>("$api/search/multi?query=$encoded&page=$page&language=en-US&include_adult=false")
        return newSearchResponseList(result.results.orEmpty().mapNotNull { it.toSearchResponse(null) },
            hasNext = page < (result.totalPages ?: 0))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    private fun TonkaItem.toSearchResponse(fallback: String?): SearchResponse? {
        val id = id?.takeIf { it > 0 } ?: return null
        val title = title ?: name ?: return null
        val poster = image(posterPath, "w500") ?: return null
        val item = this
        val type = mediaType ?: fallback
        val url = "$mainUrl/watch/$type/$id"
        return when (type) {
            "movie" -> newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                year = item.releaseDate?.take(4)?.toIntOrNull()
                score = Score.from10(item.voteAverage)
            }
            "tv" -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                year = item.firstAirDate?.take(4)?.toIntOrNull()
                score = Score.from10(item.voteAverage)
            }
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id) = titlePath.find(url)?.destructured ?: return null
        val details = get<TonkaDetails>("$api/$type/$id?language=en-US&append_to_response=credits,recommendations,videos,external_ids")
        val title = details.title ?: details.name ?: return null
        val data = TonkaLink(details.id, type)
        val canonicalUrl = "$mainUrl/watch/$type/$id"
        val response = if (type == "movie") {
            newMovieLoadResponse(title, canonicalUrl, TvType.Movie, data.toJson()) {
                duration = details.runtime
            }
        } else {
            // The watch page defaults season 0 to 1, so only regular seasons are playable.
            val episodes = details.seasons.orEmpty().mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }
                .distinct().sorted().chunked(4).flatMap { chunk ->
                    chunk.amap { season ->
                        get<TonkaSeason>("$api/tv/$id/season/$season?language=en-US").episodes.orEmpty().mapNotNull { ep ->
                            val number = ep.episodeNumber ?: return@mapNotNull null
                            newEpisode(data.copy(season = season, episode = number).toJson()) {
                                name = ep.name
                                this.season = season
                                episode = number
                                posterUrl = image(ep.stillPath, "w300")
                                description = ep.overview
                                runTime = ep.runtime
                                score = Score.from10(ep.voteAverage)
                                addDate(ep.airDate)
                            }
                        }
                    }.flatten()
                }
            newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                duration = details.episodeRunTime?.firstOrNull()
                showStatus = when (details.status) {
                    "Returning Series", "In Production" -> ShowStatus.Ongoing
                    "Ended", "Canceled" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
        return response.apply {
            posterUrl = image(details.posterPath, "w500")
            backgroundPosterUrl = image(details.backdropPath, "original")
            plot = details.overview
            year = (details.releaseDate ?: details.firstAirDate)?.take(4)?.toIntOrNull()
            score = Score.from10(details.voteAverage)
            tags = details.genres.orEmpty().mapNotNull { it.name }
            actors = details.credits?.cast.orEmpty().take(30).mapNotNull { cast ->
                ActorData(Actor(cast.name ?: return@mapNotNull null, image(cast.profilePath, "w185")), roleString = cast.character)
            }
            recommendations = details.recommendations?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
            addTMDbId(id)
            addImdbId(details.imdbId ?: details.externalIds?.imdbId)
            addTrailer(details.videos?.results.orEmpty().firstOrNull {
                it.site == "YouTube" && it.type == "Trailer" && !it.key.isNullOrBlank()
            }?.let { "https://www.youtube.com/watch?v=${it.key}" })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = tryParseJson<TonkaLink>(data) ?: return false
        if (link.id <= 0 || link.type !in setOf("movie", "tv") ||
            (link.type == "tv" && ((link.season ?: 0) < 1 || (link.episode ?: 0) < 1))) return false
        return TonkaCineResolver.resolve(link, mainUrl, name, subtitleCallback, callback)
    }

    private suspend inline fun <reified T : Any> get(url: String): T =
        app.get(url, timeout = 30L).parsedSafe<T>()
            ?: throw ErrorLoadingException("Could not load TonkaCine metadata")

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }
}
