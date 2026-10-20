package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.net.URI

internal class MkvDramaResolver(private val session: MkvDramaSession) {
    private val visited = mutableSetOf<String>()
    private val emitted = mutableSetOf<String>()
    private var browserAttempts = 0
    private val containers = setOf("mkvdrama.net", "mkvdrama.org", "viewcrate.cc", "www.viewcrate.cc")
    private val shorteners = setOf("ouo.io", "ouo.press", "oii.la", "exe.io", "exey.io", "exey.app")
    private val fileHosts = setOf("pixeldrain.com", "gofile.io", "mega.nz", "datanodes.to", "send.now", "filekeeper.net", "fileditchvids.st", "vikingfile.com", "krakenfiles.com")

    suspend fun resolve(
        url: String, target: MkvEpisodeData, label: String, quality: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        file: MkvFile? = null, depth: Int = 0,
    ): Boolean {
        if (depth > 10 || !MkvDramaDecoder.isHttp(url) || !visited.add(url)) return false
        val host = URI(url).host.lowercase().removePrefix("www.")
        fun relevant(candidate: MkvFile) = MkvDramaDecoder.matches(candidate, target.episode)
        if (host in fileHosts || Regex("(?i)\\.(mkv|mp4|m3u8)$").containsMatchIn(URI(url).path)) {
            if (file != null && !relevant(file)) return false
            // An unlabelled URL from a batch is never assigned to an arbitrary episode.
            if (target.episode != null && file == null) return false
            if (host == "pixeldrain.com") {
                val id = Regex("^/u/([A-Za-z0-9]+)$").matchEntire(URI(url).path)?.groupValues?.get(1) ?: return false
                val info = session.get("https://pixeldrain.com/api/file/$id/info", acceptJson = true).json()
                if (!info.path("can_download").asBoolean(true) || info.path("success").asBoolean(true).not()) return false
                val name = info.textOrNull("name") ?: file?.filename.orEmpty()
                if (target.episode != null && !relevant(MkvFile(url, name, file?.episode.orEmpty()))) return false
                val direct = "https://pixeldrain.com/api/file/$id?download"
                if (emitted.add(direct)) callback(newExtractorLink("MkvDrama", "MKVDrama · Pixeldrain · $label", direct, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(file?.quality?.ifBlank { quality } ?: quality)
                    this.referer = ""
                    headers = mapOf("User-Agent" to USER_AGENT)
                })
                return true
            }
            if (Regex("(?i)\\.(mkv|mp4|m3u8)$").containsMatchIn(URI(url).path)) {
                if (emitted.add(url)) callback(newExtractorLink("MkvDrama", "MKVDrama · $label", url,
                    if (URI(url).path.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(file?.quality?.ifBlank { quality } ?: quality)
                    this.referer = referer
                })
                return true
            }
            var found = false
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) { callback(link); found = true }
            }
            return found
        }
        val response = session.request(url, referer)
        response.location?.let {
            return resolve(it, target, label, quality, url, subtitleCallback, callback, file, depth + 1)
        }
        val document = Jsoup.parse(response.text, url)
        if (host in setOf("filecrypt.cc", "filecrypt.co")) {
            if (document.selectFirst("#pow-captcha, #cform, input[type=password]") != null) return false
            val files = document.select("tr").mapNotNull { row ->
                val anchor = row.selectFirst("a.download[href]") ?: return@mapNotNull null
                val filename = row.selectFirst("td[title]")?.attr("title").orEmpty()
                val link = anchor.absUrl("href")
                if (!MkvDramaDecoder.isHttp(link) || URI(link).host.removePrefix("www.") !in setOf("filecrypt.cc", "filecrypt.co")) return@mapNotNull null
                MkvFile(link, filename)
            }
            if (files.isNotEmpty()) return resolveFiles(files, target, label, quality, url, subtitleCallback, callback, depth)
            // Filecrypt /Link pages advertise a same-host /Go navigation before the file redirect.
            val go = Regex("""(?:https?://(?:www\.)?filecrypt\.(?:cc|co))?/(?:Go|Link)/[A-Za-z0-9]+\.html""")
                .findAll(response.text).map { URI(url).resolve(it.value).toString() }.firstOrNull { it != url }
            if (file != null && go != null) return resolve(go, target, label, quality, url, subtitleCallback, callback, file, depth + 1)
            // Movie-only CNL exports need no episode-to-filename mapping.
            if (target.episode == null) {
                val exports = document.select("form").mapNotNull { form ->
                    val key = form.selectFirst("input[name=jk]")?.attr("value") ?: return@mapNotNull null
                    val crypted = form.selectFirst("input[name=crypted]")?.attr("value") ?: return@mapNotNull null
                    mkvJson.createObjectNode().put("jk", key).put("crypted", crypted)
                }
                if (exports.isNotEmpty()) return resolveFiles(exports.flatMap { MkvDramaDecoder.cnl(it) }.map { MkvFile(it) },
                    target, label, quality, url, subtitleCallback, callback, depth)
            }
            return false
        }
        if (host in containers && URI(url).path.startsWith("/c/")) {
            val native = document.selectFirst("#w[data-p=c][data-s]")
            if (native != null) {
                val config = document.selectFirst("#xd") ?: error("Missing native container config")
                fun unmask(attr: String) = MkvDramaDecoder.unmask(mkvJson.readTree(config.attr(attr)))
                val token = String(unmask("data-n"), Charsets.US_ASCII)
                val slug = native.attr("data-s")
                require(slug.matches(Regex("[A-Za-z0-9_-]+")) && URI(url).path.trimEnd('/') == "/c/$slug" && token.matches(Regex("[a-f0-9]{32}")))
                val payload = session.get(MkvDramaDecoder.sameSitePath(url, "/c/$slug/p/$token"), url, true).json()
                val data = mkvJson.readTree(MkvDramaDecoder.gcm(payload.path("d").asText(), unmask("data-k"), unmask("data-v")))
                return resolveFiles(data.path("links").map { item ->
                    MkvFile(item.path("url").asText(), item.path("label").asText(), item.path("episode").asText(), item.path("quality").asText())
                }, target, label, quality, url, subtitleCallback, callback, depth)
            }
            val root = document.selectFirst("[data-open-base-url]")
            if (root != null) {
                val cards = MkvDramaDecoder.viewcrateCards(response.text, url)
                if (cards.isNotEmpty()) return resolveFiles(cards, target, label, quality, url, subtitleCallback, callback, depth)
                fun local(value: String): String = URI(url).resolve(value).toString().also {
                    require(URI(it).authority == URI(url).authority && MkvDramaDecoder.isHttp(it))
                }
                val bootstrap = document.selectFirst("script#vc-container-bootstrap")?.data()?.takeIf { it.isNotBlank() }?.let(mkvJson::readTree)
                    ?: run {
                        require(root.attr("data-bootstrap-url").isNotBlank()) { "No container bootstrap endpoint" }
                        session.get(local(root.attr("data-bootstrap-url")), url, true).json()
                    }
                val data = mkvJson.readTree(MkvDramaDecoder.gcm(bootstrap.path("c").asText(), MkvDramaDecoder.decodeBase64(bootstrap.path("k").asText()), MkvDramaDecoder.decodeBase64(bootstrap.path("i").asText())))
                val allowed = bootstrap.path("r").map { it.asText().lowercase() }
                val base = local(root.attr("data-open-base-url")).trimEnd('/')
                val files = data.path("d").flatMap { group ->
                    group.path("l").map { link ->
                        val id = link.path("u").asText()
                        require(id.matches(Regex("[a-f0-9]{16}")))
                        val privacy = if (link.path("h").asText().lowercase() in allowed) "allow-referrer" else "noreferrer"
                        MkvFile("$base/$id?privacy=$privacy", link.path("n").asText(), group.path("t").asText())
                    }
                }
                return resolveFiles(files, target, label, quality, url, subtitleCallback, callback, depth)
            }
        }
        // ViewCrate open links may take several same-host redirects; retain file metadata.
        if (host == "viewcrate.cc" && URI(url).path.startsWith("/open/")) return false
        if (host in shorteners) {
            val form = document.selectFirst("form#form-captcha, form#form-go, form#before-captcha, form#link-view")
            if (form != null && form.attr("method").equals("post", true)) {
                val values = form.select("input[name], textarea[name]").filter { !it.hasAttr("disabled") &&
                    (it.attr("type") !in setOf("checkbox", "radio") || it.hasAttr("checked")) &&
                    it.attr("type") !in setOf("submit", "button", "reset", "file") }
                    .associate { it.attr("name") to if (it.tagName() == "textarea") it.text() else it.attr("value") }
                val action = URI(url).resolve(form.attr("action")).toString()
                val gated = values.any { (key, value) -> key in setOf("cf-turnstile-response", "g-recaptcha-response", "h-captcha-response") && value.isBlank() }
                if (!gated && URI(action).host.removePrefix("www.") in shorteners) {
                    val next = session.request(action, url, form = values, post = true)
                    next.location?.let { return resolve(it, target, label, quality, action, subtitleCallback, callback, file, depth + 1) }
                    // The second form needs the returned page, not a duplicate GET of the POST URL.
                    val go = Jsoup.parse(next.text, action).selectFirst("form#form-go")
                    if (go != null) {
                        // Ouo's second continuation follows a countdown, as in the bypass client.
                        delay(6000)
                        val goUrl = URI(action).resolve(go.attr("action")).toString()
                        if (URI(goUrl).host.removePrefix("www.") in shorteners) {
                            val goValues = go.select("input[name]").associate { it.attr("name") to it.attr("value") }
                            val final = session.request(goUrl, action, form = goValues, post = true)
                            final.location?.let { return resolve(it, target, label, quality, goUrl, subtitleCallback, callback, file, depth + 1) }
                        }
                    }
                }
            }
            // Let the site's browser verification run on-device; intercept only a real destination.
            if (browserAttempts++ >= 2) return false
            val destination = WebViewResolver(
                interceptUrl = Regex("""https?://(?:www\.)?(?:mkvdrama\.(?:net|org)/c/|viewcrate\.cc/c/|pixeldrain\.com/u/|gofile\.io/d/).*"""),
                userAgent = null, useOkhttp = false, timeout = 60_000L
            ).resolveUsingWebView(url).first?.url?.toString()
            if (destination != null) return resolve(destination, target, label, quality, url, subtitleCallback, callback, file, depth + 1)
        }
        return false
    }

    private suspend fun resolveFiles(files: List<MkvFile>, target: MkvEpisodeData, label: String, quality: String,
        referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, depth: Int): Boolean {
        var found = false
        for (file in files.filter { MkvDramaDecoder.matches(it, target.episode) }) {
            try {
                found = resolve(file.url, target, label, quality, referer, subtitleCallback, callback, file, depth + 1) || found
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Log.w("MkvDrama", "Mirror ${runCatching { URI(file.url).host }.getOrNull()} failed: ${e.javaClass.simpleName}") }
        }
        return found
    }
}
