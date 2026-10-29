package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure parsing for streamcorner.st: the bootstrap, page URLs, worker messages and replies, schedule times. */
object StreamCornerParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** A `p=` value of the data API. `key` is also the SPA's providerId and the `/stream/<key>/` path segment. */
    data class Provider(val key: String, val label: String, val row: String)

    val providers = listOf(
        Provider("channels", "CHANNELS", "TV Channels"),
        Provider("nba", "NBA", "NBA"),
        Provider("mlb", "MLB", "MLB"),
        Provider("nfl", "NFL - CFB", "NFL & College Football"),
        Provider("admin", "ADMIN", "Events & 24/7"),
        Provider("alpha", "ALPHA", "Soccer · Alpha"),
        Provider("extra003", "#003", "Soccer · #003"),
        Provider("beta", "BETA", "All Sports · Beta"),
        Provider("001", "#001", "All Sports · #001"),
    )

    fun provider(key: String): Provider? = providers.firstOrNull { it.key == key }

    /** Hard-coded in the data module as `qo`; used only when the module can't be read. */
    val fallbackHosts = listOf(
        "data.gigav.workers.dev", "data.yedmzoa.workers.dev", "data.ngagzipx.workers.dev", "data.miopks.workers.dev",
        "data.senbon001.workers.dev", "data.senbon002.workers.dev", "data.senbon003.workers.dev",
        "data.phamviet444.workers.dev", "data.kanghaerin444.workers.dev", "data.minjikim444.workers.dev",
    )

    private val preloadModule = Regex("""<link[^>]+rel="modulepreload"[^>]+href="(/assets/[A-Za-z0-9_-]+\.js)"""")
    private val entryModule = Regex("""<script[^>]+type="module"[^>]+src="(/assets/[A-Za-z0-9_-]+\.js)"""")
    private val workerFile = Regex("""new URL\("([A-Za-z0-9_-]+\.js)",import\.meta\.url\)""")
    private val dataHost = Regex(""""(data\.[a-z0-9-]+\.workers\.dev)"""")

    /** Modules named in the homepage. Preloads come first: the small data module that names the worker is one. */
    fun modulePaths(html: String): List<String> =
        (preloadModule.findAll(html) + entryModule.findAll(html)).map { it.groupValues[1] }.distinct().toList()

    /** The worker script and data hosts named in one module, or null when it's not the data module. */
    fun bootstrap(js: String): SCBootstrap? {
        val worker = workerFile.find(js)?.groupValues?.get(1) ?: return null
        val hosts = dataHost.findAll(js).map { it.groupValues[1] }.distinct().toList()
        return SCBootstrap("/assets/$worker", hosts.ifEmpty { fallbackHosts })
    }

    fun dataUrl(host: String, provider: String, id: String? = null): String =
        "https://$host/corner?p=${encode(provider)}" + (id?.let { "&id=${encode(it)}" } ?: "")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The message the site posts to its worker (`hc`/`mc` in the data module), minus `requestId`. */
    fun message(operation: String, provider: Provider, hosts: List<String>, id: String? = null): String =
        mapper.writeValueAsString(mapOf(
            "operation" to operation,
            "requestUrls" to hosts.map { dataUrl(it, provider.key, id) },
            "providerId" to provider.key,
            "providerName" to provider.label,
        ))

    /** `{"requestId":1,"data":…}` or `{"requestId":1,"error":"Failed to fetch SlingTV: 403"}`. */
    fun replyData(json: String): JsonNode {
        val reply = mapper.readTree(json)
        reply.get("error")?.takeIf { !it.isNull }?.let { throw IllegalStateException(it.asText()) }
        return reply.get("data") ?: throw IllegalStateException("Reply without data")
    }

    fun list(json: String): List<SCItem> {
        val data = replyData(json)
        if (!data.isArray) return emptyList()
        return data.mapNotNull { runCatching { mapper.treeToValue<SCItem>(it) }.getOrNull() }.filter { id(it) != null }
    }

    fun detail(json: String): SCItem = mapper.treeToValue(replyData(json))

    fun id(item: SCItem): String? = (item.streamId ?: item.gameId)?.trim()?.ifEmpty { null }

    /** The site's own page URL for an item, so "open in browser" lands on the same stream. */
    fun pageUrl(mainUrl: String, provider: String, id: String): String {
        val safe = encode(id)
        return when (provider) {
            "nba" -> "$mainUrl/nba/live/$safe"
            "extra003" -> "$mainUrl/stream/extra/extra003/$safe"
            else -> "$mainUrl/stream/$provider/$safe"
        }
    }

    private val extraPage = Regex("""/stream/extra/([A-Za-z0-9_-]+)/([^/?#]+)""")
    private val nbaPage = Regex("""/nba/(?:live|vod)/([^/?#]+)""")
    private val streamPage = Regex("""/stream/([A-Za-z0-9_-]+)/([^/?#]+)""")

    /** (provider key, id) from any of the site's stream page URLs. */
    fun pageRef(url: String): Pair<String, String>? {
        val ref = extraPage.find(url)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: nbaPage.find(url)?.let { "nba" to it.groupValues[1] }
            ?: streamPage.find(url)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: return null
        return ref.first to URLDecoder.decode(ref.second, "UTF-8")
    }

    fun title(item: SCItem): String? {
        item.eventName?.trim()?.ifEmpty { null }?.let { return it }
        val home = item.homeTeam?.trim()?.ifEmpty { null } ?: return null
        val away = item.awayTeam?.trim()?.ifEmpty { null } ?: return home
        return "$away @ $home"
    }

    fun poster(item: SCItem): String? =
        listOf(item.poster, item.heroThumbnail, item.homeTeamLogo, item.categoryLogo)
            .firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }?.trim()

    enum class Status { LIVE, UPCOMING, ENDED }

    private val liveWords = listOf("qtr", "quarter", "half", "live", "in progress", "q1", "q2", "q3", "q4", "ot")

    /** A port of the site's `isItemLive`: a start time with an end or a 3.5 h default, else the status text. */
    fun status(item: SCItem, nowMs: Long): Status {
        val text = (item.time ?: item.timeEt).orEmpty().lowercase()
        if ("final" in text || "finished" in text) return Status.ENDED
        val start = startMs(item)
        if (start != null) {
            if (nowMs < start - 60_000) return Status.UPCOMING
            val end = millis(item.endTime) ?: (start + DEFAULT_DURATION_MS)
            return if (nowMs <= end) Status.LIVE else Status.ENDED
        }
        return if (liveWords.any { it in text }) Status.LIVE else Status.UPCOMING
    }

    /** Live first, then upcoming by start, then ended (only NBA keeps those, for replays) newest first. */
    fun sorted(items: List<SCItem>, nowMs: Long, keepEnded: Boolean): List<SCItem> {
        val ranked = items.map { it to status(it, nowMs) }.filter { keepEnded || it.second != Status.ENDED }
        return ranked.sortedWith(compareBy<Pair<SCItem, Status>> { it.second.ordinal }
            .thenBy { (item, status) -> (startMs(item) ?: Long.MAX_VALUE).let { if (status == Status.ENDED) -it else it } })
            .map { it.first }
    }

    fun startMs(item: SCItem): Long? =
        millis(item.startTime) ?: millis(item.timestamp)
            ?: parseTime(item.timeUtc, utcFormats, "UTC")
            ?: parseTime(item.timeEt?.replace(Regex("""\s*ET$""", RegexOption.IGNORE_CASE), ""), etFormats, "America/New_York")

    /** Seconds or milliseconds since the epoch, as the API mixes both. */
    private fun millis(value: String?): Long? {
        val n = value?.trim()?.toDoubleOrNull()?.toLong() ?: return null
        if (n <= 0) return null
        return if (n < 1_000_000_000_000) n * 1000 else n
    }

    private val utcFormats = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")
    private val etFormats = listOf("MM/dd/yy hh:mma", "MM/dd/yyyy hh:mma", "MM/dd/yy h:mma", "yyyy-MM-dd hh:mma", "yyyy-MM-dd HH:mm")

    private fun parseTime(value: String?, formats: List<String>, zone: String): Long? {
        val text = value?.trim()?.ifEmpty { null } ?: return null
        for (pattern in formats) {
            val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone(zone); isLenient = false }
            val position = ParsePosition(0)
            val date = format.parse(text, position) ?: continue
            if (position.index == text.length) return date.time
        }
        return null
    }

    /** League, start time in the viewer's zone (or "Live now"), score, then the site's description. */
    fun plot(item: SCItem, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val when_ = when (status(item, nowMs)) {
            Status.LIVE -> "Live now"
            Status.ENDED -> item.time?.trim()?.ifEmpty { null } ?: "Ended"
            Status.UPCOMING -> startMs(item)?.let {
                "Starts " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).apply { timeZone = zone }.format(Date(it))
            } ?: item.timeEt?.trim()?.ifEmpty { null }
        }
        // Games that haven't tipped off report 0–0.
        val score = item.awayScore?.let { away -> item.homeScore?.let { home -> "$away – $home" } }
            ?.takeIf { item.awayScore != "0" || item.homeScore != "0" }
        val league = listOfNotNull(item.league?.trim()?.ifEmpty { null }, item.category?.trim()?.ifEmpty { null })
            .distinctBy { it.lowercase() }.joinToString(" · ").ifEmpty { null }
        return listOfNotNull(league, when_, score, item.description?.trim()?.ifEmpty { null }).joinToString("\n")
    }

    /** Every playable choice of a detail reply: event streams, then NBA live feeds, then NBA replays. */
    fun sources(item: SCItem): List<Pair<String, String>> =
        (item.streams + item.live + item.vods).mapNotNull { stream ->
            val url = listOf(stream.embedUrl, stream.streamUrl, stream.manifestUrl, stream.url)
                .firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }?.trim() ?: return@mapNotNull null
            (stream.sourceName?.trim()?.ifEmpty { null } ?: "Stream") to url
        }.distinctBy { it.second }

    private val uhd = Regex("""\b(?:4K|UHD|2160P?)\b""", RegexOption.IGNORE_CASE)
    private val fhd = Regex("""\b(?:FHD|1080P?)\b""", RegexOption.IGNORE_CASE)
    private val hd = Regex("""\b(?:HD|720P?)\b""", RegexOption.IGNORE_CASE)

    /** Quality from a source label like "FOX FHD HDR" or "FOX 4K UHD"; null when it doesn't say. */
    fun quality(label: String): Int? = when {
        uhd.containsMatchIn(label) -> 2160
        fhd.containsMatchIn(label) -> 1080
        hd.containsMatchIn(label) -> 720
        else -> null
    }

    private val manifest = Regex("""\.m3u8(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val dash = Regex("""\.mpd(?:$|[?#])""", RegexOption.IGNORE_CASE)

    fun isManifest(url: String): Boolean = manifest.containsMatchIn(url.substringBefore('#'))

    fun isDash(url: String): Boolean = dash.containsMatchIn(url.substringBefore('#'))

    /** The page that starts the site's worker; served by the WebView at a streamcorner.st URL. */
    fun bridgeHtml(workerPath: String): String = """<!doctype html><html><head><meta charset="utf-8"></head><body><script>
let w;
try {
  w = new Worker(${mapper.writeValueAsString(workerPath)}, {type: 'module', name: 'provider-data'});
  w.onmessage = function (e) { var d = e.data || {}; CsBridge.reply(d.requestId | 0, JSON.stringify(d)); };
  w.onerror = function (e) { CsBridge.failed(String((e && e.message) || 'worker failed to load')); };
  CsBridge.ready();
} catch (e) { CsBridge.failed(String(e)); }
function csCall(id, msg) { msg.requestId = id; w.postMessage(msg); }
</script></body></html>"""

    /** A streamcorner.st page framing one embed, as the site does; some embeds blank themselves when not framed. */
    fun playerHtml(embedUrl: String): String = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,iframe{margin:0;border:0;width:100%;height:100%;background:#000}</style></head><body>
<iframe src="${attr(embedUrl)}" allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
</body></html>"""

    private fun attr(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

    private const val DEFAULT_DURATION_MS = 12_600_000L
}
