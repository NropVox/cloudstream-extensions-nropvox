package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.util.Collections

/**
 * Finds a movie code on each source site. Javtiful and MissAV watch pages are then resolved by the page
 * extractors of the Extractors plugin, which their own providers use too; JavGG, SupJav and JavHD list host
 * embeds, and JavFC2 and JavHD's own player give an HLS playlist directly.
 */
internal object JavStreamSources {
    private const val JAVTIFUL = "https://javtiful.com"
    private const val MISSAV = "https://missav.live"
    private const val JAVGG = "https://javgg.net"
    private const val SUPJAV = "https://supjav.com"
    private const val SUPJAV_PLAYER = "https://lk1.supremejav.com"
    private const val JAVFC2 = "https://javfc2.xyz"
    private const val JAVHD = "https://javhdz.life"

    private val cloudflareInterceptor by lazy { CloudflareKiller() }
    private val supjav by lazy { CloudflareGate(CloudflareKiller()) }

    private fun encode(code: String): String = URLEncoder.encode(code, "UTF-8").replace("+", "%20")

    private fun query(code: String): String = encode(JavStreamParser.searchQuery(code))

    private suspend fun all(tasks: List<suspend () -> Unit>) = runAllAsync(*tasks.toTypedArray())

    suspend fun javtiful(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val page = JavStreamParser.findPage(
            app.get("$JAVTIFUL/search?q=${query(code)}").document,
            "article.video-card:not(.video-card--partner)", "a.video-card__title", code, JAVTIFUL
        ) ?: return
        loadExtractor(page, subtitleCallback, callback)
    }

    suspend fun missav(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val page = JavStreamParser.missavPage(
            app.get("$MISSAV/en/search/${query(code)}", interceptor = cloudflareInterceptor).document, code
        ) ?: return
        loadExtractor(page, subtitleCallback, callback)
    }

    /** Pages live at `/jav/{code}/`, so the search is only a fallback for codes spelled differently there. */
    suspend fun javgg(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val direct = app.get(JavStreamParser.javggUrl(JAVGG, code))
        val document = if (direct.code in 200..299) direct.document else {
            val page = JavStreamParser.findPage(
                app.get("$JAVGG/?s=${query(code)}").document, ".result-item", "a[href]", code, JAVGG
            ) ?: return
            app.get(page).document
        }
        all(JavStreamParser.javggEmbeds(document).map { embed ->
            suspend { loadExtractor(embed, subtitleCallback, callback) }
        })
    }

    suspend fun supjav(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val page = JavStreamParser.findPage(
            supjav.get("$SUPJAV/?s=${query(code)}").document, "div.post", "a[href]", code, SUPJAV
        ) ?: return
        all(JavStreamParser.supjavServers(supjav.get(page).document).map { link ->
            suspend { supjavServer(link, subtitleCallback, callback) }
        })
    }

    /** `?l=<data-link>` returns a page with `OLID`; `?c=<OLID reversed>` 302s to the host embed. */
    private suspend fun supjavServer(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val player = app.get("$SUPJAV_PLAYER/supjav.php?l=$link", referer = "$SUPJAV/")
        val token = JavStreamParser.supjavToken(player.text) ?: return
        val embed = app.get("$SUPJAV_PLAYER/supjav.php?c=$token", referer = player.url, allowRedirects = false)
            .headers["location"]?.takeIf { it.startsWith("http") }?.substringBefore('#') ?: return
        loadExtractor(embed, "$SUPJAV_PLAYER/", subtitleCallback, callback)
    }

    /** Watch pages live at `/watch/{code}.html`; the site searches FC2 titles by number only. */
    suspend fun javfc2(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var document = app.get(JavStreamParser.javFc2Url(JAVFC2, code)).document
        var stream = JavStreamParser.javFc2Stream(document)
        if (stream == null) {
            val page = JavStreamParser.findPage(
                app.get("$JAVFC2/search/?q=${query(code)}&per_page=0").document,
                ".movie-container > div", ".movie-title a", code, JAVFC2
            ) ?: return
            document = app.get(page).document
            stream = JavStreamParser.javFc2Stream(document) ?: return
        }
        JavStreamParser.javFc2Subtitles(document, JAVFC2).forEach { (label, url) ->
            subtitleCallback(newSubtitleFile(label, url))
        }
        // Segments sit behind fake .png names, which M3u8Helper rejects, so the player probes the playlist itself.
        callback(newExtractorLink("JavFC2", "JavFC2", stream, ExtractorLinkType.M3U8) {
            referer = "$JAVFC2/"
        })
    }

    /** JavHD's titles don't carry the code, so each of the top results is checked on its own page. */
    suspend fun javhd(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val pages = JavStreamParser.javhdResults(app.get("$JAVHD/search/${query(code)}/").document, JAVHD).take(2)
        val embeds = Collections.synchronizedSet(HashSet<String>())
        all(pages.map { page -> suspend { javhdPage(page, code, embeds, subtitleCallback, callback) } })
    }

    private suspend fun javhdPage(
        url: String,
        code: String,
        embeds: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        if (!JavStreamParser.javhdMatches(document, code)) return
        // The first server's player is already on the page; the others come from /ajax.
        val pagePlayer = document.selectFirst("#video")?.html().orEmpty()
        all(JavStreamParser.javhdServers(document).map { (id, server) ->
            suspend {
                val player = pagePlayer.takeIf {
                    server == "1" && (JavStreamParser.javhdStream(it) ?: JavStreamParser.iframeSrc(it)) != null
                } ?: app.post(
                    "$JAVHD/ajax",
                    referer = url,
                    headers = mapOf("Origin" to JAVHD),
                    json = mapOf("id" to id.toInt(), "server" to server.toInt())
                ).parsedSafe<JavStreamHdPlayer>()?.player.orEmpty()
                val stream = JavStreamParser.javhdStream(player)
                val embed = JavStreamParser.iframeSrc(player)
                if (stream != null) {
                    // TikTok CDN segments with fake .png names again, so no M3u8Helper.
                    callback(newExtractorLink("JavHD", "JavHD", stream, ExtractorLinkType.M3U8) {
                        referer = "$JAVHD/"
                    })
                } else if (embed != null && embeds.add(embed)) {
                    loadExtractor(embed, "$JAVHD/", subtitleCallback, callback)
                }
            }
        })
    }
}

/**
 * supjav.com answers every path with a Cloudflare challenge. The first request solves it alone; later ones
 * replay the clearance two at a time, since 5+ parallel requests deadlock inside CloudflareKiller.
 */
private class CloudflareGate(private val cloudflare: CloudflareKiller) {
    @Volatile private var warmUntil = 0L
    private val lock = Mutex()
    private val slots = Semaphore(2)

    suspend fun get(url: String): NiceResponse {
        if (System.currentTimeMillis() >= warmUntil) lock.withLock {
            if (System.currentTimeMillis() >= warmUntil) {
                val response = app.get(url, interceptor = cloudflare, timeout = 60L)
                if (response.code in 200..299) warmUntil = System.currentTimeMillis() + WARM_MS
                return checked(response)
            }
        }
        return checked(slots.withPermit { app.get(url, interceptor = cloudflare, timeout = 60L) })
    }

    private fun checked(response: NiceResponse): NiceResponse {
        if (response.code in listOf(403, 503) && response.headers["server"].orEmpty().startsWith("cloudflare")) {
            warmUntil = 0L
            throw ErrorLoadingException("Cloudflare check not passed")
        }
        return response
    }

    private companion object {
        const val WARM_MS = 10 * 60 * 1000L
    }
}
