package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
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
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

/**
 * k-drama.in ("KatDrama") is a PHP skin over TMDB: lists and title pages are server-rendered HTML keyed by TMDB id,
 * search is a TMDB `search/multi` proxy, and streams come from the site's own resolvers `2.php` (devcorp HLS),
 * `vid2.php` (KissKH / Castle HLS, series only) and `yoy5.php` (asianctv → vidbasic mirror embeds).
 */
class KatDrama : MainAPI() {
    override var mainUrl = "https://k-drama.in"
    override var name = "KatDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    // `/explore` query strings; `upcoming` is left out because those titles have no streams yet.
    override val mainPage = mainPageOf(
        "type=kdrama&sort=popular" to "Popular K-Dramas",
        "type=kdrama&sort=newest" to "Latest K-Dramas",
        "type=kdrama&sort=rating" to "Top Rated K-Dramas",
        "type=kdrama&provider=netflix&sort=popular" to "Netflix K-Dramas",
        "type=cdrama&sort=popular" to "Chinese Dramas",
        "type=jdrama&sort=popular" to "Japanese Dramas",
        "type=thai&sort=popular" to "Thai Dramas",
        "type=movie&sort=popular" to "Asian Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/explore?${request.data}&page=$page").document
        val items = KatDramaParser.cards(doc, mainUrl).map { card ->
            if (card.isMovie) {
                newMovieSearchResponse(card.title, card.url, TvType.Movie) { posterUrl = card.poster; year = card.year }
            } else {
                newTvSeriesSearchResponse(card.title, card.url, TvType.AsianDrama) { posterUrl = card.poster; year = card.year }
            }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && KatDramaParser.hasNextPage(doc, page))
    }

    /** TMDB `search/multi`: one page of 20, `page` is ignored, people are mixed in. */
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val json = app.get("$mainUrl/api.php?action=search&q=${URLEncoder.encode(query.trim(), "UTF-8")}").text
        val items = tryParseJson<SearchResults>(json)?.results.orEmpty().mapNotNull { it.toSearchResponse() }
        return newSearchResponseList(items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        if (query.trim().length < 2) emptyList() else search(query, 1).items

    private fun SearchResult.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val isMovie = when (mediaType) {
            "movie" -> true
            "tv" -> false
            null -> title != null && name == null
            else -> return null
        }
        val label = (if (isMovie) title else name) ?: name ?: title ?: return null
        val url = KatDramaParser.detailUrl(mainUrl, if (isMovie) "movie" else "tv", id, label)
        val poster = posterPath?.let { "$TMDB_IMAGE/w500$it" }
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
        return if (isMovie) {
            newMovieSearchResponse(label, url, TvType.Movie) { posterUrl = poster; this.year = year }
        } else {
            newTvSeriesSearchResponse(label, url, TvType.AsianDrama) { posterUrl = poster; this.year = year }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val (type, id) = KatDramaParser.idOf(url) ?: throw ErrorLoadingException("Not a KatDrama title URL: $url")
        val pageUrl = url.substringBefore('?')
        val doc = app.get(pageUrl).document
        // Season 1 is what the bare page shows; other seasons are `?season=N`.
        val first = KatDramaParser.details(doc, season = 1) ?: throw ErrorLoadingException("Could not parse $pageUrl")

        fun LoadResponse.fill() {
            posterUrl = first.poster
            backgroundPosterUrl = first.backdrop
            plot = first.plot
            year = first.year
            tags = first.genres
            score = first.rating?.toDoubleOrNull()?.let { Score.from10(it) }
            actors = first.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
            addTMDbId(id.toString())
        }

        if (type == "movie") {
            val data = LinkData(id, "movie", 1, 1, first.title, first.year).toJson()
            return newMovieLoadResponse(first.title, pageUrl, TvType.Movie, data) { fill() }
        }

        val others = first.seasons.filter { it != 1 }
        val seasons = listOf(first.episodes) + others.amap { season ->
            try {
                KatDramaParser.details(app.get("$pageUrl?season=$season").document, season)?.episodes.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Season $season of $id failed: ${e.message}")
                emptyList()
            }
        }
        val episodes = seasons.flatten().map { episode ->
            newEpisode(LinkData(id, "tv", episode.season, episode.number, first.title, first.year).toJson()) {
                name = episode.name
                season = episode.season
                this.episode = episode.number
                posterUrl = episode.still
                addDate(episode.date)
            }
        }
        return newTvSeriesLoadResponse(first.title, pageUrl, TvType.AsianDrama, episodes) { fill() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<LinkData>(data)
        var found = false
        val emit: (ExtractorLink) -> Unit = { found = true; callback(it) }
        runAllAsync(
            { devcorp(link, subtitleCallback, emit) },
            { if (link.type == "tv") kisskh(link, subtitleCallback, emit) },
            // The player waits for loadLinks to return; dead mirror hosts would hold it for 30 s or more.
            { withTimeoutOrNull(MIRROR_BUDGET_MS) { vidbasic(link, subtitleCallback, emit) } },
        )
        return found
    }

    /** Server 2: one HLS master (480p/720p, fMP4 segments named `.png`) and up to ~38 VTT tracks. */
    private suspend fun devcorp(link: LinkData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val resolved = KatDramaParser.server2(app.get("$mainUrl/2.php/${link.id}/${link.season}/${link.episode}", timeout = TIMEOUT_S).document)
            ?: return
        if (!KatDramaParser.titleMatches(link.title, link.year, resolved.title, resolved.year)) {
            Log.d(name, "Server 2 matched \"${resolved.title}\" (${resolved.year}), not \"${link.title}\"; skipped")
            return
        }
        resolved.urls.forEach { url ->
            callback(newExtractorLink(name, "$name Devcorp", url, ExtractorLinkType.M3U8) { quality = Qualities.Unknown.value })
        }
        resolved.subtitles.forEach { (label, url) -> subtitleCallback(newSubtitleFile(label, url)) }
    }

    /** Server 6: KissKH (and Castle) playlists for series; titles come from the TMDB id, so no name check. */
    private suspend fun kisskh(link: LinkData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get("$mainUrl/vid2.php/${link.id}/${link.season}/${link.episode}", timeout = TIMEOUT_S).text
        val (sources, subtitles) = KatDramaParser.server6(html)
        sources.forEach { source ->
            // Castle segments are TS named `.jpg`, so the playlist goes to the player as-is.
            callback(newExtractorLink(name, "$name ${source.provider}", source.url, ExtractorLinkType.M3U8) {
                quality = source.quality?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
            })
        }
        subtitles.forEach { (label, url) -> subtitleCallback(newSubtitleFile(label, url)) }
    }

    /** Server 7: asianctv episode → vidbasic page → dood / hglink / mixdrop / streamtape mirrors (often dead or slow). */
    private suspend fun vidbasic(link: LinkData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val json = app.get("$mainUrl/yoy5.php?id=${link.id}&s=${link.season}&e=${link.episode}&ajax=1", timeout = TIMEOUT_S).text
        val resolved = KatDramaParser.server7(json) ?: return
        if (!KatDramaParser.titleMatches(link.title, link.year, resolved.title, resolved.year)) {
            Log.d(name, "Server 7 matched \"${resolved.title}\" (${resolved.year}), not \"${link.title}\"; skipped")
            return
        }
        resolved.urls.amap { page ->
            val mirrors = try {
                KatDramaParser.mirrors(app.get(page, referer = "$mainUrl/", timeout = TIMEOUT_S).text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "vidbasic $page failed: ${e.message}")
                emptyList()
            }
            mirrors.amap { mirror ->
                try {
                    loadExtractor(mirror, page, subtitleCallback, callback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "Mirror $mirror failed: ${e.message}")
                }
            }
        }
    }

    data class LinkData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("type") val type: String,
        @JsonProperty("s") val season: Int,
        @JsonProperty("e") val episode: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("year") val year: Int? = null,
    )

    data class SearchResults(@JsonProperty("results") val results: List<SearchResult>? = null)

    data class SearchResult(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
    )

    private companion object {
        const val TMDB_IMAGE = "https://image.tmdb.org/t/p"
        const val TIMEOUT_S = 30L
        const val MIRROR_BUDGET_MS = 15_000L
    }
}
