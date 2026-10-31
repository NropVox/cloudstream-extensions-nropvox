package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

/**
 * mkvbase.site, a search-only vault of release links (mostly HubCloud) behind a Cloudflare challenge. Its page sets
 * the cookies that sign a search ([MkvBaseParser]); `mkv_seq` changes with every answer, so searches run one at a time.
 */
internal object MkvBaseSource {
    private const val TAG = "MkvBase"
    private const val SITE = "https://mkvbase.site"
    private const val HOST = "mkvbase.site"

    /** Releases resolved per play, at most this many of one height: a popular movie has dozens. */
    private const val MAX_RELEASES = 8
    private const val PER_HEIGHT = 3

    private val cloudflare by lazy { CloudflareKiller() }
    private val hosts = NexdriveResolver(TAG)
    private val lock = Mutex()
    private val cookies = HashMap<String, String>()

    /**
     * Solves the challenge and gets the session cookies ahead of a search, so playing doesn't wait for the WebView.
     * It runs in a detached `launch`, where an uncaught timeout would crash the app, so failures are only logged.
     */
    suspend fun warm() {
        safely(TAG, "session") { lock.withLock { if (!ready()) page() } }
    }

    suspend fun search(query: String): List<MkvBaseResult> = lock.withLock {
        if (!ready()) page()
        repeat(2) { attempt ->
            val response = links(query) ?: return@repeat
            if (response.isSuccessful) return@withLock MkvBaseParser.results(response.text).also { Log.i(TAG, "\"$query\": ${it.size} results") }
            Log.w(TAG, "\"$query\": HTTP ${response.code} ${response.text.take(120)}")
            if (attempt > 0) return@repeat
            if (response.headers["cf-mitigated"] == "challenge") {
                // CloudflareKiller replays a saved clearance without checking it: drop the expired one and solve again.
                cloudflare.savedCookies.remove(HOST)
                cookies.clear()
                page()
            } else {
                // As the page does, a refused search renews the session cookies once and tries again.
                keep(app.get("$SITE/api/links", interceptor = cloudflare, cookies = cookies.toMap(), referer = "$SITE/", timeout = 30L))
            }
        }
        emptyList()
    }

    /** Up to [MAX_RELEASES] of [results], highest first; only HubCloud and V-Cloud drives resolve headless. */
    suspend fun loadLinks(results: List<MkvBaseResult>, name: String, callback: (ExtractorLink) -> Unit) {
        val sink = Sink(name, callback)
        results.filter { NexdriveParser.host(it.url.orEmpty()) in listOf(NexdriveParser.Host.HUBCLOUD, NexdriveParser.Host.VCLOUD) }
            // One drive is often listed under two of HubCloud's domains.
            .distinctBy { it.url!!.substringAfter("://").substringAfter('/') }
            .groupBy { FourKHDHubParser.quality(it.title.orEmpty()) }.toSortedMap(compareByDescending { it })
            .values.flatMap { it.take(PER_HEIGHT) }.take(MAX_RELEASES)
            .amap { result ->
                hosts.resolve(result.url!!) { host, url, streamable, _ -> sink.add(result.title!!, host, url, streamable) }
            }
    }

    private fun ready() = "mkv_client_key" in cookies && "mkv_challenge" in cookies

    private suspend fun page() {
        val response = app.get("$SITE/", interceptor = cloudflare, timeout = 60L)
        keep(response)
        Log.i(TAG, "Session: HTTP ${response.code}, ${if (ready()) "signing cookies set" else "no signing cookies"}")
    }

    private suspend fun links(query: String): NiceResponse? {
        val clientKey = cookie("mkv_client_key") ?: return null
        val (challenge, difficulty) = MkvBaseParser.challenge(cookie("mkv_challenge") ?: return null)
        val seq = cookie("mkv_seq") ?: "1"
        val time = System.currentTimeMillis()
        val encoded = MkvBaseParser.encodeQuery(query, time)
        val nonce = withContext(Dispatchers.Default) { MkvBaseParser.proofOfWork(challenge, difficulty, encoded) }
        val entropy = MkvBaseParser.ENTROPY
        val sig = MkvBaseParser.signature(clientKey, encoded, time, seq, nonce, entropy)
        val path = MkvBaseParser.linksPath(URLEncoder.encode(encoded, "UTF-8"), time, seq, nonce, entropy, sig)
        val response = app.get(SITE + path, interceptor = cloudflare, cookies = cookies.toMap(), referer = "$SITE/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"), timeout = 30L)
        keep(response)
        return response
    }

    /** The page reads its cookies URL-decoded (`mkv_challenge` holds an encoded colon). */
    private fun cookie(name: String) = cookies[name]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** The site's cookies from the answer, and those the challenge's WebView picked up with the clearance. */
    private fun keep(response: NiceResponse) {
        cloudflare.savedCookies[HOST]?.let { saved -> saved.filterKeys { it.startsWith("mkv_") }.forEach { (k, v) -> cookies.putIfAbsent(k, v) } }
        response.cookies.forEach { (k, v) -> cookies[k] = v }
    }

    /**
     * Download-only links (see [NexdriveSink]) play from the start but cannot seek. Those rank as Unknown quality
     * under their own source name so every stream is picked first.
     */
    private class Sink(private val name: String, private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        suspend fun add(release: String, host: String, url: String, streamable: Boolean) {
            if (!emitted.add(url)) return
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, FourKHDHubParser.tags(release).ifBlank { null }).joinToString(" · ")
            val height = FourKHDHubParser.quality(release).takeIf { it > 0 } ?: Qualities.Unknown.value
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                quality = if (streamable) height else Qualities.Unknown.value
            })
        }
    }
}
