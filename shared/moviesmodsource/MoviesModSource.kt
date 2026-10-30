package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MoviesMod's search and stream resolver: modpro.blog link pages, then a SafeLink ad gate, then DriveSeed, which hosts
 * each file on R2 and behind a Google "Instant" mirror. Shared by the MoviesMod and OmniStream plugins.
 */
object MoviesModSource {
    // The site rotates its domain; the modpro, gate and DriveSeed hosts are always read from the page links.
    const val SITE = "https://moviesmod.meme"
    private const val TAG = "MoviesMod"
    private const val PROBE_TTL_MS = 10 * 60 * 1000L

    /** One page of `/search/<q>/`, or null past the last page (a 404). */
    suspend fun search(query: String, page: Int = 1): Document? {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$SITE/search/$q/" else "$SITE/search/$q/page/$page/"
        val res = app.get(url, timeout = 30L)
        return if (res.isSuccessful) res.document else null
    }

    /** The quality pages of a post, batch zips left out. */
    fun sources(doc: Document): List<MmSource> = MoviesModParser.groups(doc).flatMap { group ->
        group.buttons.filterNot { MoviesModParser.isBatch(it.label) }.map { MmSource(group.heading, it.url) }
    }

    suspend fun loadLinks(link: MmLink, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val emit: (ExtractorLink) -> Unit = { if (emitted.add(it.url)) callback(it) }
        link.sources.amap { source ->
            safely("source ${source.heading}") {
                val doc = page(source.url).document
                if (link.episode != null) {
                    val gate = MoviesModParser.episodes(doc).firstOrNull { it.first == link.episode }?.second ?: return@safely
                    resolveGate(name, MmSource(source.heading, gate), emit)
                } else {
                    // A movie's links page offers several servers that usually end at the same file; stop at the first that plays.
                    for (server in MoviesModParser.servers(doc)) {
                        if (resolveGate(name, MmSource(source.heading, server), emit)) break
                    }
                }
            }
        }
        return emitted.isNotEmpty()
    }

    /** A modpro.blog page. Those hosts answer 429 with a Retry-After of ~9 s to bursts, so wait it out a few times. */
    suspend fun page(url: String): NiceResponse {
        repeat(3) {
            val res = app.get(url, timeout = 30L)
            if (res.code != 429) return res
            val wait = res.headers["retry-after"]?.toLongOrNull()?.coerceIn(1L, 10L) ?: 5L
            Log.i(TAG, "429 from $url, retrying in ${wait}s")
            delay(wait * 1000)
        }
        return app.get(url, timeout = 30L)
    }

    /**
     * Gate → DriveSeed file page → its mirrors. Cloud (R2) and Direct (Cloudflare Worker) honour Range and seek;
     * Instant (Google) does not, so the player has to read the whole MKV before the first frame. True when anything
     * was emitted.
     */
    private suspend fun resolveGate(name: String, source: MmSource, emit: (ExtractorLink) -> Unit): Boolean {
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
        if (!any) Log.w(TAG, "No playable mirror on ${filePage.url} (${file.name})")
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
            if (!ok) Log.w(TAG, "$host unreachable (${code ?: "timeout"}); skipping its links")
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

    suspend fun <T> safely(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$label failed: ${e.message}")
        null
    }

    private val probes = ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private val probeLock = Mutex()
}
