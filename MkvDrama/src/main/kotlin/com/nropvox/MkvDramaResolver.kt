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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Follows release links to containers, exports container rows, and emits file-host links. Never requests media. */
internal class MkvDramaResolver(private val session: MkvDramaSession) {
    private val emitted = Collections.synchronizedSet(mutableSetOf<String>())
    private val browserAttempts = AtomicInteger()
    private val browser = Mutex()

    /** Where a site `/_c/` link redirects, read without following it further. */
    suspend fun hop(url: String, referer: String): String? {
        if (URI(url).authority != URI(referer).authority || MkvDramaDecoder.isContainer(url)) return url
        return session.request(url, referer).location
    }

    /** Continue shortener pages until a container or file host, which is returned without being requested. */
    suspend fun land(url: String, referer: String, depth: Int = 0): String? {
        if (depth > 10 || !MkvDramaDecoder.isHttp(url)) return null
        if (MkvDramaDecoder.isContainer(url) || MkvDramaDecoder.isFileHost(url)) return url
        val response = session.request(url, referer)
        response.location?.let { return land(it, url, depth + 1) }
        val shorteners = MkvDramaDecoder.shorteners
        if (MkvDramaDecoder.bareHost(url) !in shorteners) return null
        val document = Jsoup.parse(response.text, url)
        val form = document.selectFirst("form#form-captcha, form#form-go, form#before-captcha, form#link-view")
        if (form != null && form.attr("method").equals("post", true)) {
            val values = form.select("input[name], textarea[name]").filter { !it.hasAttr("disabled") &&
                (it.attr("type") !in setOf("checkbox", "radio") || it.hasAttr("checked")) &&
                it.attr("type") !in setOf("submit", "button", "reset", "file") }
                .associate { it.attr("name") to if (it.tagName() == "textarea") it.text() else it.attr("value") }
            val action = URI(url).resolve(form.attr("action")).toString()
            val gated = values.any { (key, value) -> key in setOf("cf-turnstile-response", "g-recaptcha-response", "h-captcha-response") && value.isBlank() }
            if (!gated && MkvDramaDecoder.bareHost(action) in shorteners) {
                val next = session.request(action, url, form = values, post = true)
                next.location?.let { return land(it, action, depth + 1) }
                // The second form needs the returned page, not a duplicate GET of the POST URL.
                val go = Jsoup.parse(next.text, action).selectFirst("form#form-go")
                if (go != null) {
                    // Ouo's second continuation follows a countdown, as in the bypass client.
                    delay(6000)
                    val goUrl = URI(action).resolve(go.attr("action")).toString()
                    if (MkvDramaDecoder.bareHost(goUrl) in shorteners) {
                        val goValues = go.select("input[name]").associate { it.attr("name") to it.attr("value") }
                        val final = session.request(goUrl, action, form = goValues, post = true)
                        final.location?.let { return land(it, goUrl, depth + 1) }
                    }
                }
            }
        }
        // Let the site's browser verification run on-device; intercept only a real destination.
        if (browserAttempts.getAndIncrement() >= 2) return null
        val destination = browser.withLock {
            WebViewResolver(
                interceptUrl = Regex("""https?://(?:www\.)?(?:mkvdrama\.(?:net|org)/c/|viewcrate\.cc/c/|filecrypt\.(?:cc|co|to)/Container/|pixeldrain\.com/u/|gofile\.io/d/).*"""),
                userAgent = null, useOkhttp = false, timeout = 60_000L
            ).resolveUsingWebView(url).first?.url?.toString()
        }
        return destination?.let { land(it, url, depth + 1) }
    }

    /** Every row a container lists. A DLC export gives file-host URLs for all of them at once. */
    suspend fun export(url: String): List<MkvFile> {
        val response = session.get(url)
        val page = response.url
        val document = Jsoup.parse(response.text, page)
        return when {
            MkvDramaDecoder.bareHost(page) in MkvDramaDecoder.filecrypt -> filecrypt(response.text, document, page)
            document.selectFirst("#w[data-p=c][data-s]") != null -> native(document, page)
            document.selectFirst("[data-open-base-url]") != null -> viewcrate(response.text, document, page)
            else -> emptyList()
        }
    }

    private suspend fun native(document: Document, page: String): List<MkvFile> {
        val native = document.selectFirst("#w[data-p=c][data-s]") ?: return emptyList()
        val config = document.selectFirst("#xd") ?: error("Missing native container config")
        fun unmask(attr: String) = MkvDramaDecoder.unmask(mkvJson.readTree(config.attr(attr)))
        val token = String(unmask("data-n"), Charsets.US_ASCII)
        val slug = native.attr("data-s")
        require(slug.matches(Regex("[A-Za-z0-9_-]+")) && URI(page).path.trimEnd('/') == "/c/$slug" && token.matches(Regex("[a-f0-9]{32}")))
        val payload = session.get(MkvDramaDecoder.sameSitePath(page, "/c/$slug/p/$token"), page, true).json()
        val data = mkvJson.readTree(MkvDramaDecoder.gcm(payload.path("d").asText(), unmask("data-k"), unmask("data-v")))
        return data.path("links").map { item ->
            MkvFile(item.textOrNull("url").orEmpty(), item.textOrNull("label").orEmpty(), item.textOrNull("episode").orEmpty(),
                item.textOrNull("quality").orEmpty(), item.textOrNull("host").orEmpty())
        }.filter { MkvDramaDecoder.isHttp(it.url) }
    }

    private suspend fun viewcrate(html: String, document: Document, page: String): List<MkvFile> {
        val root = document.selectFirst("[data-open-base-url]") ?: return emptyList()
        fun local(value: String): String = URI(page).resolve(value).toString().also {
            require(URI(it).authority == URI(page).authority && MkvDramaDecoder.isHttp(it))
        }
        var cards = MkvDramaDecoder.viewcrateCards(html, page)
        var showDlc = document.selectFirst("#dlc_btn_container button") != null
        if (cards.isEmpty()) {
            val bootstrap = document.selectFirst("script#vc-container-bootstrap")?.data()?.takeIf { it.isNotBlank() }?.let(mkvJson::readTree)
                ?: run {
                    require(root.attr("data-bootstrap-url").isNotBlank()) { "No container bootstrap endpoint" }
                    session.get(local(root.attr("data-bootstrap-url")), page, true).json()
                }
            val data = mkvJson.readTree(MkvDramaDecoder.gcm(bootstrap.path("c").asText(), MkvDramaDecoder.decodeBase64(bootstrap.path("k").asText()), MkvDramaDecoder.decodeBase64(bootstrap.path("i").asText())))
            val allowed = bootstrap.path("r").map { it.asText().lowercase() }
            val base = local(root.attr("data-open-base-url")).trimEnd('/')
            showDlc = data.path("show_dlc").asBoolean(false)
            cards = data.path("d").flatMap { group ->
                group.path("l").map { link ->
                    val id = link.path("u").asText()
                    require(id.matches(Regex("[a-f0-9]{16}")))
                    val privacy = if (link.path("h").asText().lowercase() in allowed) "allow-referrer" else "noreferrer"
                    MkvFile("$base/$id?privacy=$privacy", link.path("n").asText(), group.path("t").asText(), host = link.path("h").asText())
                }
            }
        }
        if (showDlc && cards.isNotEmpty()) try {
            val id = URI(page).path.trimEnd('/').substringAfterLast('/')
            require(id.matches(Regex("[a-f0-9]{32}"))) { "Unrecognized ViewCrate container URL" }
            val prefix = URI(page).path.substringBeforeLast("/c/")
            val dlcUrl = local(session.get(local("$prefix/api/dlc_url/$id"), page, true).json().textOrNull("url") ?: error("No ViewCrate DLC URL"))
            require(URI(dlcUrl).path.endsWith(".dlc")) { "Unexpected ViewCrate DLC URL" }
            val entries = dlc(dlcUrl, page)
            if (entries.size == cards.size) return MkvDramaDecoder.withHeadings(entries, cards)
            Log.w("MkvDrama", "ViewCrate DLC lists ${entries.size} of ${cards.size} files; using individual links")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { Log.w("MkvDrama", "ViewCrate DLC failed: ${e.javaClass.simpleName}; using individual links") }
        return cards
    }

    private suspend fun filecrypt(html: String, document: Document, page: String): List<MkvFile> {
        if (document.selectFirst("#pow-captcha, #cform, input[type=password]") != null) return emptyList()
        val rows = document.select("tr").mapNotNull { row ->
            val anchor = row.selectFirst("a.download[href]") ?: return@mapNotNull null
            val link = anchor.absUrl("href")
            if (!MkvDramaDecoder.isHttp(link) || MkvDramaDecoder.bareHost(link) !in MkvDramaDecoder.filecrypt) return@mapNotNull null
            MkvFile(link, row.selectFirst("td[title]")?.attr("title").orEmpty(), host = row.selectFirst("a.external_link")?.text().orEmpty(),
                offline = row.selectFirst(".status .offline") != null)
        }
        MkvDramaDecoder.filecryptDlc(html, page)?.let { url ->
            try {
                // Unnamed entries cannot be assigned to episodes; the rows still can.
                val entries = dlc(url, page)
                if (entries.all { it.filename.isNotBlank() }) return entries
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Log.w("MkvDrama", "Filecrypt DLC failed: ${e.javaClass.simpleName}; using individual links") }
        }
        if (rows.isNotEmpty()) return rows
        // Click'n'Load exports carry no file names, so they only serve movies.
        val exports = document.select("form").mapNotNull { form ->
            val key = form.selectFirst("input[name=jk]")?.attr("value") ?: return@mapNotNull null
            val crypted = form.selectFirst("input[name=crypted]")?.attr("value") ?: return@mapNotNull null
            mkvJson.createObjectNode().put("jk", key).put("crypted", crypted)
        }
        return exports.flatMap { MkvDramaDecoder.cnl(it) }.map { MkvFile(it) }
    }

    /** Download a DLC container and decode it locally; only its key token goes to JDownloader's key service. */
    private suspend fun dlc(url: String, referer: String): List<MkvFile> {
        val container = session.request(url, referer, binary = true)
        check(container.location == null) { "DLC download redirected" }
        // The cookie jar holds no cookies for the key service's domain.
        val key = session.request(MkvDramaDecoder.DLC_SERVICE, post = true,
            form = mapOf("destType" to "jdtc6", "srcType" to "dlc", "data" to MkvDramaDecoder.dlcToken(container.text)))
        return MkvDramaDecoder.dlc(container.text, key.text)
    }

    /** Follow a container row's own redirect pages to its file host, stopping before the host is requested. */
    suspend fun destination(url: String, referer: String, depth: Int = 0): String? {
        if (depth > 6 || !MkvDramaDecoder.isHttp(url)) return null
        val host = MkvDramaDecoder.bareHost(url)
        if (host !in MkvDramaDecoder.viewcrate && host !in MkvDramaDecoder.filecrypt) return url
        val response = session.request(url, referer)
        response.location?.let { return destination(it, url, depth + 1) }
        if (host !in MkvDramaDecoder.filecrypt) return null
        // Filecrypt /Link/ pages advertise a same-host /Go/ navigation before the file redirect.
        val go = Regex("""(?:https?://(?:www\.)?filecrypt\.(?:cc|co|to))?/Go/[A-Za-z0-9]+\.html""")
            .findAll(response.text).map { URI(url).resolve(it.value).toString() }.firstOrNull { it != url } ?: return null
        return destination(go, url, depth + 1)
    }

    /** Emit one file-host URL: Pixeldrain directly after a metadata check, media as-is, others through extractors. */
    suspend fun emit(
        file: MkvFile, episode: Int?, label: String, quality: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = file.url
        val path = URI(url).path
        val fileQuality = getQualityFromName(file.quality.ifBlank { quality })
        if (MkvDramaDecoder.bareHost(url) == "pixeldrain.com") {
            val id = Regex("^/(?:u|api/file)/([A-Za-z0-9]+)$").matchEntire(path)?.groupValues?.get(1) ?: return false
            val info = session.get("https://pixeldrain.com/api/file/$id/info", acceptJson = true).json()
            if (!info.path("can_download").asBoolean(true) || info.path("success").asBoolean(true).not()) return false
            // The uploaded file's name outranks its row label for episode membership.
            if (episode != null && !MkvDramaDecoder.matches(MkvFile(url, info.textOrNull("name") ?: file.filename, file.episode), episode)) return false
            val direct = "https://pixeldrain.com/api/file/$id?download"
            if (emitted.add(direct)) callback(newExtractorLink("MkvDrama", "MKVDrama · Pixeldrain · $label", direct, ExtractorLinkType.VIDEO) {
                this.quality = fileQuality
                this.referer = ""
                headers = mapOf("User-Agent" to USER_AGENT)
            })
            return true
        }
        if (MkvDramaDecoder.isMedia(url)) {
            if (emitted.add(url)) callback(newExtractorLink("MkvDrama", "MKVDrama · $label", url,
                if (path.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = fileQuality
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
}
