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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * TMDB through the public API with a site key; when that key is refused (or the API is unreachable), the keyless
     * TMDB mirror Movy uses. Null for a missing title. Answers are kept for [TMDB_TTL_MS], so reopening a title or
     * playing its episodes doesn't ask again.
     */
    private suspend fun tmdb(path: String): String? {
        val separator = if ('?' in path) '&' else '?'
        val query = "$path${separator}language=en-US&include_adult=false"
        val now = System.currentTimeMillis()
        tmdbCache[query]?.let { (at, body) -> if (now - at < TMDB_TTL_MS) return body }
        return fetchTmdb(query)?.also {
            if (tmdbCache.size >= TMDB_CACHE_SIZE) tmdbCache.clear()
            tmdbCache[query] = now to it
        }
    }

    private suspend fun fetchTmdb(query: String): String? {
        if (!keyRefused) {
            try {
                val response = app.get("$TMDB_API/$query&api_key=$TMDB_KEY", timeout = 20L)
                if (response.isSuccessful) return response.text
                if (response.code != 401 && response.code != 403 && response.code != 429) return null
                if (response.code != 429) keyRefused = true
                Log.w(name, "TMDB key: HTTP ${response.code}, using the mirror")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "TMDB: ${e.message}, trying the mirror")
            }
        }
        return try {
            app.get("$TMDB_MIRROR/$query", timeout = 20L).takeIf { it.isSuccessful }?.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "TMDB mirror: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if ('?' in request.data) '&' else '?'
        val result = tmdb("${request.data}${separator}page=$page")?.let { tryParseJson<OmniTmdbPage>(it) }
        // Only "trending/all" tags each result; the other rows are all one type.
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = page < (result?.totalPages ?: 0))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = tmdb("search/multi?query=$encoded&page=$page")?.let { tryParseJson<OmniTmdbPage>(it) }
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
        val details = tmdb("$type/$id?append_to_response=external_ids,credits,recommendations,videos")
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

    /** Specials (season 0) are skipped: the sources only index regular seasons. */
    private suspend fun episodes(id: Int, details: OmniTmdbDetails, link: OmniLink) =
        seasons(id, details.seasons.orEmpty().mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }).also { seasons ->
            if (link.kind == OmniStreamParser.KIND_ANIME) OmniStreamAnime.showEpisodes[id] = airEpisodes(seasons)
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

    /** Episodes of the given seasons, in season order; TMDB allows at most 20 appended sub-requests per call. */
    private suspend fun seasons(id: Int, numbers: List<Int>): List<Pair<Int, List<OmniTmdbEpisode>>> =
        numbers.chunked(20).amap { chunk ->
            val appended = chunk.joinToString(",") { "season/$it" }
            val seasons = try {
                tmdb("tv/$id?append_to_response=$appended")?.let { mapper.readTree(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@amap emptyList()
            chunk.map { number ->
                val season = seasons.get("season/$number")?.let { mapper.treeToValue(it, OmniTmdbSeason::class.java) }
                number to season?.episodes.orEmpty()
            }
        }.flatten()

    private fun airEpisodes(seasons: List<Pair<Int, List<OmniTmdbEpisode>>>) = seasons.flatMap { (number, episodes) ->
        episodes.mapNotNull { episode -> episode.episodeNumber?.let { OmniStreamParser.AirEpisode(number, it, episode.airDate) } }
    }

    /** Used when an anime episode is played without `load` having run in this session. */
    private suspend fun fetchAirEpisodes(id: Int): List<OmniStreamParser.AirEpisode> {
        val details = tmdb("tv/$id")?.let { tryParseJson<OmniTmdbDetails>(it) } ?: return emptyList()
        return airEpisodes(seasons(id, details.seasons.orEmpty().mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }))
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
        val season = link.season
        val episode = link.episode
        val sources = mutableListOf<Pair<String, OmniSource>>(
            "Dulo" to { subs, links ->
                DuloSource.load(DuloLink(link.id, link.type, link.title, link.year, link.imdbId, season, episode), "Dulo", subs, links)
            },
            "Movy" to { subs, links ->
                MovySource.load(MovyLink(link.id, link.type, link.title, link.year, link.imdbId, link.totalSeasons, season, episode),
                    "Movy", subs, links)
            },
            "7Movies" to { subs, links ->
                SevenMoviesSource.load(SevenLink(link.id, link.type, season, episode), "7Movies", subs, links)
            },
            "TonkaCine" to { subs, links ->
                TonkaCineResolver.resolve(TonkaLink(link.id, link.type, season, episode), TonkaCineResolver.SITE, "TonkaCine", subs, links)
            },
            "Cno.to" to { subs, links ->
                CnoToSource.load(CnoLink(link.type, link.id, season, episode), "Cno.to", subs, links)
            },
            "Vegamovies" to { _, links -> OmniStreamSites.vegamovies(link, "Vegamovies", links) },
            "MoviesMod" to { _, links -> OmniStreamSites.moviesMod(link, "MoviesMod", links) },
            "4KHDHub" to { _, links -> OmniStreamSites.fourKHDHub(link, "4KHDHub", links) },
        )
        if (link.imdbId != null) {
            sources += "Vadapav" to { _, links -> OmniStremio.streams(OmniStremio.VADAPAV, link, "Vadapav", links) }
            sources += "OpenSubtitles" to { subs, _ -> OmniStremio.subtitles(OmniStremio.OPENSUBTITLES, link, subs) }
        }
        if (link.kind == OmniStreamParser.KIND_ASIAN) {
            sources += "KatDrama" to { subs, links ->
                KatDramaSource.load(KatLink(link.id, link.type, season ?: 1, episode ?: 1, link.title, link.year), "KatDrama", subs, links)
            }
            sources += "KissKH" to { subs, links -> OmniStreamSites.kissKH(link, "KissKH", subs, links) }
            sources += GOPLAY to { subs, links -> OmniStreamSites.goPlay(link, name, GOPLAY, subs, links) }
        }
        if (link.kind == OmniStreamParser.KIND_ANIME) {
            // Both AniList sources wait for the same lookup, which never holds up the TMDB-keyed ones.
            val lock = Mutex()
            var looked = false
            var target: OmniStreamParser.AniTarget? = null
            suspend fun target() = lock.withLock {
                if (!looked) {
                    target = OmniStreamAnime.target(link, ::fetchAirEpisodes)
                    looked = true
                }
                target
            }
            sources += "AnimoTv" to { subs, links ->
                target()?.let { found ->
                    listOf("sub", "dub").amap { lang -> AnimoStreamSource.load(found.anilistId, found.episode, lang, "AnimoTv", subs, links) }
                }
            }
            sources += "Ani.pm" to { subs, links ->
                target()?.let { found ->
                    listOf("sub", "dub").amap { lang ->
                        AniPmSource.load(ApLinkData(AniPmParser.ANILIST, found.anilistId.toString(), found.episode, lang), "Ani.pm", subs, links)
                    }
                }
            }
            sources += "Reanime" to { subs, links ->
                target()?.let { found -> ReanimeSource.load(found.anilistId, 0, found.episode, listOf("sub", "dub"), "Reanime", subs, links) }
            }
            sources += "AnimeX" to { subs, links ->
                target()?.let { found ->
                    val slug = AnimeXSource.slug(found.anilistId) ?: return@let
                    listOf("sub", "dub").amap { lang -> AnimeXSource.load(slug, found.episode, lang, "AnimeX", subs, links) }
                }
            }
        }
        return sources
    }

    /** GoPlay links carry this API's name as their source, so the app asks here for what renews their spent URLs. */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = GoPlaySource.interceptor

    private fun image(path: String?, size: String): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    companion object {
        private const val GOPLAY = "GoPlay"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_MIRROR = "https://db.wecollege.net/3"

        /** dulo.mov's TMDB key, from its JS bundle (the site calls TMDB from the browser with it). */
        private const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"

        /** Animation, reality, talk and news: what a drama row should leave out. */
        private const val NOT_DRAMA = "16,10764,10767,10763"

        @Volatile
        private var keyRefused = false

        private const val TMDB_TTL_MS = 30 * 60 * 1000L
        private const val TMDB_CACHE_SIZE = 100

        /** TMDB answers by query, with when they were fetched. */
        private val tmdbCache = ConcurrentHashMap<String, Pair<Long, String>>()
    }
}
