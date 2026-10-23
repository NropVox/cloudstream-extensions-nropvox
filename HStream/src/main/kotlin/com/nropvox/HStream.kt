package com.nropvox

import android.media.MediaCodecList
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * hstream.moe is a server-rendered Laravel/Livewire site: lists, search and series pages are HTML, and the only JSON
 * is `POST /player/api`, which names the stream path and the CDN mirrors. Lists show one card per episode; they are
 * collapsed into one entry per series.
 */
class HStream : MainAPI() {
    override var mainUrl = "https://hstream.moe"
    override var name = "HStream"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val homeLock = Mutex()
    @Volatile private var home: Pair<Document, Long>? = null

    // `home:` rows are tab panels of `/` (not paginated); `search:` rows are `/search` query strings.
    override val mainPage = mainPageOf(
        "home:tabs-trending" to "Trending",
        "search:order=recently-uploaded" to "Recently Uploaded",
        "search:order=recently-released" to "Recently Released",
        "home:tabs-popular-weekly" to "Popular This Week",
        "home:tabs-popular-monthly" to "Popular This Month",
        "search:order=view-count" to "Most Viewed",
        "home:tabs-most-likes" to "Most Liked",
        "search:order=recently-uploaded&tags%5B0%5D=uncensored" to "Uncensored",
        "search:order=recently-uploaded&tags%5B0%5D=4k" to "4K",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, value) = request.data.split(':', limit = 2)
        if (kind == "home") {
            val panel = if (page == 1) HStreamParser.homeTab(homePage(), value) else null
            val items = panel?.let { HStreamParser.cards(it, mainUrl) }.orEmpty().map { it.toSearchResponse() }
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        val (items, hasNext) = searchPage("$value&view=poster&page=$page")
        return newHomePageResponse(request.name, items, hasNext)
    }

    /** The four home-only rows share one fetch of `/`. */
    private suspend fun homePage(): Document = homeLock.withLock {
        home?.takeIf { System.currentTimeMillis() - it.second < HOME_TTL_MS }?.first
            ?: app.get("$mainUrl/").document.also { home = it to System.currentTimeMillis() }
    }

    private suspend fun searchPage(query: String): Pair<List<SearchResponse>, Boolean> {
        val doc = app.get("$mainUrl/search?$query").document
        return HStreamParser.cards(doc, mainUrl).map { it.toSearchResponse() } to HStreamParser.hasNextPage(doc)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val (items, hasNext) = searchPage("search=${encode(query.trim())}&view=poster&page=$page")
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    private fun HStreamParser.Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, "$mainUrl/hentai/$slug", TvType.NSFW) { posterUrl = poster }

    override suspend fun load(url: String): LoadResponse {
        var pageUrl = url
        var doc = app.get(pageUrl).document
        // An episode URL (shared link, old bookmark) opens its series.
        if (HStreamParser.episodeId(doc) != null) {
            pageUrl = HStreamParser.seriesUrl(doc, mainUrl) ?: throw ErrorLoadingException("No series link on $url")
            doc = app.get(pageUrl).document
        }
        val seriesSlug = HStreamParser.slug(pageUrl) ?: throw ErrorLoadingException("Not a series URL: $url")
        val series = HStreamParser.series(doc, seriesSlug, mainUrl) ?: throw ErrorLoadingException("Could not parse $url")
        val seriesUrl = "$mainUrl/hentai/$seriesSlug"

        fun LoadResponse.fill() {
            posterUrl = series.cover
            plot = series.plot
            year = series.year
            tags = (series.tags + listOfNotNull(series.studio)).distinct()
        }

        if (series.episodes.size == 1) {
            return newMovieLoadResponse(series.title, seriesUrl, TvType.NSFW, series.episodes.single().url) {
                fill()
                backgroundPosterUrl = series.episodes.single().thumbnail?.let(::fullStill)
            }
        }
        val episodes = series.episodes.map { episode ->
            newEpisode(episode.url) {
                name = "Episode ${episode.number}"
                this.episode = episode.number
                posterUrl = episode.thumbnail
            }
        }
        return newTvSeriesLoadResponse(series.title, seriesUrl, TvType.NSFW, episodes) {
            fill()
            backgroundPosterUrl = series.episodes.firstOrNull()?.thumbnail?.let(::fullStill)
        }
    }

    // `gallery-ep-1-0-thumbnail.webp` -> the full-size 1280x720 still.
    private fun fullStill(thumbnail: String) = thumbnail.replace("-thumbnail.webp", ".webp")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val player = player(data) ?: return false
        val path = player.streamUrl?.trim('/')?.ifEmpty { null } ?: return false
        val domain = pickDomain(player.streamDomains.orEmpty(), path) ?: return false
        val base = "$domain/$path"

        callback(newExtractorLink(name, "$name MP4 720p (H.264)", "$base/x264.720p.mp4", ExtractorLinkType.VIDEO) {
            quality = 720
        })
        callback(newExtractorLink(name, "$name DASH 720p (H.264)", "$base/720/manifest.mpd", ExtractorLinkType.DASH) {
            quality = 720
        })
        // 1080p and up are AV1-only; the site itself only offers them when the browser can decode AV1.
        if (av1Supported) {
            val av1 = listOfNotNull(
                Triple("1080", "1080p", 1080),
                Triple("2160", "4K", 2160),
                Triple("1080i", "1080p 48fps", 1080).takeIf { player.interpolated == 1 },
                Triple("2160i", "4K 48fps", 2160).takeIf { player.interpolatedUhd == 1 },
            )
            for ((dir, label, height) in av1) {
                callback(newExtractorLink(name, "$name DASH $label (AV1)", "$base/$dir/manifest.mpd", ExtractorLinkType.DASH) {
                    quality = height
                })
            }
        }
        // `extra_subtitles` lists auto-translations under `autotrans/<lang>.ass`, but those answered 404 everywhere.
        subtitleCallback(newSubtitleFile("English", "$base/eng.ass"))
        return true
    }

    /** The episode page sets the Laravel session; `/player/api` rejects requests without its CSRF token (419). */
    private suspend fun player(episodeUrl: String): PlayerData? {
        val page = app.get(episodeUrl)
        val episodeId = HStreamParser.episodeId(page.document) ?: return null
        val cookies = page.cookies
        val xsrf = cookies["XSRF-TOKEN"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
        val response = app.post(
            "$mainUrl/player/api",
            headers = mapOf("X-XSRF-TOKEN" to xsrf, "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"),
            cookies = cookies,
            referer = episodeUrl,
            data = mapOf("episode_id" to episodeId),
        )
        if (!response.isSuccessful) throw ErrorLoadingException("Player API ${response.code}")
        return response.parsedSafe<PlayerData>()
    }

    /** Like the player, pick a random mirror; skip any that does not answer for this episode. */
    private suspend fun pickDomain(domains: List<String>, path: String): String? {
        val shuffled = domains.map { it.trimEnd('/') }.shuffled()
        for (domain in shuffled) {
            val ok = try {
                app.get("$domain/$path/720/manifest.mpd", timeout = PROBE_TIMEOUT_S).isSuccessful
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(name, "Mirror $domain failed (${e.javaClass.simpleName})")
                false
            }
            if (ok) return domain
        }
        return shuffled.firstOrNull()
    }

    /** Hardware or platform software decoder; desktop builds have no MediaCodecList and get every quality. */
    private val av1Supported: Boolean by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
            }
        } catch (e: Throwable) {
            true
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    data class PlayerData(
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("stream_domains") val streamDomains: List<String>? = null,
        @JsonProperty("interpolated") val interpolated: Int? = null,
        @JsonProperty("interpolated_uhd") val interpolatedUhd: Int? = null,
    )

    private companion object {
        const val HOME_TTL_MS = 5 * 60 * 1000L
        const val PROBE_TIMEOUT_S = 10L
    }
}
