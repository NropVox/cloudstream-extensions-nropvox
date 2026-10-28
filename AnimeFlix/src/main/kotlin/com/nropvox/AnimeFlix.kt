package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AnimeFlix is a WordPress blog (its REST API needs a login) whose download cards lead to episodes.animeflix.my list
 * pages; each episode's `/getlink/` redirects straight to DriveSeed, which hosts the file on R2 and behind a Google
 * "Instant" mirror. Unlike MoviesMod there is no SafeLink gate in between.
 */
class AnimeFlix : MainAPI() {
    override var mainUrl = "https://animeflix.my"
    override var name = "AnimeFlix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "" to "New Episodes",
        "category/ongoing/" to "Ongoing",
        "?af_status=Completed" to "Complete Seasons",
        "category/movies/" to "Movies",
        "category/genre/action/" to "Action",
        "category/genre/isekai/" to "Isekai",
        "category/genre/romance/" to "Romance",
        "category/genre/comedy/" to "Comedy",
        "category/genre/fantasy/" to "Fantasy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(AnimeFlixParser.pageUrl(mainUrl, request.data, page), timeout = 30L)
        // Past the last page the site answers 404 with no cards.
        val doc = res.document
        return newHomePageResponse(
            request.name, AnimeFlixParser.cards(doc).map { it.toSearchResponse() }, hasNext = AnimeFlixParser.hasNext(doc)
        )
    }

    private fun AfCard.toSearchResponse(): SearchResponse = if (movie) {
        newMovieSearchResponse(title, url, TvType.AnimeMovie) { posterUrl = poster }
    } else {
        newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val res = app.get(AnimeFlixParser.searchUrl(mainUrl, query, page), timeout = 30L)
        // A page past the last one is a 404.
        if (!res.isSuccessful) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = res.document
        return newSearchResponseList(AnimeFlixParser.cards(doc).map { it.toSearchResponse() }, hasNext = AnimeFlixParser.hasNext(doc))
    }

    /** The theme's autocomplete: at most 6 hits, and nothing under 2 characters. */
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        if (query.trim().length < 2) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val rows = tryParseJson<List<AfSuggest>>(app.get("$mainUrl/?af_suggest=$q", timeout = 15L).text) ?: return null
        return rows.mapNotNull { row ->
            val url = row.u?.ifBlank { null } ?: return@mapNotNull null
            val title = row.t?.ifBlank { null } ?: return@mapNotNull null
            AfCard(url, title, row.i, row.m.orEmpty().trim().startsWith("Movie")).toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, timeout = 30L).document
        val details = AnimeFlixParser.details(doc)
        val title = details.title ?: throw ErrorLoadingException("No title on $url")
        val seasons = AnimeFlixParser.seasons(doc, AnimeFlixParser.leadingInt(details.info["Season"]) ?: 1)
        if (seasons.isEmpty()) throw ErrorLoadingException("No download cards on $url")

        // Every quality's list page, read once: ongoing seasons can be an episode further along in one quality.
        val lists = seasons.amap { season ->
            season to season.qualities.amap { quality ->
                safely("episode list ${quality.url}") { AnimeFlixParser.entries(app.get(quality.url, timeout = 30L).document) }.orEmpty()
            }
        }
        val anyEpisode = lists.any { (_, pages) -> pages.any { entries -> entries.any { it.episode != null } } }
        val anyLink = lists.any { (_, pages) -> pages.any { it.isNotEmpty() } }
        if (!anyLink) throw ErrorLoadingException("No episode links on the list pages of $url")

        // A movie's list page names qualities (`Link for – 1036p`) instead of episodes.
        val response: LoadResponse = if (!anyEpisode && seasons.size == 1) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, AfLink(sources(seasons.single())).toJson())
        } else {
            val episodes: List<Episode> = lists.sortedBy { it.first.season }.flatMap { (season, pages) ->
                val numbers = pages.flatten().mapNotNull { it.episode }.distinct().sorted()
                numbers.map { number ->
                    newEpisode(AfLink(sources(season), number).toJson()) {
                        this.season = season.season
                        episode = number
                        name = "Episode $number"
                    }
                }
            }
            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                showStatus = when (details.info["Status"]?.lowercase()) {
                    "ongoing" -> ShowStatus.Ongoing
                    "completed" -> ShowStatus.Completed
                    else -> null
                }
            }
        }
        return response.apply {
            posterUrl = details.poster
            plot = details.plot
            year = AnimeFlixParser.leadingInt(details.info["Released"])
            tags = details.genres
            score = details.rating?.let { Score.from10(it) }
            recommendations = AnimeFlixParser.recommendations(doc).map { it.toSearchResponse() }
        }
    }

    private fun sources(season: AfSeason) = season.qualities.map { AfSource(AnimeFlixParser.label(it), it.url) }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = tryParseJson<AfLink>(data) ?: return false
        val found = link.sources.distinctBy { it.url }.amap { source ->
            safely("source ${source.label}") {
                val entries = AnimeFlixParser.entries(app.get(source.url, timeout = 30L).document)
                if (link.episode != null) {
                    val entry = entries.firstOrNull { it.episode == link.episode } ?: return@safely emptyList()
                    resolve(source.label, entry.url)
                } else {
                    // A movie's list page can hold more qualities than its card names (`Link for – 690p`).
                    entries.amap { entry -> resolve(entry.label.substringAfter('–').trim().ifBlank { source.label }, entry.url) }.flatten()
                }
            }.orEmpty()
        }.flatten()
        // The player keeps emission order between equal qualities: seekable and MP4 links first, then MKVs smallest first.
        val (mkvs, others) = found.partition { it.mkvSize != null }
        (others + mkvs.sortedBy { it.mkvSize }).map { it.link }.distinctBy { it.url }.forEach(callback)
        return found.isNotEmpty()
    }

    /**
     * `/getlink/` → DriveSeed `/r` → `/file/<code>` → its mirrors. Cloud (R2) and Direct (worker) honour Range and
     * seek; Instant (Google) does not. Every hop goes through [hop], on a fresh connection.
     */
    private suspend fun resolve(label: String, getlink: String): List<Candidate> = safely("getlink $label") {
        val target = hop(getlink).location ?: run {
            Log.w(name, "No redirect from $getlink")
            return@safely emptyList()
        }
        val origin = originOf(target)
        // DriveSeed's /r?key= page only redirects to /file/<code> with a relative location.replace.
        val first = hop(target)
        val filePage = AnimeFlixParser.redirect(first.body)?.let { hop(absolute(origin, it)) } ?: first
        val file = AnimeFlixParser.driveFile(AnimeFlixParser.body(filePage.body, filePage.url))
        val height = AnimeFlixParser.quality(file.name ?: "").takeIf { it > 0 } ?: AnimeFlixParser.quality(label).takeIf { it > 0 }
        suspend fun link(source: String, kind: String, url: String, quality: Int?) = newExtractorLink(
            source, "$name $kind · $label", url, ExtractorLinkType.VIDEO
        ) { this.quality = quality ?: Qualities.Unknown.value }

        // The R2 probe can take its full 5 s timeout, so the other mirrors resolve meanwhile.
        val (cloud, direct, instant) = coroutineScope {
            val cloud = async { file.cloud?.takeIf { hostReachable(it) } }
            val direct = async { file.direct?.let { directLink(it) } }
            val instant = async { file.instant?.let { safely("instant ${file.name}") { hop(it).location?.let { AnimeFlixParser.instantTarget(it) } } } }
            Triple(cloud.await(), direct.await(), instant.await())
        }
        val links = mutableListOf<Candidate>()
        cloud?.let { links += Candidate(link(name, "Cloud", it, height)) }
        direct?.let { links += Candidate(link(name, "Direct", it, height)) }
        instant?.let {
            // Google ignores Range. A faststart MP4 still starts at once, so alone it keeps its height; an MKV has to
            // be read whole before the first frame, so it ranks Unknown and [loadLinks] puts the smallest first.
            val mkv = file.name?.endsWith(".mkv", true) == true
            val quality = if (links.isEmpty() && !mkv) height else null
            links += Candidate(link("$name Instant", "Instant (no seek)", it, quality), if (mkv) file.sizeMb ?: Double.MAX_VALUE else null)
        }
        if (links.isEmpty()) Log.w(name, "No playable mirror on ${filePage.url} (${file.name})")
        links
    }.orEmpty()

    /** A link of [resolve]; [mkvSize] (MB) is set on Instant MKVs, which play only after a full download. */
    private class Candidate(val link: ExtractorLink, val mkvSize: Double? = null)

    private class Hop(val url: String, val code: Int, val location: String?, val body: String)

    /**
     * One GET without following redirects. On the test phone, requests to driveseed.org sometimes hung until the 30 s
     * timeout through the app's pooled client while the same hop answers in under a second otherwise, so these hops
     * use their own unpooled client with a short timeout and try again on a fresh connection.
     */
    private suspend fun hop(url: String): Hop = withContext(Dispatchers.IO) {
        var last: IOException? = null
        repeat(HOP_ATTEMPTS) { attempt ->
            try {
                return@withContext hopClient.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
                    Hop(url, it.code, it.header("location"), it.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                last = e
                Log.w(name, "hop ${URI(url).host} attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        throw last ?: IOException("hop $url failed")
    }

    private val hopClient by lazy {
        app.baseClient.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** The worker link of `/wfile/<code>?type=1`, if it serves the file. */
    private suspend fun directLink(wfile: String): String? = safely("direct $wfile") {
        val page = hop("$wfile?type=1")
        val url = AnimeFlixParser.workerLink(AnimeFlixParser.body(page.body, page.url)) ?: return@safely null
        url.takeIf { rangeStatus(it) == 206 }
    }

    /**
     * Some ISPs block `*.r2.dev`: the connection hangs until the player times out a minute later. One probe per host
     * decides whether its links are worth emitting; the answer is kept for a few minutes.
     */
    private suspend fun hostReachable(url: String): Boolean {
        val host = URI(url).host ?: return false
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
     * doesn't cover OkHttp's 30 s connect, so this uses its own short-timeout client.
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
        const val HOP_ATTEMPTS = 3
    }
}
