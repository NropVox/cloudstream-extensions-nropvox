package com.nropvox

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import java.net.URLEncoder

/**
 * OmniStream: a TMDB catalog (movies, shows, anime, Asian drama) whose links come from every TMDB-keyed source in
 * this repo at once (Dulo, Movy, 7Movies, TonkaCine, Cno.to, plus KatDrama for Asian titles), the sites it finds the
 * title on by search ([OmniStreamSites]: Vegamovies, MoviesMod, 4KHDHub, plus KissKH and GoPlay for Asian titles),
 * and for anime also the AniList-keyed AnimoTv, Ani.pm, Reanime and AnimeX after a TMDB → AniList lookup. Their
 * resolvers are compiled in from `shared/`, and [OmniLinkRace] runs them in parallel against a deadline. Titles with
 * an IMDb id also get Vadapav's files and OpenSubtitles' subtitles from their Stremio addons ([OmniStremio]).
 */
class OmniStream : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    override var name = "OmniStream"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "trending/all/week" to "Trending this week",
        "movie/popular" to "Popular movies",
        "tv/popular" to "Popular shows",
        "discover/tv?with_genres=16&with_origin_country=JP&sort_by=popularity.desc" to "Anime",
        "discover/movie?with_genres=16&with_origin_country=JP&sort_by=popularity.desc" to "Anime movies",
        "discover/tv?with_origin_country=KR&without_genres=$NOT_DRAMA&sort_by=popularity.desc&vote_count.gte=20" to "K-Drama",
        "discover/tv?with_origin_country=CN|TW&without_genres=$NOT_DRAMA&sort_by=popularity.desc&vote_count.gte=10" to "C-Drama",
        "movie/now_playing?region=US" to "In theaters",
        "movie/top_rated" to "Top rated movies",
        "tv/top_rated" to "Top rated shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if ('?' in request.data) '&' else '?'
        val result = OmniTmdb.get("${request.data}${separator}page=$page")?.let { tryParseJson<OmniTmdbPage>(it) }
        // Only "trending/all" tags each result; the other rows are all one type.
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = page < (result?.totalPages ?: 0))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = OmniTmdb.get("search/multi?query=$encoded&page=$page")?.let { tryParseJson<OmniTmdbPage>(it) }
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(null) }
        return newSearchResponseList(items, hasNext = page < (result?.totalPages ?: 0))
    }

    /** People and poster-less entries are skipped. */
    private fun OmniTmdbItem.toSearchResponse(defaultType: String?): SearchResponse? {
        val type = mediaType ?: defaultType ?: return null
        val id = id ?: return null
        val title = title ?: name ?: return null
        val poster = image(posterPath, "w500") ?: return null
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        val kind = OmniStreamParser.kind(genreIds, originCountry, originalLanguage)
        val url = "$mainUrl/$type/$id"
        return when (type) {
            "movie" -> newMovieSearchResponse(title, url, if (kind == OmniStreamParser.KIND_ANIME) TvType.AnimeMovie else TvType.Movie) {
                posterUrl = poster
                this.year = year
                score = Score.from10(voteAverage)
            }
            "tv" -> newTvSeriesSearchResponse(title, url, tvType(kind)) {
                posterUrl = poster
                this.year = year
                score = Score.from10(voteAverage)
            }
            else -> null
        }
    }

    private fun tvType(kind: String) = when (kind) {
        OmniStreamParser.KIND_ANIME -> TvType.Anime
        OmniStreamParser.KIND_ASIAN -> TvType.AsianDrama
        else -> TvType.TvSeries
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id) = OmniStreamParser.titleRef(url) ?: return null
        val details = OmniTmdb.get("$type/$id?append_to_response=external_ids,credits,recommendations,videos")
            ?.let { tryParseJson<OmniTmdbDetails>(it) } ?: return null
        val title = details.title ?: details.name ?: return null
        val imdbId = details.imdbId ?: details.externalIds?.imdbId
        val year = (details.releaseDate ?: details.firstAirDate)?.take(4)?.toIntOrNull()
        val kind = OmniStreamParser.kind(details.genres?.mapNotNull { it.id }, details.originCountry, details.originalLanguage)
        val actors = details.credits?.cast.orEmpty().take(30).mapNotNull { cast ->
            ActorData(Actor(cast.name ?: return@mapNotNull null, image(cast.profilePath, "w185")), roleString = cast.character)
        }
        val recommendations = details.recommendations?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
        val trailer = details.videos?.results.orEmpty()
            .firstOrNull { it.site == "YouTube" && it.type == "Trailer" && it.key != null }
            ?.let { "https://www.youtube.com/watch?v=${it.key}" }
        val link = OmniLink(
            id = details.id,
            type = type,
            title = title,
            originalTitle = (details.originalTitle ?: details.originalName)?.takeIf { it != title },
            year = year,
            imdbId = imdbId,
            totalSeasons = details.numberOfSeasons,
            kind = kind,
            airDate = details.releaseDate,
        )
        // The page is open, so the lookups the sources need before their first link start now, not on play.
        OmniStreamSites.warm(link)
        if (kind == OmniStreamParser.KIND_ANIME) OmniStreamAnime.warm(link)

        val load: LoadResponse = if (type == "movie") {
            val movieType = if (kind == OmniStreamParser.KIND_ANIME) TvType.AnimeMovie else TvType.Movie
            newMovieLoadResponse(title, url, movieType, link) {
                duration = details.runtime
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType(kind), episodes(id, details, link)) {
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
            addTMDbId(id.toString())
            addTrailer(trailer)
        }
    }

    private suspend fun episodes(id: Int, details: OmniTmdbDetails, link: OmniLink) =
        OmniTmdb.seasons(id, OmniTmdb.seasonNumbers(details)).also { seasons ->
            if (link.kind == OmniStreamParser.KIND_ANIME) OmniStreamAnime.showEpisodes[id] = OmniTmdb.airEpisodes(seasons)
        }.flatMap { (number, episodes) ->
            episodes.mapNotNull { episode ->
                val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                newEpisode(link.copy(season = number, episode = episodeNumber, airDate = episode.airDate)) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<OmniLink>(data)
        if (link.type == "tv" && (link.season == null || link.episode == null)) return false
        if (!OmniStreamParser.aired(link.year, link.airDate, (System.currentTimeMillis() / 86_400_000L).toInt())) {
            Log.i(name, "${link.title}: not aired yet, no sources asked")
            return false
        }
        return OmniLinkRace(name, subtitleCallback, callback).run(sources(link), unprobed = setOf(GOPLAY))
    }

    /** Every source that can serve this title, each under the name it labels its links with. */
    private fun sources(link: OmniLink): List<Pair<String, OmniSource>> {
        val sources = tmdbSources { link }.toMutableList()
        if (link.kind == OmniStreamParser.KIND_ASIAN) {
            sources += "KatDrama" to { subs, links ->
                KatDramaSource.load(KatLink(link.id, link.type, link.season ?: 1, link.episode ?: 1, link.title, link.year), "KatDrama", subs, links)
            }
            sources += "KissKH" to { subs, links -> OmniStreamSites.kissKH(link, "KissKH", subs, links) }
            sources += GOPLAY to { subs, links -> OmniStreamSites.goPlay(link, name, GOPLAY, subs, links) }
        }
        // The AniList sources all wait for the same lookup, which never holds up the TMDB-keyed ones.
        if (link.kind == OmniStreamParser.KIND_ANIME) sources += OmniStreamAnime.sources(once { OmniStreamAnime.target(link, OmniTmdb::episodesOf) })
        return sources
    }

    /** GoPlay links carry this API's name as their source, so the app asks here for what renews their spent URLs. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = GoPlaySource.interceptor

    private fun image(path: String?, size: String): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    companion object {
        private const val GOPLAY = "GoPlay"

        /** Animation, reality, talk and news: what a drama row should leave out. */
        private const val NOT_DRAMA = "16,10764,10767,10763"

        /**
         * The TMDB-keyed sources, the title-searched sites and the IMDb-keyed Stremio addons, each asking [find] for
         * the title (OmniAnime maps its AniList episode to TMDB there; a null skips them all).
         */
        internal fun tmdbSources(find: suspend () -> OmniLink?): List<Pair<String, OmniSource>> = listOf(
            "Dulo" to { subs, links ->
                find()?.let { link ->
                    DuloSource.load(DuloLink(link.id, link.type, link.title, link.year, link.imdbId, link.season, link.episode), "Dulo", subs, links)
                }
            },
            "Movy" to { subs, links ->
                find()?.let { link ->
                    MovySource.load(MovyLink(link.id, link.type, link.title, link.year, link.imdbId, link.totalSeasons, link.season, link.episode),
                        "Movy", subs, links)
                }
            },
            "7Movies" to { subs, links ->
                find()?.let { link -> SevenMoviesSource.load(SevenLink(link.id, link.type, link.season, link.episode), "7Movies", subs, links) }
            },
            "TonkaCine" to { subs, links ->
                find()?.let { link ->
                    TonkaCineResolver.resolve(TonkaLink(link.id, link.type, link.season, link.episode), TonkaCineResolver.SITE, "TonkaCine", subs, links)
                }
            },
            "Cno.to" to { subs, links ->
                find()?.let { link -> CnoToSource.load(CnoLink(link.type, link.id, link.season, link.episode), "Cno.to", subs, links) }
            },
            "Vegamovies" to { _, links -> find()?.let { link -> OmniStreamSites.vegamovies(link, "Vegamovies", links) } },
            "MoviesMod" to { _, links -> find()?.let { link -> OmniStreamSites.moviesMod(link, "MoviesMod", links) } },
            "4KHDHub" to { _, links -> find()?.let { link -> OmniStreamSites.fourKHDHub(link, "4KHDHub", links) } },
            // Both skip titles without an IMDb id.
            "Vadapav" to { _, links -> find()?.let { link -> OmniStremio.streams(OmniStremio.VADAPAV, link, "Vadapav", links) } },
            "OpenSubtitles" to { subs, _ -> find()?.let { link -> OmniStremio.subtitles(OmniStremio.OPENSUBTITLES, link, subs) } },
        )
    }
}

/** [block] run once, by whichever caller asks first; the others wait for its answer. A failed run is retried. */
internal fun <T> once(block: suspend () -> T): suspend () -> T {
    val lock = Mutex()
    var done = false
    var value: T? = null
    return {
        lock.withLock {
            if (!done) {
                value = block()
                done = true
            }
            @Suppress("UNCHECKED_CAST")
            value as T
        }
    }
}
