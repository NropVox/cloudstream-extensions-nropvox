package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Pure parsing for AnimeFlix posts (theme `animeflix-lite`), its episodes.animeflix.my list pages and the DriveSeed
 * file page. Nothing here touches the network or the Cloudstream library, so each selector is covered by a JVM test
 * against a page saved during recon.
 */
object AnimeFlixParser {
    private val leadingNoise = Regex("""^\s*Down?load\s+""", RegexOption.IGNORE_CASE)
    // The name ends where the SEO tail starts: a year or season in parentheses, a bracket/brace, or a season/audio token.
    private val titleTail = Regex(
        """\s*\(\s*(\d{4}|Season|S\d)|\s*[\[{]|\s+(Season\s*\d|S\d{2}\b|Dual Audio|Multi Audio|Hindi|English|Eng Subbed|Japanese|\d{3,4}p)""",
        RegexOption.IGNORE_CASE
    )
    private val leadingNumber = Regex("""^\s*(\d+)""")
    private val seasonLabel = Regex("""\bSeason\s*(\d+)""", RegexOption.IGNORE_CASE)
    // File names use underscores (`_720p_`), which \b doesn't treat as a boundary.
    private val height = Regex("""(?<![0-9])(\d{3,4})p(?![a-z0-9])""", RegexOption.IGNORE_CASE)
    private val episodeLabel = Regex("""\bEpisode\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val perEpisode = Regex("""\s*per episode\s*""", RegexOption.IGNORE_CASE)
    private val fileSize = Regex("""([\d.]+)\s*(MB|GB)""", RegexOption.IGNORE_CASE)
    private val jsRedirect = Regex("""location\.replace\(\s*["']([^"']+)["']""")
    private val metaRedirect = Regex("""url=([^"'\s>]+)""", RegexOption.IGNORE_CASE)

    fun body(html: String, baseUri: String): Document = Jsoup.parse(html, baseUri)

    fun cleanTitle(raw: String): String {
        var title = raw.replace(leadingNoise, "")
        titleTail.find(title)?.let { title = title.substring(0, it.range.first) }
        return title.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', ':', '|')
    }

    /**
     * A listing URL for [page]. Row data is a path with an optional query (`category/movies/`, `?af_status=Completed`);
     * WordPress wants the page before the query: `/page/2/?af_status=Completed`.
     */
    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val path = data.substringBefore('?')
        val query = data.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return if (page <= 1) "$mainUrl/$path$query" else "$mainUrl/${path}page/$page/$query"
    }

    /** `?s=` redirects to `/search/<q>`, which pages like a category. */
    fun searchUrl(mainUrl: String, query: String, page: Int): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return if (page <= 1) "$mainUrl/search/$q/" else "$mainUrl/search/$q/page/$page/"
    }

    /** Pixel height named in [text] (`720p`, `_1080p_`), or 0. */
    fun quality(text: String): Int = height.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Cards of the main grid. The home page's hero and "Complete seasons" rail sit outside `[data-grid]`. */
    fun cards(doc: Document): List<AfCard> = cardsIn(doc.select("[data-grid] a.card[href]"))

    /** "More like this" on a post. */
    fun recommendations(doc: Document): List<AfCard> = cardsIn(doc.select("section[aria-labelledby=more] a.card[href]"))

    private fun cardsIn(links: List<org.jsoup.nodes.Element>): List<AfCard> = links.mapNotNull { a ->
        val url = a.attr("abs:href").ifBlank { return@mapNotNull null }
        val title = a.selectFirst(".card-t")?.text()?.trim()?.ifBlank { null }
            ?: cleanTitle(a.attr("title")).ifBlank { return@mapNotNull null }
        val meta = a.selectFirst(".card-m")?.text().orEmpty()
        AfCard(url, title, a.selectFirst("img")?.attr("abs:src")?.ifBlank { null }, meta.trim().startsWith("Movie"))
    }.distinctBy { it.url }

    /** Listings have `a.next.page-numbers`; the home page only its "Load more" button (`a[data-more]`). */
    fun hasNext(doc: Document) = doc.selectFirst("a.next.page-numbers, a[data-more]") != null

    fun details(doc: Document): AfDetails {
        val info = doc.select("dl.info dt").associate { dt ->
            dt.text().trim() to (dt.nextElementSibling()?.takeIf { it.tagName() == "dd" }?.text()?.trim().orEmpty())
        }
        return AfDetails(
            title = doc.selectFirst("section.t-hero h1, h1")?.text()?.trim()?.ifBlank { null },
            poster = doc.selectFirst(".t-poster img")?.attr("abs:src")?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
            plot = doc.selectFirst("#af-summary")?.text()?.trim()?.ifBlank { null },
            genres = doc.select(".t-info a.chip").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            rating = doc.selectFirst(".t-info .rating")?.text()?.trim()?.toDoubleOrNull(),
            info = info,
        )
    }

    /** Leading number of a sidebar value: `Released` `2026`, `Season` `1-2` → 1. */
    fun leadingInt(value: String?): Int? = value?.let { leadingNumber.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * The download cards of `section#download`, by season. Multi-season posts wrap each season in
     * `details.dl-season` (`Season 2 (3)`); single-season posts have one `.dl` whose season is the sidebar's.
     */
    fun seasons(doc: Document, defaultSeason: Int): List<AfSeason> {
        val section = doc.selectFirst("section#download") ?: return emptyList()
        val groups = section.select("details.dl-season")
        if (groups.isEmpty()) {
            return listOf(AfSeason(defaultSeason, qualities(section))).filter { it.qualities.isNotEmpty() }
        }
        return groups.mapIndexed { index, group ->
            val label = group.selectFirst("summary")?.text().orEmpty()
            val season = seasonLabel.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
            AfSeason(season, qualities(group))
        }.filter { it.qualities.isNotEmpty() }
    }

    private fun qualities(root: org.jsoup.nodes.Element): List<AfQuality> = root.select(".dl-card").mapNotNull { card ->
        val url = card.selectFirst("a[href*=/archives/]")?.attr("abs:href")?.ifBlank { null } ?: return@mapNotNull null
        AfQuality(
            quality = card.selectFirst(".q")?.text()?.trim().orEmpty(),
            sub = card.selectFirst(".dl-sub")?.text()?.replace(perEpisode, " ")?.trim().orEmpty(),
            url = url,
        )
    }

    /** `720p · 10bit · 180MB`: what tells two cards of one height apart. */
    fun label(quality: AfQuality): String = listOf(quality.quality, quality.sub).filter { it.isNotBlank() }.joinToString(" · ")

    /** Links of an episodes.animeflix.my list page. Batch/zip links aren't episodes. */
    fun entries(doc: Document): List<AfEntry> = doc.select(".entry-content a[href*=/getlink/]").mapNotNull { a ->
        val label = a.text().trim(' ', '⌈', '⌋')
        if (label.contains("batch", true) || label.contains("zip", true)) return@mapNotNull null
        AfEntry(label, episodeLabel.find(label)?.groupValues?.get(1)?.toIntOrNull(), a.attr("abs:href"))
    }.distinctBy { it.url }

    /** The target of a `location.replace("…")` page (with `\/` escapes), or of a meta refresh. */
    fun redirect(html: String): String? =
        (jsRedirect.find(html) ?: metaRedirect.find(html))?.groupValues?.get(1)?.replace("\\/", "/")

    data class DriveFile(val name: String?, val sizeMb: Double?, val cloud: String?, val instant: String?, val direct: String?)

    /** DriveSeed's `/file/<code>` page: the R2 "Cloud Download", the "Instant Download" redirector and `/wfile/` if any. */
    fun driveFile(doc: Document): DriveFile {
        fun item(key: String) = doc.select("li.list-group-item").map { it.text() }.firstOrNull { it.startsWith(key) }
            ?.substringAfter(':')?.trim()
        fun button(label: String) = doc.select("a.btn[href]").firstOrNull { it.text().trim().equals(label, true) }
            ?.attr("abs:href")?.takeIf { it.startsWith("http") }
        val direct = doc.selectFirst("a.btn[href*=/wfile/]")?.attr("abs:href")?.ifBlank { null }
        return DriveFile(item("Name"), item("Size")?.let { sizeMb(it) }, button("Cloud Download"), button("Instant Download"), direct)
    }

    /** `178.76MB` / `1.2GB` → megabytes. */
    fun sizeMb(text: String): Double? = fileSize.find(text)?.let { m ->
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        if (m.groupValues[2].equals("GB", true)) value * 1024 else value
    }

    /** The Cloudflare Worker download link on a `/wfile/<code>?type=1` page. */
    fun workerLink(doc: Document): String? =
        doc.selectFirst("a.btn[href*=workers.dev]")?.attr("abs:href")?.ifBlank { null }

    /** The Instant redirector answers `302 https://video-seed.dev/?url=<google URL>`; the player wants the Google URL. */
    fun instantTarget(location: String): String =
        if (location.contains("?url=")) location.substringAfter("?url=") else location
}
