package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MoviesMod is a WordPress blog (its REST API needs a login) whose download buttons lead to modpro.blog link pages,
 * then through a SafeLink ad gate to DriveSeed, which hosts each file on R2 and behind a Google "Instant" mirror.
 */
class MoviesMod : MainAPI() {
    // The site rotates its domain; the modpro, gate and DriveSeed hosts are always read from the page links.
    override var mainUrl = "https://moviesmod.meme"
    override var name = "MoviesMod"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "web-series/" to "Web Series",
        "anime/" to "Anime",
        "ott/netflix/" to "Netflix",
        "ott/amazon-prime-video/" to "Amazon Prime",
        "ott/disney-plus/" to "Disney+",
        "ott/hbo-max/" to "HBO Max",
        "movies-by-genre/action/" to "Action",
        "movies-by-genre/comedy/" to "Comedy",
        "movies-by-genre/horror/" to "Horror",
        "movies-by-genre/sci-fi/" to "Sci-Fi",
        "language/korean/" to "Korean",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        // Past the last page the site answers 404 with no cards.
        val doc = app.get(url, timeout = 30L).document
        val cards = MoviesModParser.cards(doc)
        return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext = MoviesModParser.hasNext(doc))
    }

    private fun MmCard.toSearchResponse(): SearchResponse {
        val clean = MoviesModParser.cleanTitle(title).ifBlank { title }
        val year = MoviesModParser.year(title)
        return if (MoviesModParser.isSeries(title)) {
            newTvSeriesSearchResponse(clean, url, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(clean, url, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/search/$q/" else "$mainUrl/search/$q/page/$page/"
        val res = app.get(url, timeout = 30L)
        // A page past the last one is a 404.
        if (!res.isSuccessful) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = res.document
        return newSearchResponseList(MoviesModParser.cards(doc).map { it.toSearchResponse() }, hasNext = MoviesModParser.hasNext(doc))
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 30L).document
        val rawTitle = doc.selectFirst("h1.title, h1.single-title, h1")?.text()?.trim().orEmpty()
        val details = MoviesModParser.details(doc)
        val title = details.title ?: MoviesModParser.cleanTitle(rawTitle).ifBlank { rawTitle }
        if (title.isBlank()) throw ErrorLoadingException("No title on $url")

        val sources = MoviesModParser.groups(doc).flatMap { group ->
            group.buttons.filterNot { MoviesModParser.isBatch(it.label) }.map { MmSource(group.heading, it.url) }
        }
        val episodeLists = sources.filter { MoviesModParser.isEpisodeList(it.url) }

        val response: LoadResponse = if (episodeLists.isNotEmpty()) {
            val type = if (MoviesModParser.isAnime(doc)) TvType.Anime else TvType.TvSeries
            newTvSeriesLoadResponse(title, url, type, episodes(episodeLists))
        } else {
            if (sources.isEmpty()) throw ErrorLoadingException("No download links on $url")
            newMovieLoadResponse(title, url, TvType.Movie, MmLink(sources).toJson())
        }
        return response.apply {
            posterUrl = details.poster
            plot = details.plot
            year = details.year ?: MoviesModParser.year(rawTitle)
            tags = details.genres
            score = details.rating?.let { Score.from10(it) }
            addActors(details.actors)
            addImdbId(details.imdbId)
        }
    }

    /**
     * Each season × quality has its own episode list page, and episodes.modpro.blog answers 429 after about six
     * requests, so only one page per season is read here: the quality whose heading claims the most episodes
     * (`E06 Added`). Each episode carries every quality page of its season; [loadLinks] reads them for its gates.
     */
    private suspend fun episodes(lists: List<MmSource>): List<Episode> =
        lists.groupBy { MoviesModParser.season(it.heading) }.toSortedMap().flatMap { (season, pages) ->
            val probe = pages.maxByOrNull { MoviesModParser.addedEpisodes(it.heading) ?: 0 } ?: return@flatMap emptyList()
            val numbers = safely("episode list ${probe.url}") {
                val res = page(probe.url)
                MoviesModParser.episodes(res.document).map { it.first }.also {
                    if (it.isEmpty()) Log.w(name, "No episodes on ${probe.url}: HTTP ${res.code} '${res.document.title()}'")
                }
            }.orEmpty()
            numbers.sorted().map { number ->
                newEpisode(MmLink(pages, number).toJson()) {
                    this.season = season
                    episode = number
                    name = "Episode $number"
                }
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = tryParseJson<MmLink>(data) ?: return false
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val emit: (ExtractorLink) -> Unit = { if (emitted.add(it.url)) callback(it) }
        link.sources.amap { source ->
            safely("source ${source.heading}") {
                val doc = page(source.url).document
                if (link.episode != null) {
                    val gate = MoviesModParser.episodes(doc).firstOrNull { it.first == link.episode }?.second ?: return@safely
                    resolveGate(MmSource(source.heading, gate), emit)
                } else {
                    // A movie's links page offers several servers that usually end at the same file; stop at the first that plays.
                    for (server in MoviesModParser.servers(doc)) {
                        if (resolveGate(MmSource(source.heading, server), emit)) break
                    }
                }
            }
        }
        return emitted.isNotEmpty()
    }

    /** A modpro.blog page. Those hosts answer 429 with a Retry-After of ~9 s to bursts, so wait it out a few times. */
    private suspend fun page(url: String): NiceResponse {
        repeat(3) {
            val res = app.get(url, timeout = 30L)
            if (res.code != 429) return res
            val wait = res.headers["retry-after"]?.toLongOrNull()?.coerceIn(1L, 10L) ?: 5L
            Log.i(name, "429 from $url, retrying in ${wait}s")
            delay(wait * 1000)
        }
        return app.get(url, timeout = 30L)
    }

    /**
     * Gate → DriveSeed file page → its mirrors. Cloud (R2) and Direct (Cloudflare Worker) honour Range and seek;
     * Instant (Google) does not, so the player has to read the whole MKV before the first frame. True when anything
     * was emitted.
     */
    private suspend fun resolveGate(source: MmSource, emit: (ExtractorLink) -> Unit): Boolean {
        val target = bypass(source.url) ?: return false
        val origin = originOf(target)
        // DriveSeed's /r?key= page only redirects to /file/<code> with a relative location.replace.
        val first = app.get(target, timeout = 30L)
        val filePage = MoviesModParser.redirect(first.text)?.let { app.get(absolute(origin, it), timeout = 30L) } ?: first
        val file = MoviesModParser.driveFile(filePage.document)
        val label = MoviesModParser.tags(source.heading)
        val height = MoviesModParser.quality(file.name ?: source.heading).takeIf { it > 0 }
            ?: MoviesModParser.quality(source.heading).takeIf { it > 0 }
        suspend fun link(source: String, kind: String, url: String, quality: Int?) = emit(newExtractorLink(
            source, listOf("$name $kind", label).filter { it.isNotBlank() }.joinToString(" · "), url, ExtractorLinkType.VIDEO
        ) { this.quality = quality ?: Qualities.Unknown.value })

        val cloud = file.cloud?.takeIf { hostReachable(it) }
        cloud?.let { link(name, "Cloud", it, height) }
        val direct = file.direct?.let { directLink(it) }
        direct?.let { link(name, "Direct", it, height) }
        val instant = file.instant?.let { safely("instant ${file.name}") {
            app.get(it, allowRedirects = false, timeout = 30L).headers["location"]?.let { MoviesModParser.instantTarget(it) }
        } }
        // Next to a seekable link Instant ranks as Unknown so the seekable one is picked; alone it keeps its height so
        // the best quality plays.
        instant?.let { link("$name Instant", "Instant (no seek)", it, if (cloud == null && direct == null) height else null) }

        val any = cloud != null || direct != null || instant != null
        if (!any) Log.w(name, "No playable mirror on ${filePage.url} (${file.name})")
        return any
    }

    /** The worker link of `/wfile/<code>?type=1`, if it serves the file; per file it answers 206, 403 or 500. */
    private suspend fun directLink(wfile: String): String? = safely("direct $wfile") {
        val url = MoviesModParser.workerLink(app.get("$wfile?type=1", timeout = 30L).document) ?: return@safely null
        url.takeIf { rangeStatus(it) == 206 }
    }

    /**
     * The SafeLink gate: GET the sid page, POST its landing form, POST the step form, then GET `?lp_go=<token>` with
     * cookie `<token>=<_lp_http2>`; the answer `location.replace`s to the file host. Returns that target URL.
     */
    private suspend fun bypass(url: String): String? {
        val origin = originOf(url)
        val landing = MoviesModParser.gateForm(app.get(url, timeout = 30L).document) ?: return null
        val step = MoviesModParser.gateForm(app.post(landing.action, data = landing.fields, timeout = 30L).document) ?: return null
        val page = app.post(step.action, data = step.fields, timeout = 30L).text
        val (key, token) = MoviesModParser.goToken(page) ?: return null
        val http2 = MoviesModParser.http2(step) ?: return null
        val go = app.get("$origin/?$key=$token", cookies = mapOf(token to http2), allowRedirects = false, timeout = 30L)
        return go.headers["location"] ?: MoviesModParser.redirect(go.text)
    }

    /**
     * Some ISPs block `*.r2.dev`: the connection hangs until the player times out a minute later. One probe per host
     * decides whether its links are worth emitting; the answer is kept for a few minutes.
     */
    private suspend fun hostReachable(url: String): Boolean {
        val host = URI(url).host ?: return false
        // Every quality probes the same host at once; the first one decides and the rest read its answer.
        return probeLock.withLock {
            probes[host]?.takeIf { System.currentTimeMillis() - it.second < PROBE_TTL_MS }?.let { return@withLock it.first }
            val code = rangeStatus(url)
            val ok = code != null && code in 200..299
            if (!ok) Log.w(name, "$host unreachable (${code ?: "timeout"}); skipping its links")
            probes[host] = ok to System.currentTimeMillis()
            ok
        }
    }

    /**
     * Status of a one-byte Range request, or null if the host doesn't answer within a few seconds. NiceHttp's timeout
     * doesn't cover OkHttp's 30 s connect, so this uses its own short-timeout client, and the response is closed
     * unread so a host that ignores Range doesn't stream the whole file.
     */
    private suspend fun rangeStatus(url: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            probeClient.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { it.code }
        }.getOrNull()
    }

    private val probeClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun originOf(url: String) = URI(url).let { "${it.scheme}://${it.host}" }

    private fun absolute(origin: String, path: String) = if (path.startsWith("http")) path else "$origin/${path.trimStart('/')}"

    private suspend fun <T> safely(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(name, "$label failed: ${e.message}")
        null
    }

    private val probes = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val probeLock = Mutex()

    private companion object {
        const val PROBE_TTL_MS = 10 * 60 * 1000L
    }
}
