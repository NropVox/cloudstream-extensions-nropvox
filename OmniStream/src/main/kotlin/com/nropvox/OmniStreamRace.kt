package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One stream source: emits whatever it finds through the two callbacks. */
typealias OmniSource = suspend (subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) -> Unit

/**
 * Runs every source at once on the IO pool and returns as soon as the answer is good enough: every source done, or
 * [OmniStreamParser.enough] working links plus a short grace, and never later than [TOTAL_MS]. The player only starts
 * once loadLinks returns, so one slow source must not hold it. Each link is probed before it is passed on, so the
 * count only includes links that answer; links that are proven dead (HTTP error, not a playlist, DRM) are dropped.
 * Single-use links (GoPlay) can't be probed without spending them, so they pass unchecked, and so do links whose
 * probe is still running when the race closes.
 *
 * Sources run in a detached scope, so a source stuck in blocking I/O can't delay the return; whatever it emits
 * after the race closed is ignored.
 */
internal class OmniLinkRace(
    private val tag: String,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val links = ConcurrentHashMap.newKeySet<String>()
    private val subtitles = ConcurrentHashMap.newKeySet<String>()
    private val goodLinks = AtomicInteger()
    private val goodSources = ConcurrentHashMap.newKeySet<String>()
    private val dropped = AtomicInteger()
    // Links whose probe has not answered yet, by URL. A probe that never answers counts as alive, so whatever is
    // still here when the race closes is passed on unchecked; [pending] is also the lock between the two.
    private val pending = HashMap<String, ExtractorLink>()
    private val enough = CompletableDeferred<Unit>()

    /** What one source did; read for the summary line, also for sources still running when the race closed. */
    private class SourceStat(val label: String) {
        val emitted = AtomicInteger()
        @Volatile var status = "running"
        @Volatile var ms: Long? = null
    }

    /** Links of the sources named in [unprobed] count without a probe: their URLs are single-use. */
    suspend fun run(sources: List<Pair<String, OmniSource>>, unprobed: Set<String> = emptySet()): Boolean {
        val started = System.currentTimeMillis()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var finish = "all done"
        val stats = sources.map { (label, _) -> SourceStat(label) }
        try {
            val probes = ConcurrentLinkedQueue<Job>()
            val jobs = sources.mapIndexed { index, (label, source) ->
                scope.launch { runSource(stats[index], source, scope, probes, label !in unprobed) }
            }
            // Probes are only started by sources, so once those are done the queue is complete.
            val done = scope.async { jobs.joinAll(); probes.toList().joinAll() }
            val ended = withTimeoutOrNull(TOTAL_MS) {
                select {
                    done.onAwait { }
                    enough.onAwait { }
                }
                if (!done.isCompleted) {
                    finish = "enough"
                    withTimeoutOrNull(GRACE_MS) { done.await() }
                }
            }
            if (ended == null) finish = "deadline"
        } finally {
            closed.set(true)
            scope.cancel()
        }
        // Slow sources (multi-hop download sites) often emit just before the close; their links still go to the player.
        val unchecked = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        unchecked.forEach(callback)
        val elapsed = System.currentTimeMillis() - started
        Log.i(tag, "${goodLinks.get()} links (${dropped.get()} dead dropped, ${unchecked.size} unchecked) in $elapsed ms, $finish; " +
            stats.joinToString(", ") { "${it.label} ${it.emitted.get()}/${it.ms ?: elapsed}ms" + if (it.status == "ok") "" else " ${it.status}" })
        return goodLinks.get() > 0 || unchecked.isNotEmpty()
    }

    private suspend fun runSource(stat: SourceStat, source: OmniSource, scope: CoroutineScope, probes: ConcurrentLinkedQueue<Job>, probe: Boolean) {
        val started = System.currentTimeMillis()
        val label = stat.label
        try {
            stat.status = withTimeoutOrNull(SOURCE_MS) {
                try {
                    source({ subtitle -> if (!closed.get() && subtitles.add(subtitle.url)) subtitleCallback(subtitle) }) { link ->
                        if (!closed.get() && links.add(link.url)) {
                            stat.emitted.incrementAndGet()
                            if (probe) {
                                synchronized(pending) { pending[link.url] = link }
                                probes.add(scope.launch { probeAndEmit(label, link) })
                            } else emit(label, link)
                        }
                    }
                    "ok"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(tag, "$label: ${e.javaClass.simpleName} ${e.message}")
                    "error"
                }
            } ?: "cut"
        } finally {
            if (stat.status == "running") stat.status = "cancelled"
            stat.ms = System.currentTimeMillis() - started
        }
    }

    private suspend fun probeAndEmit(label: String, link: ExtractorLink) {
        val alive = OmniProbe.alive(link)
        synchronized(pending) {
            // Gone when the race closed meanwhile and passed it on unchecked.
            if (pending.remove(link.url) == null) return
            if (!alive) {
                dropped.incrementAndGet()
                Log.i(tag, "Dropped dead link ${link.name}")
                return
            }
            emit(label, link)
        }
    }

    private fun emit(label: String, link: ExtractorLink) {
        callback(link)
        goodSources.add(label)
        if (OmniStreamParser.enough(goodLinks.incrementAndGet(), goodSources.size)) enough.complete(Unit)
    }

    companion object {
        /** Hard cap on the whole of loadLinks. */
        const val TOTAL_MS = 12_000L
        const val SOURCE_MS = 12_000L

        /** How long stragglers may still add links once there are enough. */
        const val GRACE_MS = 2_500L
    }
}

/**
 * A quick check that a link can play, run with its own headers. It answers false only when the link is proven
 * unplayable; a timeout or an unexpected error counts as alive, so a flaky probe never hides a working link.
 */
internal object OmniProbe {
    private const val PEEK_BYTES = 256L * 1024

    // NiceHttp's timeout doesn't cover OkHttp's 30 s connect, so the probe uses its own short-timeout client.
    private val client by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    suspend fun alive(link: ExtractorLink): Boolean = withContext(Dispatchers.IO) {
        try {
            when (link.type) {
                ExtractorLinkType.M3U8 -> playlistAlive(link)
                ExtractorLinkType.VIDEO -> videoAlive(link)
                else -> true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
    }

    /** The playlist must answer, be HLS and carry no DRM key, in the master or (where keys usually are) its first variant. */
    private fun playlistAlive(link: ExtractorLink): Boolean {
        val (code, body, finalUrl) = fetch(link, link.url, ranged = false)
        if (dead(code)) return false
        if (code !in 200..299) return true
        if (!OmniStreamParser.isPlaylist(body) || OmniStreamParser.isDrm(body)) return false
        val variant = OmniStreamParser.firstVariant(body, finalUrl) ?: return true
        val (variantCode, variantBody, _) = fetch(link, variant, ranged = false)
        return !(variantCode in 200..299 && OmniStreamParser.isDrm(variantBody))
    }

    /** One byte, closed unread, so a host that ignores Range can't stream the whole file. */
    private fun videoAlive(link: ExtractorLink): Boolean {
        val (code, _, _) = fetch(link, link.url, ranged = true)
        return !dead(code)
    }

    /** 416 can come back for a Range on some hosts that still play; 429 is the host being busy, not dead. */
    private fun dead(code: Int) = code >= 400 && code != 416 && code != 429

    private fun fetch(link: ExtractorLink, url: String, ranged: Boolean): Triple<Int, String, String> {
        val builder = Request.Builder().url(url)
        val headers = link.headers
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (headers.keys.none { it.equals("Referer", true) } && link.referer.isNotBlank()) builder.header("Referer", link.referer)
        if (headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", USER_AGENT)
        if (ranged) builder.header("Range", "bytes=0-0")
        return client.newCall(builder.build()).execute().use { response ->
            val body = if (ranged) "" else response.peekBody(PEEK_BYTES).string()
            Triple(response.code, body, response.request.url.toString())
        }
    }
}
