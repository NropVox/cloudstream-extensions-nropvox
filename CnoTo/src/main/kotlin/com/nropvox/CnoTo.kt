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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * Cno.to is a React SPA over a JSON API keyed by TMDB IDs; no auth, cookies or tokens are needed.
 * Titles in its own library come with first-party HLS sources; the rest only offer third-party embeds.
 */
class CnoTo : MainAPI() {
    override var mainUrl = "https://cno.to"
    override var name = "Cno.to"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    private val api get() = "$mainUrl/api"

    // media.settlar.io (anime servers 18/19) rejects non-browser user agents with 403.
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    private val titlePath = Regex("""/(movie|show|tv)/(\d+)""")

    override val mainPage = mainPageOf(
        "home" to "Home",
        // The white-label "dexter" library only lists titles with first-party HLS streams.
        "browse?site=dexter&sort=added" to "Recently Added",
        "browse?type=movie" to "Popular Movies",
        "browse?type=tv" to "Popular Shows",
        "browse?type=movie&sort=top" to "Top Rated Movies",
        "browse?type=tv&sort=top" to "Top Rated Shows",
        "browse?type=tv&genre=16&lang=ja" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "home") {
            val home = fetch<Home>("home") ?: throw ErrorLoadingException("Could not load Cno.to home")
            // Genre, franchise and topic shelves carry their own arrays instead of items.
            val lists = home.shelves.orEmpty().mapNotNull { shelf ->
                val title = shelf.title ?: return@mapNotNull null
                val items = shelf.items.orEmpty().mapNotNull { it.toSearchResponse() }
                if (items.isEmpty()) null else HomePageList(title, items)
            }
            return newHomePageResponse(lists, hasNext = false)
        }

        val result = fetch<Paged>("${request.data}&page=$page")
            ?: throw ErrorLoadingException("Could not load ${request.name}")
        return newHomePageResponse(
            request.name,
            result.results.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = (result.page ?: page) < (result.totalPages ?: 0)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = fetch<SearchResult>("search?q=$encoded&page=$page")
            ?: return newSearchResponseList(emptyList(), hasNext = false)
        // "top" (best matches) is only filled on the first page and overlaps with "results".
        val cards = (result.top.orEmpty() + result.results.orEmpty()).distinctBy { "${it.type}:${it.id}" }
        return newSearchResponseList(
            cards.mapNotNull { it.toSearchResponse() },
            hasNext = (result.page ?: page) < (result.totalPages ?: 0)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return fetch<List<Suggestion>>("suggest?q=$encoded")?.mapNotNull {
            Card(type = it.kind, id = it.id, title = it.title, poster = it.image).toSearchResponse()
        }
    }

    private fun Card.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = title ?: return null
        // Upcoming titles are listed but have nothing to play.
        if (playable == false) return null
        val card = this
        return when (type) {
            "movie" -> newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                posterUrl = image(card.poster)
                year = card.year
                score = Score.from10(card.rating)
            }
            "tv" -> newTvSeriesSearchResponse(title, "$mainUrl/show/$id", TvType.TvSeries) {
                posterUrl = image(card.poster)
                year = card.year
                score = Score.from10(card.rating)
            }
            // Suggestions also return people.
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (kind, id) = titlePath.find(url)?.destructured ?: return null
        return if (kind == "movie") loadMovie(id.toInt()) else loadShow(id.toInt())
    }

    private suspend fun loadMovie(id: Int): LoadResponse? {
        val movie = fetch<Details>("movie/$id") ?: throw ErrorLoadingException("Could not load movie $id")
        val title = movie.title ?: return null
        val type = if (movie.isAnime()) TvType.AnimeMovie else TvType.Movie
        return newMovieLoadResponse(title, "$mainUrl/movie/$id", type, LinkData("movie", id).toJson()) {
            applyDetails(movie)
            duration = movie.runtime
        }
    }

    private suspend fun loadShow(id: Int): LoadResponse? {
        val show = fetch<Details>("tv/$id") ?: throw ErrorLoadingException("Could not load show $id")
        val title = show.title ?: return null

        val episodes = show.seasonList.orEmpty().mapNotNull { it.number }
            .amap { number -> fetch<Season>("tv/$id/season/$number")?.episodes.orEmpty() }
            .flatten()
            .filter { it.playable != false }
            .mapNotNull { info ->
                val season = info.season ?: return@mapNotNull null
                val episode = info.episode ?: return@mapNotNull null
                newEpisode(LinkData("tv", id, season, episode).toJson()) {
                    this.season = season
                    this.episode = episode
                    name = info.title
                    description = info.overview
                    posterUrl = image(info.still, "w300")
                    runTime = info.runtime
                    score = Score.from10(info.rating)
                    addDate(info.date)
                }
            }

        val type = if (show.isAnime()) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, "$mainUrl/show/$id", type, episodes) {
            applyDetails(show)
            showStatus = when (show.status) {
                "Returning Series", "In Production" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
        }
    }

    private suspend fun LoadResponse.applyDetails(details: Details) {
        posterUrl = image(details.poster)
        backgroundPosterUrl = image(details.backdrop, "w1280")
        logoUrl = image(details.logo)
        plot = details.overview
        year = details.year
        tags = details.genreList?.mapNotNull { it.name }
        contentRating = details.certification
        score = Score.from10(details.rating)
        actors = details.cast?.mapNotNull { member ->
            val name = member.name ?: return@mapNotNull null
            ActorData(Actor(name, image(member.photo, "w185")), roleString = member.role)
        }
        recommendations = details.recommendations?.mapNotNull { it.toSearchResponse() }
        addImdbId(details.imdbId)
        addTMDbId(details.id?.toString())
        details.trailer?.key?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<LinkData>(data)
        val sources = if (link.type == "movie") {
            fetch<Details>("movie/${link.id}")?.sources
        } else {
            fetch<EpisodeDetails>("tv/${link.id}/episode/${link.season}/${link.episode}")?.sources
        }

        var found = false
        for (source in sources.orEmpty()) {
            val url = source.url ?: continue
            when (source.kind) {
                "hls" -> {
                    // Sealed playlists need a Turnstile pass and key unwrapping; Cno.to has this switched off.
                    if (source.sealed == true) {
                        Log.w(name, "Skipping sealed source ${source.id}")
                        continue
                    }
                    val label = listOfNotNull(name, source.label, source.note).joinToString(" ")
                    // The master playlist lists every resolution; the player offers them as qualities.
                    callback(newExtractorLink(name, label, fixUrl(url), ExtractorLinkType.M3U8) {
                        referer = "$mainUrl/"
                        quality = Qualities.Unknown.value
                        headers = browserHeaders
                    })
                    found = true
                }
                // cinesrc.st / vidfast.vc pages; only playable if an installed extractor handles them.
                "embed" -> if (loadExtractor(url, "$mainUrl/", subtitleCallback, callback)) found = true
            }
        }

        if (found) loadSubtitles(link.apiKey(), subtitleCallback)
        return found
    }

    private suspend fun loadSubtitles(key: String, subtitleCallback: (SubtitleFile) -> Unit) {
        var list = fetch<SubtitleList>("subs/list/$key")
        // Cno.to gathers subtitles on demand for titles nobody has opened yet.
        if (list?.files.isNullOrEmpty() && list?.pending != null) {
            delay(SUBTITLE_RETRY_DELAY)
            list = fetch<SubtitleList>("subs/list/$key")
        }

        // Popular titles list hundreds of files; keep the best-ranked few per language.
        list?.files.orEmpty()
            .filter { it.id != null }
            .sortedByDescending { it.rank ?: 0.0 }
            .groupBy { it.label ?: it.lang ?: "Unknown" }
            .forEach { (label, files) ->
                files.take(MAX_SUBTITLES_PER_LANGUAGE).forEachIndexed { index, file ->
                    subtitleCallback(
                        newSubtitleFile(
                            if (index == 0) label else "$label ${index + 1}",
                            "$api/subs/file/$key/${file.id}.vtt"
                        ) {
                            headers = browserHeaders
                        }
                    )
                }
            }
    }

    private suspend inline fun <reified T> fetch(path: String): T? =
        tryParseJson<T>(app.get("$api/$path", headers = browserHeaders).text)

    private fun image(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private fun Details.isAnime() =
        originalLanguage == "ja" && genreList.orEmpty().any { it.id == ANIMATION_GENRE }

    companion object {
        private const val ANIMATION_GENRE = 16
        private const val MAX_SUBTITLES_PER_LANGUAGE = 3
        private const val SUBTITLE_RETRY_DELAY = 3_000L
    }

    data class LinkData(
        val type: String,
        val id: Int,
        val season: Int? = null,
        val episode: Int? = null,
    ) {
        /** Path segment used by the subtitle and skip endpoints. */
        fun apiKey() = if (type == "movie") "movie/$id" else "tv/$id/$season/$episode"
    }

    data class Card(
        val type: String? = null,
        val id: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val poster: String? = null,
        val rating: Double? = null,
        val playable: Boolean? = null,
    )

    data class Shelf(val title: String? = null, val items: List<Card>? = null)

    data class Home(val shelves: List<Shelf>? = null)

    data class Paged(val page: Int? = null, val totalPages: Int? = null, val results: List<Card>? = null)

    data class SearchResult(
        val page: Int? = null,
        val totalPages: Int? = null,
        val results: List<Card>? = null,
        val top: List<Card>? = null,
    )

    data class Suggestion(
        val kind: String? = null,
        val id: Int? = null,
        val title: String? = null,
        val image: String? = null,
    )

    data class Genre(val id: Int? = null, val name: String? = null)

    data class CastMember(val name: String? = null, val role: String? = null, val photo: String? = null)

    data class Trailer(val key: String? = null)

    data class SeasonSummary(val number: Int? = null)

    data class Details(
        val id: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val overview: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val genreList: List<Genre>? = null,
        val rating: Double? = null,
        val runtime: Int? = null,
        val certification: String? = null,
        val originalLanguage: String? = null,
        val status: String? = null,
        val imdbId: String? = null,
        val cast: List<CastMember>? = null,
        val trailer: Trailer? = null,
        val recommendations: List<Card>? = null,
        val seasonList: List<SeasonSummary>? = null,
        val sources: List<Source>? = null,
    )

    data class EpisodeDetails(val sources: List<Source>? = null)

    data class Season(val episodes: List<EpisodeInfo>? = null)

    data class EpisodeInfo(
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val overview: String? = null,
        val still: String? = null,
        val runtime: Int? = null,
        val date: String? = null,
        val rating: Double? = null,
        val playable: Boolean? = null,
    )

    data class Source(
        val id: String? = null,
        val kind: String? = null,
        val url: String? = null,
        val label: String? = null,
        val note: String? = null,
        val sealed: Boolean? = null,
    )

    data class SubtitleList(val files: List<SubtitleInfo>? = null, val pending: Any? = null)

    data class SubtitleInfo(
        val id: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val rank: Double? = null,
    )
}
