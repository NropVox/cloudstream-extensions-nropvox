package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * Pure parsing for XPrimeHub post bodies (`content.rendered` of the REST API) and the nexdrive/file-host pages it
 * links to. Nothing here touches the network or the Cloudstream library.
 */
object XPrimeHubParser {
    /** File host of a nexdrive link, read from its URL. */
    enum class Host { GDIRECT, VCLOUD, HUBCLOUD, VDRIVE, PIXELDRAIN, OTHER }

    private val leadingNoise = Regex("""^\s*(?:Download\s+)?(?:\[18\+\]\s*)?""", RegexOption.IGNORE_CASE)
    private val yearInParens = Regex("""\((\d{4})\)""")
    // Without a year the title ends at the first language, quality or source token.
    private val titleTail = Regex(
        """\s+(UNRATED|UNCUT|Hindi|English|Tagalog|Japanese|Korean|Dual Audio|\d{3,4}p|WEB-DL|WEB Series|Full Movie|Short Film)\b""",
        RegexOption.IGNORE_CASE
    )
    // `S01 Part 2`, `S01E07&08`, `Season 1 Part 2`: kept in the title so the parts of a season stay apart.
    private val seasonTag = Regex(
        """\bS\d{1,2}(?:E\d+(?:\s*&\s*\d+)?)?(?:\s+Part\s*\d+)?\b|\bSeason\s*\d+(?:\s+Part\s*\d+)?\b""",
        RegexOption.IGNORE_CASE
    )
    private val seasonNumber = Regex("""\bS(\d{1,2})\b|\bSeason\s*(\d+)""", RegexOption.IGNORE_CASE)
    // `[18+] Paap S01 Hindi {Episode 1} 720p [150MB]`
    private val episodeHeading = Regex("""\{\s*Episodes?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val height = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
    private val size = Regex("""\[\s*[\d.]+\s*[MG]B\s*\]""", RegexOption.IGNORE_CASE)
    private val reurl = Regex("""var reurl = "([^"]+)"""")
    private val doubleBase64 = Regex("""atob\(atob\('([^']+)'\)\)""")
    private val hubcloudUrl = Regex("""var url = '([^']+)'""")
    private val vdriveShare = Regex("""/s/([A-Za-z0-9]+)""")
    private val pixeldrainFile = Regex("""/(?:u|api/file)/([A-Za-z0-9]+)""")
    private val whitespace = Regex("""\s+""")

    fun unescape(html: String): String = Parser.unescapeEntities(html, false)

    fun cleanTitle(rendered: String): String {
        val raw = unescape(rendered).replace(leadingNoise, "")
        val year = yearInParens.find(raw)
        val base = when {
            year != null -> raw.substring(0, year.range.first)
            else -> titleTail.find(raw)?.let { raw.substring(0, it.range.first) } ?: raw
        }
        val tag = seasonTag.find(raw, base.length)?.value
        return listOfNotNull(base.trim(' ', '-', '–', ':', '|'), tag).joinToString(" ").replace(whitespace, " ").trim()
    }

    fun year(rendered: String): Int? = yearInParens.find(rendered)?.groupValues?.get(1)?.toIntOrNull()

    fun episode(heading: String): Int? = episodeHeading.find(heading)?.groupValues?.get(1)?.toIntOrNull()

    fun season(text: String): Int? = seasonNumber.find(text)?.let { m ->
        (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    /** Pixel height named in [text] (`720p`, `4K`), or 0. */
    fun quality(text: String): Int = height.find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: if (text.contains("4K", ignoreCase = true)) 2160 else 0

    fun size(text: String): String? = size.find(text)?.value?.replace(" ", "")

    fun host(url: String): Host {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return when {
            host.startsWith("fastdl.") -> Host.GDIRECT
            host.startsWith("vcloud.") -> Host.VCLOUD
            host.startsWith("hubcloud.") -> Host.HUBCLOUD
            "vegadrive." in host -> Host.VDRIVE
            host.startsWith("pixeldrain.") -> Host.PIXELDRAIN
            else -> Host.OTHER
        }
    }

    private fun Element.cleanText() = text().replace("⚡", "").replace(whitespace, " ").trim()

    fun body(contentHtml: String, baseUrl: String): Document = Jsoup.parseBodyFragment(contentHtml, baseUrl)

    /**
     * Download headings of a post, each with its nexdrive buttons, in page order. Old posts wrap a `<button>` in
     * the link; posts from 2026-10 on use `a.xp-download-btn`.
     */
    fun groups(doc: Document): List<XphGroup> {
        val groups = mutableListOf<XphGroup>()
        var heading: String? = null
        var buttons = mutableListOf<XphPage>()
        fun flush() {
            heading?.let { if (buttons.isNotEmpty()) groups += XphGroup(it, buttons) }
            buttons = mutableListOf()
        }
        for (el in doc.select("h2, h3, h4, h5, h6, a[href]")) {
            if (el.tagName() != "a") {
                flush()
                heading = el.cleanText()
                continue
            }
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            val isButton = el.selectFirst("button") != null || el.hasClass("xp-download-btn") || "nexdrive." in href
            if (heading != null && isButton && href.startsWith("http")) buttons += XphPage(heading!!, el.cleanText(), href)
        }
        flush()
        return groups
    }

    /** Host links of a nexdrive page (G-Direct, V-Cloud, V-Drive, Filepress, ...). */
    fun hostLinks(doc: Document): List<String> = (doc.selectFirst(".entry-inner") ?: doc).select("a[href]")
        .map { it.attr("abs:href").ifBlank { it.attr("href") } }
        .filter { it.startsWith("http") && host(it) != Host.OTHER }
        .distinct()

    /** A `Cast` / `Genre` / `Language` detail from the info table (new posts) or the `<strong>Cast:</strong> …<br>` list (old). */
    fun detail(doc: Document, key: String): String? {
        doc.select("table.xp-info-table tr").firstOrNull { it.selectFirst("td.xp-td-label")?.text()?.contains(key, true) == true }
            ?.select("td")?.getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val label = doc.select("strong").firstOrNull { it.text().trim().removeSuffix(":").trim().equals(key, true) } ?: return null
        return (label.nextSibling() as? TextNode)?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    fun list(value: String?): List<String> = value?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

    /** `p.xp-plot-box`, or the paragraph after the `Storyline/PLOT` heading. Old SEO filler (`Download [18+] …`) is dropped. */
    fun plot(doc: Document): String? {
        doc.selectFirst("p.xp-plot-box")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val head = doc.select("h2, h3, h4").firstOrNull { it.text().contains("PLOT", true) } ?: return null
        var next = head.nextElementSibling()
        while (next != null && !next.tagName().matches(Regex("h\\d"))) {
            if (next.tagName() == "p" && next.text().isNotBlank()) {
                return next.text().trim().takeUnless { it.startsWith("Download", true) }
            }
            next = next.nextElementSibling()
        }
        return null
    }

    /** First screenshot, used as the backdrop. */
    fun screenshot(doc: Document, siteHost: String): String? = doc.select("img[src]")
        .map { it.attr("abs:src") }
        .firstOrNull { it.startsWith("http") && siteHost !in it }

    /** G-Direct: the embed page names the direct file in `var reurl`, behind a `dl.php?link=` ad wrapper. */
    fun directUrl(embedHtml: String): String? = reurl.find(embedHtml)?.groupValues?.get(1)?.let {
        if ("dl.php?link=" in it) it.substringAfter("dl.php?link=") else it
    }

    /** V-Cloud landing page: the download button's target hides in `atob(atob('…'))`. */
    fun vcloudNext(landingHtml: String, decode: (String) -> String): String? =
        doubleBase64.find(landingHtml)?.groupValues?.get(1)?.let { decode(decode(it)) }

    /** HubCloud drive page: the `#download` button (also in `var url`) opens the same server list as V-Cloud. */
    fun hubcloudNext(doc: Document): String? =
        doc.selectFirst("a#download")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: hubcloudUrl.find(doc.html())?.groupValues?.get(1)

    /** V-Drive share id of `https://one.vegadrive.app/s/<id>`. */
    fun vdriveId(url: String): String? = vdriveShare.find(url)?.groupValues?.get(1)

    /** Pixeldrain file id of `/u/<id>` or `/api/file/<id>`. */
    fun pixeldrainId(url: String): String? = pixeldrainFile.find(url)?.groupValues?.get(1)

    /** V-Cloud and HubCloud result page: server name (`FSL Server`) to link. Links with an empty href are skipped. */
    fun mirrors(doc: Document): List<Pair<String, String>> = doc.select("a[href]").mapNotNull { a ->
        val text = a.cleanText()
        val href = a.attr("href")
        if (!text.startsWith("Download [") || !href.startsWith("http")) return@mapNotNull null
        text.substringAfter('[').substringBefore(']').trim() to href
    }
}
