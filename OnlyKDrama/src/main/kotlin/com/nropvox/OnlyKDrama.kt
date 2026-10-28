package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
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
import com.nropvox.OnlyKDramaParser.Host
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.util.Collections

/** WordPress (DooPlay) download site behind a Cloudflare challenge; each post lists file-host links per episode. */
class OnlyKDrama : MainAPI() {
    override var mainUrl = "https://onlykdrama.shop"
    override var name = "OnlyKDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)

    private val cloudflare by lazy { CloudflareKiller() }
    private val site by lazy { Challenged(cloudflare) }
    // XCloud's server list (xcloud1.lol) has its own challenge; its landing page and the R2 files have none.
    private val xcloud by lazy { Challenged(cloudflare) }
    private val hosts = NexdriveResolver(name)

    override val mainPage = mainPageOf(
        "drama/" to "Latest Dramas",
        "genres/ongoing/" to "Ongoing",
        "genres/koren-drama/" to "Korean Dramas",
        "genres/chinese-drama/" to "Chinese Dramas",
        "genres/hindi-dubbed-drama/" to "Hindi Dubbed Dramas",
        "genres/english-dubbed-drama/" to "English Dubbed Dramas",
        "movies/" to "Movies",
        "genres/hindi-dubbed-movie/" to "Hindi Dubbed Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        val response = site.get(url)
        val doc = response.document
        val cards = OnlyKDramaParser.cards(doc, mainUrl)
        com.lagradost.api.Log.i(name, "TRACE home $url -> ${response.code} ${response.text.length} chars, ${cards.size} cards, title=${doc.title()}")
        return newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext = OnlyKDramaParser.hasNext(doc))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val response = site.get(url)
        // Past the last page WordPress answers 404.
        if (response.code == 404) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = response.document
        val cards = OnlyKDramaParser.cards(doc, mainUrl)
        return newSearchResponseList(cards.map { it.toSearchResponse() }, hasNext = cards.isNotEmpty() && OnlyKDramaParser.hasNext(doc))
    }

    private fun OkCard.toSearchResponse(): SearchResponse = if (movie) {
        newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    } else {
        newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = site.get(url).document
        val title = OnlyKDramaParser.title(doc) ?: throw ErrorLoadingException("No title on $url")
        val entries = OnlyKDramaParser.entries(doc)

        val load: LoadResponse = if (OnlyKDramaParser.isMovie(url)) {
            if (entries.isEmpty()) throw ErrorLoadingException("No playable download links on $url")
            newMovieLoadResponse(title, url, TvType.Movie, OkLinks(entries.map { it.file }).toJson())
        } else {
            // One episode is listed once per release and host; every link of it is resolved together.
            val episodes = entries.filter { it.episode != null }
                .groupBy { (it.season ?: 1) to it.episode!! }
                .toSortedMap(compareBy({ it.first }, { it.second }))
                .map { (key, links) ->
                    newEpisode(OkLinks(links.map { it.file }.distinctBy { it.url }).toJson()) {
                        season = key.first
                        episode = key.second
                        name = "Episode ${key.second}"
                    }
                }
            if (episodes.isEmpty()) throw ErrorLoadingException("No playable episode links on $url")
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes).apply {
                showStatus = when (OnlyKDramaParser.ongoing(doc)) {
                    true -> ShowStatus.Ongoing
                    false -> ShowStatus.Completed
                    null -> null
                }
            }
        }
        return load.apply {
            posterUrl = OnlyKDramaParser.poster(doc)
            plot = OnlyKDramaParser.plot(doc)
            year = OnlyKDramaParser.year(doc)
            tags = OnlyKDramaParser.genres(doc)
            score = OnlyKDramaParser.rating(doc)?.let { Score.from10(it) }
            addTrailer(OnlyKDramaParser.trailer(doc))
            addTMDbId(OnlyKDramaParser.tmdbId(doc)?.toString())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = tryParseJson<OkLinks>(data) ?: return false
        val sink = Sink(callback)
        links.files.amap { file ->
            val add = NexdriveSink { host, url, streamable, _ -> sink.add(file, host, url, streamable) }
            when (OnlyKDramaParser.host(file.url)) {
                Host.HUBCLOUD, Host.PIXELDRAIN -> hosts.resolve(file.url, add)
                Host.GDFLIX -> safely(name, "GDFlix") {
                    val pixeldrain = OnlyKDramaParser.gdflixPixeldrain(app.get(file.url, timeout = 30L).document) ?: return@safely
                    hosts.resolve(pixeldrain) { _, url, streamable, _ -> sink.add(file, "GDFlix Pixeldrain", url, streamable) }
                }
                Host.XCLOUD -> safely(name, "XCloud") { xcloud(file, sink) }
                Host.DIRECT -> sink.add(file, "Worker", file.url, streamable = true)
                Host.OTHER -> Unit
            }
        }
        return sink.isNotEmpty()
    }

    private suspend fun xcloud(file: OkFile, sink: Sink) {
        val landing = app.get(file.url, timeout = 30L)
        val servers = OnlyKDramaParser.xcloudServers(landing.document) ?: return
        val list = xcloud.get(servers, referer = landing.url)
        OnlyKDramaParser.xcloudMirrors(list.document, list.url).amap { (server, redirect) ->
            safely(name, "XCloud $server") {
                // With a saved clearance CloudflareKiller replays requests on a client that follows redirects, which
                // would start downloading the file; send its cookies by hand and read the Location instead.
                val headers = cloudflare.getCookieHeaders(redirect).toMap()
                val target = app.get(redirect, headers = headers, referer = servers, allowRedirects = false, timeout = 30L)
                    .headers["location"]?.takeIf { it.startsWith("http") } ?: return@safely
                sink.add(file, "XCloud $server", target, streamable = true)
            }
        }
    }

    /** Download-only links (Google `video-downloads`) cannot seek; they rank as Unknown so streams are picked first. */
    private inner class Sink(private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        fun isNotEmpty() = emitted.isNotEmpty()

        suspend fun add(file: OkFile, host: String, url: String, streamable: Boolean) {
            if (!emitted.add(url)) return
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, OnlyKDramaParser.release(file.name), file.size).joinToString(" · ")
            val height = OnlyKDramaParser.quality(file.name).takeIf { it > 0 } ?: Qualities.Unknown.value
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                quality = if (streamable) height else Qualities.Unknown.value
            })
        }
    }
}

/**
 * A host behind a Cloudflare challenge. The first request of a while solves it alone; the rest share the cached cookie
 * two at a time, because CloudflareKiller sends a second request to the same host from inside its interceptor and
 * OkHttp allows only 5 per host: 5 parallel calls deadlock. A failed solve takes the WebView's full minute, so the
 * requests queued behind it fail at once for a while instead of each spending another minute on the same challenge.
 */
private class Challenged(private val cloudflare: CloudflareKiller) {
    @Volatile private var warmUntil = 0L
    @Volatile private var coldUntil = 0L
    private val lock = Mutex()
    private val slots = Semaphore(2)

    suspend fun get(url: String, referer: String? = null): NiceResponse {
        if (System.currentTimeMillis() >= warmUntil) lock.withLock {
            if (System.currentTimeMillis() >= warmUntil) {
                if (System.currentTimeMillis() < coldUntil) throw ErrorLoadingException("$FAILED [cold]")
                val start = System.currentTimeMillis()
                val response = app.get(url, referer = referer, interceptor = cloudflare, timeout = 60L)
                com.lagradost.api.Log.i("OnlyKDrama", "TRACE solve $url -> ${response.code} in ${System.currentTimeMillis() - start} ms")
                if (response.code in 200..299) warmUntil = System.currentTimeMillis() + WARM_MS
                else if (challenged(response)) coldUntil = System.currentTimeMillis() + COLD_MS
                return checked(response, "solve ${System.currentTimeMillis() - start}ms")
            }
        }
        return checked(slots.withPermit { app.get(url, referer = referer, interceptor = cloudflare, timeout = 60L) }, "replay")
    }

    private fun challenged(response: NiceResponse) =
        response.code in listOf(403, 503) && response.headers["server"].orEmpty().startsWith("cloudflare")

    private fun checked(response: NiceResponse, how: String): NiceResponse =
        if (challenged(response)) {
            val sent = response.okhttpResponse.request
            throw ErrorLoadingException("$FAILED [$how ${response.code} mit=${response.headers["cf-mitigated"]} ua=${sent.header("user-agent")?.takeLast(60)} " +
                "wvua=${com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent?.takeLast(60)} ck=${sent.header("cookie")?.split(";")?.map { it.substringBefore("=").trim() }}]")
        } else response

    private companion object {
        const val WARM_MS = 10 * 60 * 1000L
        const val COLD_MS = 30 * 1000L
        const val FAILED = "Cloudflare check not passed, retry in a moment"
    }
}
