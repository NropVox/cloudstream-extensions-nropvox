package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dulo.mov's streams, keyed by TMDB id: its own Workers serve ZCDN ("Pickle", HLS up to 4K, no headers) and
 * `/api/streams/resolve`, which polls third-party servers and serves them through a Referer-checked proxy.
 * Shared by the Dulo and OmniStream plugins; [label] names the links and log lines.
 */
object DuloSource {
    const val SITE = "https://dulo.mov"
    private const val RESOLVE_BUDGET_MS = 10_000L

    // The zstream-proxy URLs of resolver sources and their captions 403 without the site as Referer or Origin.
    private val siteHeaders = mapOf("Origin" to SITE, "Referer" to "$SITE/")

    private val domainLock = Mutex()
    private var cachedDomain: String? = null

    suspend fun load(
        link: DuloLink,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = AtomicBoolean(false)
        runAllAsync(
            { loadSubtitles(link, label, subtitleCallback) },
            { if (loadZcdn(link, label, callback)) found.set(true) },
            // The player waits for the slowest task, so the resolver's polling is capped.
            { withTimeoutOrNull(RESOLVE_BUDGET_MS) { if (loadResolved(link, label, subtitleCallback, callback)) found.set(true) } },
        )
        return found.get()
    }

    /** Pickle: one `/cdn` lookup on a random ZCDN copy, then the master playlist for its top quality. */
    private suspend fun loadZcdn(link: DuloLink, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        val domain = workerDomain(label)
        val hosts = (1..3).shuffled()
        for (host in hosts.take(2)) {
            try {
                val answer = app.get(DuloParser.zcdnUrl(domain, host, link), timeout = 15L).text
                val playUrl = DuloParser.zcdnPlayUrl(answer, domain)
                    ?: return false.also { Log.d(label, "Pickle: no stream (${answer.take(80)})") }
                val master = app.get(playUrl, timeout = 15L)
                if (!master.isSuccessful || !master.text.trimStart().startsWith("#EXTM3U")) {
                    Log.w(label, "Pickle: master HTTP ${master.code}")
                    return false
                }
                callback(newExtractorLink("$label Pickle", "$label Pickle", playUrl, ExtractorLinkType.M3U8) {
                    quality = DuloParser.topHeight(master.text) ?: Qualities.Unknown.value
                })
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(label, "Pickle zcdn$host: ${e.message}")
            }
        }
        return false
    }

    /**
     * Extra servers: `/api/streams/resolve` answers `pending` until its backend has asked every server, and
     * (with `progressive=true`) already lists the servers found so far, so each poll emits the new ones.
     */
    private suspend fun loadResolved(
        link: DuloLink,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "$SITE/api/streams/resolve?${DuloParser.resolveQuery(link)}"
        // Every poll mints new proxy tokens for the same servers, so they are told apart by label like on the site.
        val emitted = HashSet<String>()
        while (true) {
            val response = try {
                app.get(url, timeout = 30L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(label, "Resolve: ${e.message}")
                break
            }
            if (response.code == 429 || response.code == 503) {
                delay(2_000)
                continue
            }
            val answer = DuloParser.resolveAnswer(response.text) ?: break
            answer.sources.filter { it.url != null && emitted.add(it.label ?: it.url) }
                .forEach { emitResolved(it, label, subtitleCallback, callback) }
            if (!answer.pending) break
            delay(1_500)
        }
        return emitted.isNotEmpty()
    }

    private suspend fun emitResolved(
        source: ResolveSource,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceUrl = source.url ?: return
        val (server, height) = DuloParser.splitLabel(source.label ?: "Server")
        source.captions.forEach { caption ->
            val captionUrl = caption.url ?: return@forEach
            subtitleCallback(newSubtitleFile("${caption.display ?: caption.language ?: "Unknown"} ($server)", captionUrl) {
                headers = siteHeaders
            })
        }
        val type = when (source.type) {
            "mp4", "mkv", "webm" -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
        // The player appends the quality itself, so the name stops at the server.
        callback(newExtractorLink("$label $server", "$label $server", sourceUrl, type) {
            referer = "$SITE/"
            quality = height ?: Qualities.Unknown.value
            headers = siteHeaders
        })
    }

    private suspend fun loadSubtitles(link: DuloLink, label: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val params = listOfNotNull(
            "id=${link.id}",
            link.imdbId?.let { "imdb=$it" },
            link.season?.let { "season=$it" },
            link.episode?.let { "episode=$it" },
        ).joinToString("&")
        val json = try {
            app.get("$SITE/api/subtitles/search?$params", timeout = 20L).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(label, "Subtitles: ${e.message}")
            return
        }
        DuloParser.subtitles(json).forEach { subtitle ->
            val name = (subtitle.display ?: subtitle.language ?: "Unknown") + if (subtitle.hearingImpaired) " (SDH)" else ""
            subtitleCallback(newSubtitleFile(name, subtitle.url!!))
        }
    }

    /** The Workers' domain from the site's runtime config, read once per session. */
    private suspend fun workerDomain(label: String): String = domainLock.withLock {
        cachedDomain ?: run {
            val domain = try {
                DuloParser.workerDomain(app.get("$SITE/dulo-config.js", timeout = 10L).text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(label, "Config: ${e.message}")
                null
            }
            (domain ?: DuloParser.DEFAULT_WORKER_DOMAIN).also { cachedDomain = it }
        }
    }
}
