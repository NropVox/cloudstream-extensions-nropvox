package com.nropvox

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * Finds a movie code on each source site. Javtiful, SexTB and MissAV watch pages are then resolved by the page
 * extractors of the Extractors plugin, which their own providers use too; JavGG and SupJav only list host embeds.
 */
internal object JavStreamSources {
    private const val JAVTIFUL = "https://javtiful.com"
    private const val SEXTB = "https://sextb.net"
    private const val MISSAV = "https://missav.live"
    private const val JAVGG = "https://javgg.net"
    private const val SUPJAV = "https://supjav.com"
    private const val SUPJAV_API = "https://lk1.supremejav.com/supjav.php"

    private val cloudflareInterceptor = CloudflareKiller()

    private val sextbHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$SEXTB/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun encode(code: String): String = URLEncoder.encode(code, "UTF-8").replace("+", "%20")

    private fun Element.matchesMovieCode(code: String): Boolean {
        if (code.isBlank()) return false
        val pattern = Regex(
            "(?<![a-z0-9])${Regex.escape(code)}(?![a-z0-9])",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(text()) || select("a[href], img[alt]").any {
            pattern.containsMatchIn(it.attr("href")) || pattern.containsMatchIn(it.attr("alt"))
        }
    }

    private fun Element.absoluteHref(base: String): String? =
        attr("href").takeIf { it.isNotBlank() }?.let { URI("$base/").resolve(it).toString() }

    suspend fun javtiful(code: String): String? =
        app.get("$JAVTIFUL/search?q=${encode(code)}").document
            .select("article.video-card:not(.video-card--partner)")
            .firstOrNull { it.matchesMovieCode(code) }
            ?.selectFirst("a.video-card__title")?.absoluteHref(JAVTIFUL)

    suspend fun sextb(code: String): String? =
        app.get("$SEXTB/search/${encode(code)}", headers = sextbHeaders, interceptor = cloudflareInterceptor).document
            .select("div.tray-item")
            .firstOrNull { it.matchesMovieCode(code) }
            ?.selectFirst("a")?.absoluteHref(SEXTB)

    suspend fun missav(code: String): String? =
        app.get("$MISSAV/en/search/${encode(code)}", interceptor = cloudflareInterceptor).document
            .select("div.grid.grid-cols-2 > div")
            .firstOrNull { it.matchesMovieCode(code) }
            ?.selectFirst("a[href*='/en/'], a[href*='/dm']")?.absoluteHref(MISSAV)

    suspend fun javgg(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val matchedUrl = app.get("$JAVGG/?s=$code").document
            .selectFirst(".result-item > article a")?.absoluteHref(JAVGG) ?: return
        val embeds = app.get(matchedUrl).document.select(".pframe > iframe")
            .map { it.attr("src") }.filter { it.isNotBlank() }
        runAllAsync(*embeds.map { src -> suspend { loadExtractor(src, subtitleCallback, callback) } }.toTypedArray())
    }

    suspend fun supjav(code: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val matchedUrl = app.get("$SUPJAV/?s=$code").document
            .selectFirst(".post a")?.absoluteHref(SUPJAV) ?: return
        val servers = app.get(matchedUrl).document.select(".btn-server").map { it.attr("data-link") }
        runAllAsync(*servers.map { dataLink ->
            suspend {
                val res1 = app.get(
                    url = "$SUPJAV_API?l=$dataLink",
                    referer = SUPJAV,
                    headers = mapOf("referer" to SUPJAV)
                )
                val olid = res1.text.substringAfter("var OLID = '").substringBefore("'").reversed()
                val res2 = app.get(
                    "$SUPJAV_API?c=$olid",
                    referer = res1.url,
                    headers = mapOf("referer" to res1.url),
                    allowRedirects = false
                )
                res2.headers["location"]?.let { loc ->
                    loadExtractor(loc, referer = SUPJAV_API, subtitleCallback = subtitleCallback, callback = callback)
                }
            }
        }.toTypedArray())
    }
}
