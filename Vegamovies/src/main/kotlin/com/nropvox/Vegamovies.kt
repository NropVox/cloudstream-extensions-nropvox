package com.nropvox

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
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
import com.lagradost.nicehttp.NiceResponse
import com.nropvox.VegamoviesParser.Host
import com.nropvox.VegamoviesParser.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URI
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

    // vcloud.beer answers plain clients with a Cloudflare challenge; the other hops need nothing.
    private val cloudflare by lazy { CloudflareKiller() }
    // One WebView must solve the challenge and cache its cookie; parallel solvers for the same host stall each other.
    @Volatile private var vcloudWarmUntil = 0L
    private val vcloudSlots = Semaphore(2)
    private val vcloudLock = Mutex()

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
                val numbers = safely("episode list") {
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
            safely("nexdrive ${page.label}") {
                val entries = VegamoviesParser.entries(app.get(page.url, timeout = 30L).document)
                    .filter { it.episode == link.episode }
                entries.amap { entry ->
                    when (VegamoviesParser.host(entry.url)) {
                        Host.GDIRECT -> safely("G-Direct") {
                            gdirect(entry.url)?.let { sink.add(page, "G-Direct", it, streamable = false) }
                        }
                        Host.VCLOUD -> safely("V-Cloud") { vcloud(entry.url, page, sink) }
                        Host.HUBCLOUD -> safely("HubCloud") { hubcloud(entry.url, page, sink) }
                        Host.VDRIVE -> safely("V-Drive") { vdrive(entry.url, page, sink) }
                        Host.PIXELDRAIN -> safely("Pixeldrain") {
                            pixeldrain(entry.url)?.let { sink.add(page, "Pixeldrain", it, streamable = true) }
                        }
                        // Filepress (interactive Turnstile), Gofile (signed rotating token, bans stale ones),
                        // VikingFile (Turnstile), 1fichier and MegaUp (wait/captcha pages) cannot be resolved headless.
                        Host.OTHER -> Unit
                    }
                }
            }
        }
        return sink.isNotEmpty()
    }

    /**
     * Streamable links answer HTTP range requests, so the player can seek. Download-only links (Google
     * `video-downloads` files behind G-Direct, 10Gbps and Vegadrop originals) ignore Range and resend the whole
     * file: they play from the start but cannot seek. Those rank as Unknown quality (priority 4) under their own
     * source name so every 480p+ stream is picked first, and users can still reorder them in source priority.
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

    private suspend fun gdirect(embedUrl: String): String? =
        VegamoviesParser.directUrl(app.get(embedUrl, timeout = 30L).text)

    private suspend fun vcloud(url: String, page: VgPage, sink: Sink) {
        val landing = vcloudGet(url).text
        val next = VegamoviesParser.vcloudNext(landing) { base64Decode(it) }
        Log.d(name, "V-Cloud landing ${landing.length} chars, token url ${if (next != null) "found" else "missing"}")
        if (next == null) return
        val mirrors = VegamoviesParser.mirrors(vcloudGet(next).document)
        Log.d(name, "V-Cloud servers: ${mirrors.joinToString { it.first }}")
        cloudMirrors("V-Cloud", mirrors, page, sink)
    }

    /**
     * The first V-Cloud request of a while solves the challenge alone; the rest wait for it, then share its cached
     * cookie two at a time. Cloudflare clearance outlives [VCLOUD_WARM_MS], after which one request solves again.
     */
    private suspend fun vcloudGet(url: String): NiceResponse {
        if (System.currentTimeMillis() >= vcloudWarmUntil) vcloudLock.withLock {
            if (System.currentTimeMillis() >= vcloudWarmUntil) {
                return app.get(url, interceptor = cloudflare, timeout = 60L)
                    .also { if (it.code in 200..299) vcloudWarmUntil = System.currentTimeMillis() + VCLOUD_WARM_MS }
            }
        }
        // CloudflareKiller blocks its OkHttp slot while it sends the cookie request to the same host, and OkHttp
        // allows 5 calls per host: 5 parallel V-Cloud requests deadlock, so at most 2 (+2 inner) run at once.
        return vcloudSlots.withPermit { app.get(url, interceptor = cloudflare, timeout = 60L) }
    }

    // HubCloud is the same server list as V-Cloud without the Cloudflare challenge.
    private suspend fun hubcloud(url: String, page: VgPage, sink: Sink) {
        val next = VegamoviesParser.hubcloudNext(app.get(url, timeout = 30L).document) ?: return
        val mirrors = VegamoviesParser.mirrors(app.get(next, referer = url, timeout = 30L).document)
        Log.d(name, "HubCloud servers: ${mirrors.joinToString { it.first }}")
        cloudMirrors("HubCloud", mirrors, page, sink)
    }

    private suspend fun cloudMirrors(host: String, mirrors: List<Pair<String, String>>, page: VgPage, sink: Sink) {
        mirrors.amap { (server, target) ->
            safely("$host $server") {
                when {
                    // Presigned R2 URLs with range support.
                    server.startsWith("FSLv2", ignoreCase = true) -> sink.add(page, "$host FSLv2", target, streamable = true)
                    // V-Cloud's plain FSL mirror (pub-*.r2.dev) resolves but never connects (TCP timeout) from
                    // the networks tested; HubCloud's is a presigned r2.cloudflarestorage.com URL like FSLv2.
                    server.startsWith("FSL", ignoreCase = true) && !URI(target).host.orEmpty().endsWith(".r2.dev") ->
                        sink.add(page, "$host FSL", target, streamable = true)
                    server.contains("10Gbps", ignoreCase = true) ->
                        redirectedFile(target)?.let { sink.add(page, "$host 10Gbps", it, streamable = false) }
                    server.contains("Pixel", ignoreCase = true) ->
                        pixeldrain(target)?.let { sink.add(page, "$host Pixeldrain", it, streamable = true) }
                }
            }
        }
    }

    /**
     * V-Drive mirrors one file to several hosts; `/api/file/<id>` says which are ready. Vegadrop (`skydrop`) and
     * Pixeldrain play; the others are Turnstile or token gated (see [loadLinks]).
     */
    private suspend fun vdrive(url: String, page: VgPage, sink: Sink) {
        val id = VegamoviesParser.vdriveId(url) ?: return
        val origin = URI(url).let { "${it.scheme}://${it.host}" }
        // The share domain 301s to the current one; later calls need that origin as Referer.
        val response = app.get("$origin/api/file/$id", headers = JSON, timeout = 30L)
        val base = URI(response.url).let { "${it.scheme}://${it.host}" }
        val ready = response.parsedSafe<VdFile>()?.providers.orEmpty().filter { it.status == "ready" }.mapNotNull { it.provider }
        ready.amap { provider ->
            safely("V-Drive $provider") {
                when (provider) {
                    "skydrop" -> vegadrop(base, id, page, sink)
                    "pixeldrain" -> {
                        val target = app.get("$base/__vega_bridge/link/$id?provider=pixeldrain", allowRedirects = false, timeout = 30L)
                            .headers["location"] ?: return@safely
                        pixeldrain(target)?.let { sink.add(page, "V-Drive Pixeldrain", it, streamable = true) }
                    }
                }
            }
        }
    }

    /**
     * Vegadrop hands out a Google Photos URL (`lh3.googleusercontent.com/pw/…=dv`). `=dv` is the original file
     * without range support; the `=m37`/`=m22`/`=m18` transcodes (1080p/720p/360p MP4, first audio track only) seek.
     */
    private suspend fun vegadrop(base: String, id: String, page: VgPage, sink: Sink) {
        var original: String? = null
        // The first call starts preparation and answers 202 `ready:false`; the page polls for up to 90 s.
        for (attempt in 0 until 8) {
            val link = app.get("$base/download-link/$id/skydrop", referer = "$base/", headers = JSON, timeout = 30L).parsedSafe<VdLink>()
            original = link?.url
            if (original != null) break
            delay((link?.retryAfterMs ?: 2500L).coerceIn(1500L, 5000L))
        }
        val file = original ?: return
        val stem = file.substringBeforeLast('=')
        // A missing transcode answers 404; an available one 302s to googlevideo.
        val best = VEGADROP_TRANSCODES.amap { (suffix, height) ->
            (height to "$stem=$suffix").takeIf { app.get(it.second, allowRedirects = false, timeout = 15L).code in 300..399 }
        }.filterNotNull().maxByOrNull { it.first }
        best?.let { (height, url) -> sink.add(page, "Vegadrop transcode, 1 audio", url, streamable = true, quality = height) }
        sink.add(page, "Vegadrop original", file, streamable = false)
    }

    private suspend fun pixeldrain(url: String): String? {
        val id = VegamoviesParser.pixeldrainId(url) ?: return null
        val info = app.get("https://pixeldrain.com/api/file/$id/info", timeout = 15L).parsedSafe<PdInfo>()
        return if (info?.canDownload == true) "https://pixeldrain.com/api/file/$id" else null
    }

    // 10Gbps hops through two 302s and ends on a `dl.php?link=<file>` ad wrapper.
    private suspend fun redirectedFile(start: String): String? {
        var current = start
        repeat(5) {
            if ("dl.php?link=" in current) return current.substringAfter("dl.php?link=")
            val location = app.get(current, allowRedirects = false, timeout = 30L).headers["location"] ?: return null
            current = URI(current).resolve(location).toString()
        }
        return current.takeIf { "dl.php?link=" in it }?.substringAfter("dl.php?link=")
    }

    private suspend fun <T> safely(operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Do not log messages: HTTP errors may carry signed file URLs.
        Log.d(name, "$operation failed (${e.javaClass.simpleName})")
        null
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val SEARCH_PAGE_SIZE = 15
        val JSON = mapOf("Accept" to "application/json")
        val VEGADROP_TRANSCODES = listOf("m37" to 1080, "m22" to 720, "m18" to 360)
        const val VCLOUD_WARM_MS = 10 * 60 * 1000L
    }
}
