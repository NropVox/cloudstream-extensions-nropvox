package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

/**
 * movy.sx: a TMDB catalog (served by a keyless proxy) plus city-named stream resolvers whose responses are
 * encrypted with a short-lived seed, resolved by [MovySource] in `shared/movysource` (shared with OmniStream).
 */
class Movy : MainAPI() {
    override var mainUrl = "https://www.movy.sx"
    override var name = "Movy"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://db.wecollege.net/3"
    private val titleUrl = Regex("""/(movie|tv)/(\d+)""")

    override val mainPage = mainPageOf(
        "trending/all/week" to "Trending this week",
        "movie/popular" to "Popular movies",
        "tv/popular" to "Popular shows",
        "discover/movie?sort_by=vote_average.desc&vote_count.gte=300" to "Top rated movies",
        "discover/tv?sort_by=vote_average.desc&vote_count.gte=300" to "Top rated shows",
        "discover/tv?with_networks=213" to "Netflix",
        "discover/tv?with_networks=1024" to "Prime Video",
        "discover/tv?with_networks=3186" to "HBO Max",
        "discover/tv?with_networks=2739" to "Disney+",
        "discover/tv?with_networks=2552" to "Apple TV+",
        "discover/tv?with_networks=4330" to "Paramount+",
        "discover/tv?with_networks=453" to "Hulu",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if ('?' in request.data) '&' else '?'
        val result = app.get("$tmdbApi/${request.data}${separator}page=$page&language=en&include_adult=false")
            .parsedSafe<TmdbPage>()
        // Only "trending/all" tags each result; the other rows are all one type.
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = page < (result?.totalPages ?: 0)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = app.get("$tmdbApi/search/multi?query=$encoded&page=$page&language=en&include_adult=false")
            .parsedSafe<TmdbPage>()
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(null) }
        return newSearchResponseList(items, hasNext = page < (result?.totalPages ?: 0))
    }

    /** People and poster-less entries are skipped, like the site's search does. */
    private fun TmdbItem.toSearchResponse(defaultType: String?): SearchResponse? {
        val type = mediaType ?: defaultType ?: return null
        val title = title ?: name ?: return null
        val poster = image(posterPath, "w500") ?: return null
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        val url = "$mainUrl/$type/$id"
        return when (type) {
            "movie" -> newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
                score = Score.from10(voteAverage)
            }
            "tv" -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score = Score.from10(voteAverage)
            }
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id) = titleUrl.find(url)?.destructured ?: return null
        val details = app.get(
            "$tmdbApi/$type/$id?append_to_response=external_ids,credits,recommendations,videos&language=en"
        ).parsedSafe<TmdbDetails>() ?: return null
        val title = details.title ?: details.name ?: return null
        val imdbId = details.imdbId ?: details.externalIds?.imdbId
        val year = (details.releaseDate ?: details.firstAirDate)?.take(4)?.toIntOrNull()
        val actors = details.credits?.cast.orEmpty().take(30).mapNotNull { cast ->
            ActorData(Actor(cast.name ?: return@mapNotNull null, image(cast.profilePath, "w185")), roleString = cast.character)
        }
        val recommendations = details.recommendations?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
        val trailer = details.videos?.results.orEmpty()
            .firstOrNull { it.site == "YouTube" && it.type == "Trailer" && it.key != null }
            ?.let { "https://www.youtube.com/watch?v=${it.key}" }

        val load: LoadResponse = if (type == "movie") {
            val data = MovyLink(details.id, "movie", title, year, imdbId)
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                duration = details.runtime
            }
        } else {
            val data = MovyLink(details.id, "tv", title, year, imdbId, totalSeasons = details.numberOfSeasons)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes(id, details, data)) {
                duration = details.episodeRunTime?.firstOrNull()
                showStatus = when (details.status) {
                    "Returning Series", "In Production" -> ShowStatus.Ongoing
                    "Ended", "Canceled" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
        return load.apply {
            posterUrl = image(details.posterPath, "w500")
            backgroundPosterUrl = image(details.backdropPath, "original")
            plot = details.overview
            this.year = year
            tags = details.genres?.mapNotNull { it.name }
            score = Score.from10(details.voteAverage)
            this.actors = actors
            this.recommendations = recommendations
            addImdbId(imdbId)
            addTMDbId(id)
            addTrailer(trailer)
        }
    }

    /** Specials (season 0) are skipped: the resolvers only index regular seasons. */
    private suspend fun episodes(id: String, details: TmdbDetails, data: MovyLink) =
        details.seasons.orEmpty().mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }
            // TMDB allows at most 20 appended sub-requests per call.
            .chunked(20).amap { chunk ->
                val appended = chunk.joinToString(",") { "season/$it" }
                val json = app.get("$tmdbApi/tv/$id?append_to_response=$appended&language=en").text
                val seasons = try {
                    mapper.readTree(json)
                } catch (e: Exception) {
                    return@amap emptyList()
                }
                chunk.flatMap { number ->
                    val seasonData = seasons.get("season/$number")?.let { mapper.treeToValue(it, TmdbSeason::class.java) }
                    seasonData?.episodes.orEmpty().mapNotNull { episode ->
                        val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                        newEpisode(data.copy(season = number, episode = episodeNumber)) {
                            name = episode.name
                            season = number
                            this.episode = episodeNumber
                            posterUrl = image(episode.stillPath, "w300")
                            description = episode.overview
                            runTime = episode.runtime
                            score = Score.from10(episode.voteAverage)
                            addDate(episode.airDate)
                        }
                    }
                }
            }.flatten()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = MovySource.load(parseJson<MovyLink>(data), name, subtitleCallback, callback)

    private fun image(path: String?, size: String): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    data class TmdbPage(
        @JsonProperty("results") val results: List<TmdbItem>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class TmdbDetails(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeasonSummary>? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("credits") val credits: TmdbCredits? = null,
        @JsonProperty("recommendations") val recommendations: TmdbPage? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
    )

    data class TmdbGenre(@JsonProperty("name") val name: String? = null)

    data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

    data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null)

    data class TmdbCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class TmdbVideos(@JsonProperty("results") val results: List<TmdbVideo>? = null)

    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class TmdbSeasonSummary(@JsonProperty("season_number") val seasonNumber: Int? = null)

    data class TmdbSeason(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )
}
