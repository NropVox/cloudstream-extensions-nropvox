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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nropvox.VegamoviesParser.Kind
import java.net.URLEncoder
import java.util.Collections

class Vegamovies : MainAPI() {
    // The site rotates its domain; the nexdrive, fastdl and vcloud hosts are always read from the page links.
    override var mainUrl = "https://new1.vegamovies.lease"
    override var name = "Vegamovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val hosts = NexdriveResolver(name)

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

    // `/ts-search.php` is a Typesense proxy with 15 hits per page; wp-json search is poisoned by Cloudflare's cache.
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val result = app.get("$mainUrl/ts-search.php?q=${encode(query.trim())}&page=$page", timeout = 30L)
            .parsedSafe<VgSearch>() ?: throw ErrorLoadingException("Could not search $name")
        val items = result.hits.orEmpty().mapNotNull { hit ->
            val doc = hit.document ?: return@mapNotNull null
            val permalink = doc.permalink ?: return@mapNotNull null
            val title = doc.title ?: return@mapNotNull null
            searchResponse("$mainUrl$permalink", title, doc.poster, null, anime = doc.category.orEmpty().contains("Anime Series"))
        }
        return newSearchResponseList(items, hasNext = items.isNotEmpty() && page * SEARCH_PAGE_SIZE < (result.found ?: 0))
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
            val link = VgLink(groups.flatMap { pagesOf(it) })
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

    /** Batch zips hold whole seasons, never an episode, so they are not a stream source. */
    private fun pagesOf(group: VgGroup) = group.buttons
        .filter { VegamoviesParser.kind(it.label) != Kind.BATCH }
        .map { VgPage(group.heading, it.label, it.url) }

    // One nexdrive page per season part names its episodes; every quality of that part can then play each one.
    private suspend fun episodes(groups: List<VgGroup>): List<Episode> {
        // A series page with no season heading is one season.
        val parts = groups.groupBy { it.part ?: (1 to 0) }.toSortedMap(compareBy({ it.first }, { it.second }))
        return parts.entries.chunked(4).flatMap { chunk ->
            chunk.amap { (key, partGroups) ->
                val pages = partGroups.flatMap { pagesOf(it) }
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
    ): Boolean {
        val link = tryParseJson<VgLink>(data) ?: return false
        val sink = Sink(callback)
        // Every nexdrive page, host and mirror resolves at once; only the Cloudflare-challenged V-Cloud hops queue.
        link.pages.amap { page ->
            safely(name, "nexdrive ${page.label}") {
                val entries = VegamoviesParser.entries(app.get(page.url, timeout = 30L).document)
                    .filter { it.episode == link.episode }
                entries.amap { entry ->
                    hosts.resolve(entry.url) { host, url, streamable, quality -> sink.add(page, host, url, streamable, quality) }
                }
            }
        }
        return sink.isNotEmpty()
    }

    /**
     * Download-only links (see [NexdriveSink]) play from the start but cannot seek. Those rank as Unknown quality
     * (priority 4) under their own source name so every 480p+ stream is picked first, and users can still reorder
     * them in source priority.
     */
    private inner class Sink(private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        fun isNotEmpty() = emitted.isNotEmpty()

        /** [quality] overrides the heading's for transcodes, whose size and codec tags no longer apply; the player shows it. */
        suspend fun add(page: VgPage, host: String, url: String, streamable: Boolean, quality: Int? = null) {
            if (!emitted.add(url)) return
            val tags = if (quality != null) null else VegamoviesParser.tags(page.heading).ifBlank { page.heading.take(60) }
            val height = quality ?: VegamoviesParser.quality("${page.heading} ${page.label}").takeIf { it > 0 }
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, tags).joinToString(" · ")
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                this.quality = if (streamable) height ?: Qualities.Unknown.value else Qualities.Unknown.value
            })
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val SEARCH_PAGE_SIZE = 15
    }
}
