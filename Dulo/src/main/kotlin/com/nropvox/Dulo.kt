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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/**
 * dulo.mov: a SvelteKit skin over TMDB (called from the browser with the key baked into its bundle) whose
 * streams come from its own Workers: ZCDN ("Pickle", HLS up to 4K, no headers) and `/api/streams/resolve`,
 * which polls third-party servers and serves them through a Referer-checked proxy.
 */
class Dulo : MainAPI() {
    override var mainUrl = "https://dulo.mov"
    override var name = "Dulo"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApi = "https://api.themoviedb.org/3"

    // The zstream-proxy URLs of resolver sources and their captions 403 without the site as Referer or Origin.
    private val siteHeaders
        get() = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "trending/all/week" to "Trending this week",
        "movie/popular" to "Popular movies",
        "tv/popular" to "Popular shows",
        "movie/now_playing?region=US" to "In theaters",
        "tv/on_the_air" to "On the air",
        "discover/tv?with_genres=16&with_origin_country=JP&sort_by=popularity.desc" to "Anime",
        "movie/top_rated" to "Top rated movies",
        "tv/top_rated" to "Top rated shows",
    )

    private fun tmdb(path: String): String {
        val separator = if ('?' in path) '&' else '?'
        return "$tmdbApi/$path${separator}api_key=$TMDB_KEY&language=en-US&include_adult=false"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = app.get(tmdb("${request.data}${if ('?' in request.data) '&' else '?'}page=$page"))
            .parsedSafe<TmdbPage>()
        // Only "trending/all" tags each result; the other rows are all one type.
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(type) }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = page < (result?.totalPages ?: 0))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = app.get(tmdb("search/multi?query=$encoded&page=$page")).parsedSafe<TmdbPage>()
        val items = result?.results.orEmpty().mapNotNull { it.toSearchResponse(null) }
        return newSearchResponseList(items, hasNext = page < (result?.totalPages ?: 0))
    }

    /** People and poster-less entries are skipped, like the site's search does. */
    private fun TmdbItem.toSearchResponse(defaultType: String?): SearchResponse? {
        val type = mediaType ?: defaultType ?: return null
        val id = id ?: return null
        val title = title ?: name ?: return null
        val poster = image(posterPath, "w500") ?: return null
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        val url = DuloParser.titleUrl(mainUrl, type, id)
        return when (type) {
            "movie" -> newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
                score = Score.from10(voteAverage)
            }
            "tv" -> {
                val tvType = if (DuloParser.isAnime(genreIds, originCountry)) TvType.Anime else TvType.TvSeries
                newTvSeriesSearchResponse(title, url, tvType) {
                    posterUrl = poster
                    this.year = year
                    score = Score.from10(voteAverage)
                }
            }
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id) = DuloParser.titleRef(url) ?: return null
        val details = app.get(tmdb("$type/$id?append_to_response=external_ids,credits,recommendations,videos"))
            .parsedSafe<TmdbDetails>() ?: return null
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
        val link = DuloLink(details.id, type, title, year, imdbId)

        val load: LoadResponse = if (type == "movie") {
            newMovieLoadResponse(title, url, TvType.Movie, link) {
                duration = details.runtime
            }
        } else {
            val anime = DuloParser.isAnime(details.genres?.mapNotNull { it.id }, details.originCountry)
            newTvSeriesLoadResponse(title, url, if (anime) TvType.Anime else TvType.TvSeries, episodes(id, details, link)) {
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

    /** Specials (season 0) are skipped: ZCDN and the resolver only index regular seasons. */
    private suspend fun episodes(id: Int, details: TmdbDetails, link: DuloLink) =
        details.seasons.orEmpty().mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }
            // TMDB allows at most 20 appended sub-requests per call.
            .chunked(20).amap { chunk ->
                val appended = chunk.joinToString(",") { "season/$it" }
                val seasons = try {
                    mapper.readTree(app.get(tmdb("tv/$id?append_to_response=$appended")).text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@amap emptyList()
                }
                chunk.flatMap { number ->
                    val seasonData = seasons.get("season/$number")?.let { mapper.treeToValue(it, TmdbSeason::class.java) }
                    seasonData?.episodes.orEmpty().mapNotNull { episode ->
                        val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                        newEpisode(link.copy(season = number, episode = episodeNumber)) {
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
    ): Boolean {
        val link = parseJson<DuloLink>(data)
        var found = false
        runAllAsync(
            { loadSubtitles(link, subtitleCallback) },
            { if (loadZcdn(link, callback)) found = true },
            // The player waits for the slowest task, so the resolver's polling is capped.
            { withTimeoutOrNull(RESOLVE_BUDGET_MS) { if (loadResolved(link, subtitleCallback, callback)) found = true } },
        )
        return found
    }

    /** Pickle: one `/cdn` lookup on a random ZCDN copy, then the master playlist for its top quality. */
    private suspend fun loadZcdn(link: DuloLink, callback: (ExtractorLink) -> Unit): Boolean {
        val domain = workerDomain()
        val hosts = (1..3).shuffled()
        for (host in hosts.take(2)) {
            try {
                val answer = app.get(DuloParser.zcdnUrl(domain, host, link), timeout = 15L).text
                val playUrl = DuloParser.zcdnPlayUrl(answer, domain)
                    ?: return false.also { Log.d(name, "Pickle: no stream (${answer.take(80)})") }
                val master = app.get(playUrl, timeout = 15L)
                if (!master.isSuccessful || !master.text.trimStart().startsWith("#EXTM3U")) {
                    Log.w(name, "Pickle: master HTTP ${master.code}")
                    return false
                }
                callback(newExtractorLink("$name Pickle", "$name Pickle", playUrl, ExtractorLinkType.M3U8) {
                    quality = DuloParser.topHeight(master.text) ?: Qualities.Unknown.value
                })
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Pickle zcdn$host: ${e.message}")
            }
        }
        return false
    }

    /**
     * Extra servers: `/api/streams/resolve` answers `pending` until its backend has asked every server, and
     * (with `progressive=true`) already lists the servers found so far, so each poll emits the new ones.
     */
    private suspend fun loadResolved(
        link: DuloLink,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "$mainUrl/api/streams/resolve?${DuloParser.resolveQuery(link)}"
        // Every poll mints new proxy tokens for the same servers, so they are told apart by label like on the site.
        val emitted = HashSet<String>()
        while (true) {
            val response = try {
                app.get(url, timeout = 30L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Resolve: ${e.message}")
                break
            }
            if (response.code == 429 || response.code == 503) {
                delay(2_000)
                continue
            }
            val answer = DuloParser.resolveAnswer(response.text) ?: break
            answer.sources.filter { it.url != null && emitted.add(it.label ?: it.url) }
                .forEach { emitResolved(it, subtitleCallback, callback) }
            if (!answer.pending) break
            delay(1_500)
        }
        return emitted.isNotEmpty()
    }

    private suspend fun emitResolved(
        source: ResolveSource,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceUrl = source.url ?: return
        val (server, height) = DuloParser.splitLabel(source.label ?: "Server")
        source.captions.forEach { caption ->
            val captionUrl = caption.url ?: return@forEach
            subtitleCallback(newSubtitleFile("${caption.display ?: caption.language ?: "Unknown"} ($server)", captionUrl) {
                headers = siteHeaders
            })
        }
        val type = when (source.type) {
            "mp4", "mkv", "webm" -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
        // The player appends the quality itself, so the name stops at the server.
        callback(newExtractorLink("$name $server", "$name $server", sourceUrl, type) {
            referer = "$mainUrl/"
            quality = height ?: Qualities.Unknown.value
            headers = siteHeaders
        })
    }

    private suspend fun loadSubtitles(link: DuloLink, subtitleCallback: (SubtitleFile) -> Unit) {
        val params = listOfNotNull(
            "id=${link.id}",
            link.imdbId?.let { "imdb=$it" },
            link.season?.let { "season=$it" },
            link.episode?.let { "episode=$it" },
        ).joinToString("&")
        val json = try {
            app.get("$mainUrl/api/subtitles/search?$params", timeout = 20L).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "Subtitles: ${e.message}")
            return
        }
        DuloParser.subtitles(json).forEach { subtitle ->
            val label = (subtitle.display ?: subtitle.language ?: "Unknown") + if (subtitle.hearingImpaired) " (SDH)" else ""
            subtitleCallback(newSubtitleFile(label, subtitle.url!!))
        }
    }

    /** The Workers' domain from the site's runtime config, read once per session. */
    private suspend fun workerDomain(): String = domainLock.withLock {
        cachedDomain ?: run {
            val domain = try {
                DuloParser.workerDomain(app.get("$mainUrl/dulo-config.js", timeout = 10L).text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Config: ${e.message}")
                null
            }
            (domain ?: DuloParser.DEFAULT_WORKER_DOMAIN).also { cachedDomain = it }
        }
    }

    private fun image(path: String?, size: String): String? =
        path?.let { "https://image.tmdb.org/t/p/$size$it" }

    companion object {
        /** dulo.mov's own TMDB key, from its JS bundle (the site calls TMDB from the browser with it). */
        private const val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val RESOLVE_BUDGET_MS = 10_000L

        private val domainLock = Mutex()
        private var cachedDomain: String? = null
    }
}
