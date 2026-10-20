package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.USER_AGENT
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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

class SevenMovies : MainAPI() {
    override var mainUrl = "https://7movies.ac"
    override var name = "7Movies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val player = "https://embed.vidrift.net"
    private val titlePath = Regex("""/(movie|tv)/(\d+)""")
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "catalog" to "Discover",
        "type=movie" to "Popular Movies",
        "type=tv" to "Popular TV Shows",
        "type=movie&sort=rated" to "Top Rated Movies",
        "type=tv&sort=rated" to "Top Rated TV Shows",
        "type=movie&sort=newest" to "Latest Movies",
        "type=tv&sort=newest" to "Latest TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "catalog") {
            val rows = get<SevenRows>("$mainUrl/api/catalog-more?offset=${(page - 1) * 4}&count=4&skip=")
                ?: throw ErrorLoadingException("Could not load 7Movies catalog")
            return newHomePageResponse(rows.rows.orEmpty().mapNotNull { row ->
                val cards = row.items.orEmpty().mapNotNull { it.toSearchResponse() }
                if (cards.isEmpty()) null else HomePageList(row.title ?: "Discover", cards)
            }, hasNext = rows.hasMore == true)
        }
        val result = get<SevenPage>("$mainUrl/api/discover?${request.data}&page=$page")
            ?: throw ErrorLoadingException("Could not load ${request.name}")
        return newHomePageResponse(request.name, result.items.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = page < (result.totalPages ?: 0))
    }

    // The site's search returns one ranked list, with no pagination contract.
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1 || query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val result = get<SevenPage>("$mainUrl/api/search?v=5&q=${encode(query.trim())}")
            ?: throw ErrorLoadingException("Could not search 7Movies")
        return newSearchResponseList(result.results.orEmpty().mapNotNull { it.toSearchResponse() }, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    private fun SevenCard.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = title ?: return null
        val card = this
        return when (mediaType) {
            "movie" -> newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                posterUrl = card.poster
                year = card.year?.toIntOrNull()
                score = Score.from10(card.rating)
            }
            "tv" -> newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                posterUrl = card.poster
                year = card.year?.toIntOrNull()
                score = Score.from10(card.rating)
            }
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (type, id) = titlePath.find(url)?.destructured ?: return null
        // The proxy appends videos, credits and recommendations itself. Unknown TMDB paths return
        // {results: []}, so episodes must use the site's separate /api/tv endpoint.
        val details = get<SevenDetails>("$mainUrl/api/tmdb?path=/$type/$id")
            ?: throw ErrorLoadingException("Could not load 7Movies title")
        val title = details.title ?: details.name ?: return null
        val data = SevenLink(id.toInt(), type)
        val response: LoadResponse = if (type == "movie") {
            newMovieLoadResponse(title, "$mainUrl/movie/$id", TvType.Movie, data.toJson()) {
                duration = details.runtime
            }
        } else {
            val seasons = details.seasons.orEmpty().mapNotNull { it.number }.distinct().sorted()
            // Limit concurrent season requests for long-running shows.
            val episodes = seasons.chunked(4).flatMap { chunk ->
                chunk.amap { season ->
                    val result = get<SevenEpisodes>("$mainUrl/api/tv/$id/season/$season")
                        ?: throw ErrorLoadingException("Could not load season $season")
                    result.episodes.orEmpty().mapNotNull { ep ->
                        val number = ep.number ?: return@mapNotNull null
                        newEpisode(data.copy(season = season, episode = number).toJson()) {
                            name = ep.name
                            this.season = season
                            episode = number
                            posterUrl = image(ep.still, "w300")
                            description = ep.overview
                            runTime = ep.runtime
                            addDate(ep.airDate)
                        }
                    }
                }.flatten()
            }
            newTvSeriesLoadResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, episodes) {
                duration = details.episodeRunTime?.firstOrNull()
                showStatus = when (details.status) {
                    "Returning Series", "In Production" -> ShowStatus.Ongoing
                    "Ended", "Canceled" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
        return response.apply {
            posterUrl = image(details.poster, "w500")
            backgroundPosterUrl = image(details.backdrop, "original")
            plot = details.overview
            year = (details.releaseDate ?: details.firstAirDate)?.take(4)?.toIntOrNull()
            score = Score.from10(details.rating)
            tags = details.genres.orEmpty().mapNotNull { it.name }
            actors = details.credits?.cast.orEmpty().take(30).mapNotNull { cast ->
                ActorData(Actor(cast.name ?: return@mapNotNull null, image(cast.profile, "w185")), roleString = cast.character)
            }
            recommendations = details.recommendations?.results.orEmpty().mapNotNull { item ->
                SevenCard(item.id, item.title ?: item.name, (item.releaseDate ?: item.firstAirDate)?.take(4),
                    item.rating, item.mediaType ?: type, image(item.poster, "w500")).toSearchResponse()
            }
            addTMDbId(id)
            addImdbId(details.imdbId)
            addTrailer(details.videos?.results.orEmpty().firstOrNull {
                it.site == "YouTube" && it.type == "Trailer" && it.key != null
            }?.let { "https://www.youtube.com/watch?v=${it.key}" })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = tryParseJson<SevenLink>(data) ?: return false
        if (link.type !in setOf("movie", "tv") || link.id <= 0 ||
            (link.type == "tv" && (link.season == null || link.episode == null))) return false
        val path = if (link.type == "tv") "tv/${link.id}/${link.season}/${link.episode}" else "movie/${link.id}"
        val watchUrl = "$mainUrl/${link.type}/${link.id}/watch"
        // Never persist grants or signed URLs in episode data: obtain them when playback starts.
        val grant = safely("playback grant") {
            app.post("$mainUrl/api/playback-token", headers = browserHeaders + mapOf(
                "Origin" to mainUrl, "Referer" to watchUrl, "Content-Type" to "application/json"
            ), json = SevenGrantRequest(link.id, link.type, link.season, link.episode), timeout = 30L)
                .parsedSafe<SevenGrant>()
        }?.token?.takeIf { it.isNotBlank() } ?: return false
        val bootResponse = safely("player boot") {
            app.get("$player/api/boot/$path?token=${encode(grant)}&shell=1", headers = browserHeaders + mapOf(
                "x-embed-parent" to mainUrl, "Referer" to watchUrl
            ), timeout = 30L)
        } ?: return false
        val boot = bootResponse.parsedSafe<SevenBoot>() ?: return false
        val meta = boot.meta ?: return false
        if (!meta.unreleased.isNullOrBlank()) return false
        val token = meta.token?.takeIf { it.isNotBlank() } ?: grant
        val cookie = bootResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val playerHeaders = browserHeaders + mapOf("Referer" to "$player/") +
            if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val subtitles = Collections.synchronizedSet(HashSet<String>())

        fun mediaHeaders(url: String): Map<String, String> =
            if (URI(url).host == URI(player).host) playerHeaders else browserHeaders + mapOf("Referer" to "$player/")

        suspend fun emitSubtitle(track: SevenSubtitle) {
            val url = absolute(track.url) ?: return
            if (subtitles.add(url)) subtitleCallback(newSubtitleFile(track.label ?: track.lang ?: "Unknown", url) {
                headers = mediaHeaders(url)
            })
        }

        suspend fun emit(stream: SevenStream, provider: String, quality: Int? = null) {
            val url = absolute(stream.url?.takeIf { it.isNotBlank() } ?: stream.proxyUrl) ?: return
            if (emitted.add(url)) {
                val label = stream.name ?: provider.replaceFirstChar { it.uppercase() }
                callback(newExtractorLink(name, "$name - $label", url,
                    if (stream.type == "mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    referer = "$player/"
                    this.quality = stream.rungs.orEmpty().mapNotNull { it.height }.maxOrNull()
                        ?: quality ?: Qualities.Unknown.value
                    headers = mediaHeaders(url)
                })
                stream.subtitleListUrl?.let { list ->
                    absolute(list)?.let { subtitleUrl ->
                        get<SevenTracks>(subtitleUrl, playerHeaders)?.tracks.orEmpty().forEach { emitSubtitle(it) }
                    }
                }
            }
        }

        boot.shell?.subtitles.orEmpty().forEach { emitSubtitle(it) }
        listOf("evion" to meta.evionUrl, "vexon" to meta.vexonUrl, "selfhost" to meta.selfhostUrl)
            .forEach { (provider, url) ->
                if (!url.isNullOrBlank()) emit(SevenStream(url = url,
                    type = if (provider == "selfhost") meta.selfhostKind else "hls"), provider)
            }
        meta.orionStreams.orEmpty().forEach { emit(it, "Orion") }
        val providers = linkedSetOf("orion", "vela", "atlas", "titan", "lyra")
        // Anime flags are either false or an array of available sub/dub providers.
        meta.anime?.takeIf { it.isArray }?.forEach { providers.add(it.asText()) }
        if (meta.anime?.isBoolean == true && meta.anime.asBoolean()) providers.addAll(listOf("anime", "animedub"))
        providers.filter { it.matches(Regex("[a-z0-9-]{1,24}")) }.amap { provider ->
            safely(provider) {
                val source = get<SevenSources>("$player/api/source/$path?token=${encode(token)}" +
                    "&provider=$provider&parent=${encode(meta.parent ?: URI(mainUrl).host)}&nofallback=1", playerHeaders)
                    ?: return@safely
                source.subtitles.orEmpty().forEach { emitSubtitle(it) }
                source.streams.orEmpty().forEach { emit(it, source.provider ?: provider, source.maxRes) }
            }
        }
        return emitted.isNotEmpty()
    }

    private suspend inline fun <reified T : Any> get(url: String, headers: Map<String, String> = browserHeaders): T? =
        safely("request") { app.get(url, headers = headers, timeout = 30L).parsedSafe<T>() }

    private suspend fun <T> safely(operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Do not log exception messages: HTTP errors may contain playback tokens or signed URLs.
        Log.d(name, "$operation failed (${e.javaClass.simpleName})")
        null
    }

    private fun absolute(url: String?): String? = url?.takeIf { it.isNotBlank() }?.let {
        runCatching { URI("$player/").resolve(it).takeIf { u -> u.scheme in setOf("http", "https") }?.toString() }.getOrNull()
    }

    private fun image(path: String?, size: String) = path?.let { "https://image.tmdb.org/t/p/$size$it" }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
