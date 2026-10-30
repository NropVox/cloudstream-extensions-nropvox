package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Pure parsing for MoviesMod posts, the modpro link pages, the SafeLink gate in front of every file and the
 * DriveSeed file page. Nothing here touches the network or the Cloudstream library, so each selector is covered by a
 * JVM test against a page saved during recon.
 */
object MoviesModParser {
    // One post spells it "Dowload".
    private val leadingNoise = Regex("""^\s*Down?load\s+""", RegexOption.IGNORE_CASE)
    // The name ends where the SEO tail starts: a year or season in parentheses, a brace/bracket, or an audio/rip token.
    private val titleTail = Regex(
        """\s*\(\s*(\d{4}|Season|S\d)|\s*[\[{]|\s+(Season\s*\d|S\d{2}\b|Dual Audio|Multi Audio|Hindi|English|\d{3,4}p|WEB-?DL|BluRay)""",
        RegexOption.IGNORE_CASE
    )
    private val yearInParens = Regex("""\((\d{4})\)""")
    private val seriesTitle = Regex("""\bSeason\b|\bS\d{2}\b|\bEpisodes?\b""", RegexOption.IGNORE_CASE)
    private val seasonLong = Regex("""\bSeason\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val seasonShort = Regex("""\bS(\d{1,2})\b""")
    private val height = Regex("""\b(\d{3,4})p\b""", RegexOption.IGNORE_CASE)
    private val added = Regex("""\bE(\d+)\s*Added""", RegexOption.IGNORE_CASE)
    private val episodeLabel = Regex("""\bEpisode\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val language = Regex("""\{([^}]+)\}""")
    private val technical = Regex("""\b(2160p|1080p|720p|480p|4K|HEVC|x265|x264|10bit|HDR)\b""", RegexOption.IGNORE_CASE)
    private val size = Regex("""\[\s*[\d.]+\s*(?:MB|GB)\s*\]""", RegexOption.IGNORE_CASE)
    private val imdb = Regex("""imdb\.com/title/(tt\d+)""")
    private val gateField = Regex("""_(lp|wp)_http2?""")
    private val goToken = Regex("""[?&](lp_go|go)=([\w-]+)""")
    private val jsRedirect = Regex("""location\.replace\(\s*["']([^"']+)["']""")
    private val metaRedirect = Regex("""url=([^"'\s>]+)""", RegexOption.IGNORE_CASE)

    fun body(html: String, baseUri: String): Document = Jsoup.parse(html, baseUri)

    fun cleanTitle(raw: String): String {
        var title = raw.replace(leadingNoise, "")
        titleTail.find(title)?.let { title = title.substring(0, it.range.first) }
        return title.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', ':', '|')
    }

    fun year(raw: String): Int? = yearInParens.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    fun isSeries(raw: String): Boolean = seriesTitle.containsMatchIn(raw)

    /** Pixel height named in [text] (`720p`), or 0. */
    fun quality(text: String): Int = height.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** `Season 2 {…}` and `S01 (E06 Added)` name their season; a heading without one is season 1. */
    fun season(heading: String): Int =
        (seasonLong.find(heading) ?: seasonShort.find(heading))?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** `S01 (E06 Added) …` → 6: how far an ongoing season's quality has got. */
    fun addedEpisodes(heading: String): Int? = added.find(heading)?.groupValues?.get(1)?.toIntOrNull()

    /** Audio languages, codec and size from a download heading, for the link name. */
    fun tags(heading: String): String = listOfNotNull(
        language.find(heading)?.value,
        technical.findAll(heading).map { it.value }.distinctBy { it.lowercase() }.joinToString(" ").ifBlank { null },
        size.find(heading)?.value,
    ).joinToString(" ")

    fun isBatch(label: String) = label.contains("batch", true) || label.contains("zip", true)

    /** Cards of the home, category and search pages. */
    fun cards(doc: Document): List<MmCard> = doc.select("article.latestPost").mapNotNull { article ->
        val a = article.selectFirst("h2.title a, a[href]") ?: return@mapNotNull null
        val url = a.attr("abs:href").ifBlank { return@mapNotNull null }
        val title = a.attr("title").ifBlank { a.text() }.trim()
        MmCard(url, title, article.selectFirst("img")?.attr("abs:src")?.ifBlank { null })
    }

    fun hasNext(doc: Document) = doc.selectFirst("a.next.page-numbers") != null

    fun details(doc: Document): MmDetails {
        val box = doc.selectFirst("div.imdbwp")
        val footer = box?.selectFirst(".imdbwp__footer")
        val actors = footer?.select("strong")?.firstOrNull { it.text().startsWith("Actors") }?.nextElementSibling()?.text()
        return MmDetails(
            title = box?.selectFirst(".imdbwp__title")?.text()?.trim()?.ifBlank { null },
            year = box?.selectFirst(".imdbwp__header")?.text()?.let { year(it) },
            // The IMDb plugin embeds TMDB's w185 poster; w500 is the same image at a size the result page can use.
            poster = box?.selectFirst("img.imdbwp__img")?.attr("abs:src")?.ifBlank { null }?.replace("/w185/", "/w500/")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
            plot = box?.selectFirst(".imdbwp__teaser")?.text()?.trim()?.ifBlank { null },
            genres = box?.select(".imdbwp__meta span")?.getOrNull(1)?.text()?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() && it != "N/A" }.orEmpty(),
            rating = box?.selectFirst(".imdbwp__star")?.text()?.trim()?.toDoubleOrNull(),
            actors = actors?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            imdbId = (box?.selectFirst("a.imdbwp__link") ?: doc.selectFirst("a[href*=imdb.com/title/]"))
                ?.attr("href")?.let { imdb.find(it)?.groupValues?.get(1) },
        )
    }

    fun isAnime(doc: Document) = doc.selectFirst(".thecategory a[href*=/anime/]") != null

    /** Download headings in post order, each with the maxbuttons that follow it. Headings without buttons are dropped. */
    fun groups(doc: Document): List<MmGroup> {
        val content = doc.selectFirst("div.thecontent") ?: return emptyList()
        val groups = mutableListOf<MmGroup>()
        var heading: String? = null
        var buttons = mutableListOf<MmButton>()
        fun flush() {
            if (heading != null && buttons.isNotEmpty()) groups += MmGroup(heading!!, buttons)
            buttons = mutableListOf()
        }
        for (el in content.select("h2, h3, h4, a.maxbutton")) {
            if (el.tagName() == "a") {
                val url = el.attr("abs:href")
                if (heading != null && url.isNotBlank()) buttons += MmButton(el.text().trim(), url)
            } else {
                flush()
                heading = el.text().replace(Regex("""\s+"""), " ").trim()
            }
        }
        flush()
        return groups
    }

    /** Series posts link their episode lists on `episodes.modpro.blog`; movies link server pages on other modpro hosts. */
    fun isEpisodeList(url: String) = url.contains("://episodes.")

    /** `(episode, gate URL)` from an episodes.modpro.blog page; the All Episodes Batch link is not an episode. */
    fun episodes(doc: Document): List<Pair<Int, String>> = doc.select(".entry-content a[href]").mapNotNull { a ->
        val number = episodeLabel.find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        if (isBatch(a.text())) return@mapNotNull null
        number to a.attr("abs:href")
    }.distinctBy { it.first }

    /** Server buttons of a links.modpro.blog / posts.modpro.blog page, deduplicated (several labels share one gate). */
    fun servers(doc: Document): List<String> = doc.select(".entry-content a[href*=sid=]")
        .filterNot { it.text().contains("comment", true) }
        .map { it.attr("abs:href") }
        .distinct()

    /**
     * The gate's form for the next hop. Two generations of the gate exist: `_lp_http`/`_lp_http2` on
     * `form#lp-land`/`form#lp-s1-form`, and `_wp_http`/`_wp_http2` on `form#landing`; both are found by field name.
     */
    fun gateForm(doc: Document): MmForm? = doc.select("form").firstNotNullOfOrNull { form ->
        val fields = form.select("input[name]").associate { it.attr("name") to it.attr("value") }
        if (fields.keys.none { gateField.matches(it) }) return@firstNotNullOfOrNull null
        MmForm(form.attr("abs:action").ifBlank { doc.location() }, fields)
    }

    /** The last hop: `?lp_go=<token>` (or `?go=` on the old gate), which wants cookie `<token>=<*_http2 value>`. */
    fun goToken(html: String): Pair<String, String>? = goToken.find(html)?.let { it.groupValues[1] to it.groupValues[2] }

    fun http2(form: MmForm): String? = form.fields.entries.firstOrNull { it.key.endsWith("_http2") }?.value

    /** The target of a `location.replace("…")` page (with `\/` escapes), or of a meta refresh. */
    fun redirect(html: String): String? =
        (jsRedirect.find(html) ?: metaRedirect.find(html))?.groupValues?.get(1)?.replace("\\/", "/")

    data class DriveFile(val name: String?, val cloud: String?, val instant: String?, val direct: String?)

    /**
     * DriveSeed's `/file/<code>` page: the R2 "Cloud Download", the "Instant Download" redirector and the `/wfile/`
     * page behind "Direct Links" (labelled "Direct Download" on some files).
     */
    fun driveFile(doc: Document): DriveFile {
        val name = doc.select("li.list-group-item").map { it.text() }.firstOrNull { it.startsWith("Name") }
            ?.substringAfter(':')?.trim()
        fun button(label: String) = doc.select("a.btn[href]").firstOrNull { it.text().trim().equals(label, true) }
            ?.attr("abs:href")?.takeIf { it.startsWith("http") }
        val direct = doc.selectFirst("a.btn[href*=/wfile/]")?.attr("abs:href")?.ifBlank { null }
        return DriveFile(name, button("Cloud Download"), button("Instant Download"), direct)
    }

    /** The Cloudflare Worker download link on a `/wfile/<code>?type=1` page. */
    fun workerLink(doc: Document): String? =
        doc.selectFirst("a.btn[href*=workers.dev]")?.attr("abs:href")?.ifBlank { null }

    /** The Instant redirector answers `302 https://video-seed.dev/?url=<google URL>`; the player wants the Google URL. */
    fun instantTarget(location: String): String =
        if (location.contains("?url=")) location.substringAfter("?url=") else location
}
