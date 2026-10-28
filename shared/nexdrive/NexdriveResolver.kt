package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import com.nropvox.NexdriveParser.Host
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URI

/**
 * Receives each playable file a host link resolves to. [host] names the hop chain for the link label. Streamable
 * files answer HTTP range requests, so the player can seek; download-only ones (Google `video-downloads` files
 * behind G-Direct, 10Gbps and Vegadrop originals) ignore Range and resend the whole file. [quality] is set only for
 * transcodes, whose height replaces the one in the page heading.
 */
fun interface NexdriveSink {
    suspend fun add(host: String, url: String, streamable: Boolean, quality: Int?)
}

/** Resolves G-Direct, V-Cloud, HubCloud, V-Drive and Pixeldrain links to files. Keep one per provider: it holds the V-Cloud clearance. */
class NexdriveResolver(private val tag: String) {
    // vcloud.beer answers plain clients with a Cloudflare challenge; the other hops need nothing.
    private val cloudflare by lazy { CloudflareKiller() }
    // One WebView must solve the challenge and cache its cookie; parallel solvers for the same host stall each other.
    @Volatile private var vcloudWarmUntil = 0L
    private val vcloudSlots = Semaphore(2)
    private val vcloudLock = Mutex()

    suspend fun resolve(url: String, sink: NexdriveSink) {
        when (NexdriveParser.host(url)) {
            Host.GDIRECT -> safely(tag, "G-Direct") {
                gdirect(url)?.let { sink.add("G-Direct", it, streamable = false, quality = null) }
            }
            Host.VCLOUD -> safely(tag, "V-Cloud") { vcloud(url, sink) }
            Host.HUBCLOUD -> safely(tag, "HubCloud") { hubcloud(url, sink) }
            Host.VDRIVE -> safely(tag, "V-Drive") { vdrive(url, sink) }
            Host.PIXELDRAIN -> safely(tag, "Pixeldrain") {
                pixeldrain(url)?.let { sink.add("Pixeldrain", it, streamable = true, quality = null) }
            }
            // Filepress (interactive Turnstile), Gofile (signed rotating token, bans stale ones),
            // VikingFile (Turnstile), 1fichier and MegaUp (wait/captcha pages) cannot be resolved headless.
            Host.OTHER -> Unit
        }
    }

    private suspend fun gdirect(embedUrl: String): String? =
        NexdriveParser.directUrl(app.get(embedUrl, timeout = 30L).text)

    private suspend fun vcloud(url: String, sink: NexdriveSink) {
        val landing = vcloudGet(url).text
        val next = NexdriveParser.vcloudNext(landing) { base64Decode(it) }
        Log.d(tag, "V-Cloud landing ${landing.length} chars, token url ${if (next != null) "found" else "missing"}")
        if (next == null) return
        val mirrors = NexdriveParser.mirrors(vcloudGet(next).document)
        Log.d(tag, "V-Cloud servers: ${mirrors.joinToString { it.first }}")
        cloudMirrors("V-Cloud", mirrors, sink)
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
    private suspend fun hubcloud(url: String, sink: NexdriveSink) {
        val next = NexdriveParser.hubcloudNext(app.get(url, timeout = 30L).document) ?: return
        val mirrors = NexdriveParser.mirrors(app.get(next, referer = url, timeout = 30L).document)
        Log.d(tag, "HubCloud servers: ${mirrors.joinToString { it.first }}")
        cloudMirrors("HubCloud", mirrors, sink)
    }

    private suspend fun cloudMirrors(host: String, mirrors: List<Pair<String, String>>, sink: NexdriveSink) {
        mirrors.amap { (server, target) ->
            safely(tag, "$host $server") {
                when {
                    // Presigned R2 URLs with range support.
                    server.startsWith("FSLv2", ignoreCase = true) -> sink.add("$host FSLv2", target, streamable = true, quality = null)
                    // V-Cloud's plain FSL mirror (pub-*.r2.dev) resolves but never connects (TCP timeout) from
                    // the networks tested; HubCloud's is a presigned r2.cloudflarestorage.com URL like FSLv2.
                    server.startsWith("FSL", ignoreCase = true) && !URI(target).host.orEmpty().endsWith(".r2.dev") ->
                        sink.add("$host FSL", target, streamable = true, quality = null)
                    server.contains("10Gbps", ignoreCase = true) ->
                        redirectedFile(target)?.let { sink.add("$host 10Gbps", it, streamable = false, quality = null) }
                    server.contains("Pixel", ignoreCase = true) ->
                        pixeldrain(target)?.let { sink.add("$host Pixeldrain", it, streamable = true, quality = null) }
                }
            }
        }
    }

    /**
     * V-Drive mirrors one file to several hosts; `/api/file/<id>` says which are ready. Vegadrop (`skydrop`) and
     * Pixeldrain play; the others are Turnstile or token gated.
     */
    private suspend fun vdrive(url: String, sink: NexdriveSink) {
        val id = NexdriveParser.vdriveId(url) ?: return
        val origin = URI(url).let { "${it.scheme}://${it.host}" }
        // The share domain 301s to the current one; later calls need that origin as Referer.
        val response = app.get("$origin/api/file/$id", headers = JSON, timeout = 30L)
        val base = URI(response.url).let { "${it.scheme}://${it.host}" }
        val ready = response.parsedSafe<VdFile>()?.providers.orEmpty().filter { it.status == "ready" }.mapNotNull { it.provider }
        ready.amap { provider ->
            safely(tag, "V-Drive $provider") {
                when (provider) {
                    "skydrop" -> vegadrop(base, id, sink)
                    "pixeldrain" -> {
                        val target = app.get("$base/__vega_bridge/link/$id?provider=pixeldrain", allowRedirects = false, timeout = 30L)
                            .headers["location"] ?: return@safely
                        pixeldrain(target)?.let { sink.add("V-Drive Pixeldrain", it, streamable = true, quality = null) }
                    }
                }
            }
        }
    }

    /**
     * Vegadrop hands out a Google Photos URL (`lh3.googleusercontent.com/pw/…=dv`). `=dv` is the original file
     * without range support; the `=m37`/`=m22`/`=m18` transcodes (1080p/720p/360p MP4, first audio track only) seek.
     */
    private suspend fun vegadrop(base: String, id: String, sink: NexdriveSink) {
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
        best?.let { (height, url) -> sink.add("Vegadrop transcode, 1 audio", url, streamable = true, quality = height) }
        sink.add("Vegadrop original", file, streamable = false, quality = null)
    }

    private suspend fun pixeldrain(url: String): String? {
        val id = NexdriveParser.pixeldrainId(url) ?: return null
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

    private companion object {
        val JSON = mapOf("Accept" to "application/json")
        val VEGADROP_TRANSCODES = listOf("m37" to 1080, "m22" to 720, "m18" to 360)
        const val VCLOUD_WARM_MS = 10 * 60 * 1000L
    }
}

/** Runs [block], logging and swallowing any failure but cancellation. */
suspend fun <T> safely(tag: String, operation: String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // Do not log messages: HTTP errors may carry signed file URLs.
    Log.d(tag, "$operation failed (${e.javaClass.simpleName})")
    null
}
