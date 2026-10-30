package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.io.encoding.Base64

/** Code matching and the page shapes of the sites JavStream looks a SexTB code up on. */
internal object JavStreamParser {
    private val tokenRegex = Regex("[a-z]+|[0-9]+")
    private val fc2Regex = Regex("""^fc2[-_ ]?ppv[-_ ]?(\d+)$""", RegexOption.IGNORE_CASE)
    private val missavSuffixes = "(?:-uncensored-leak|-english-subtitle)?"
    private val javFc2SrcRegex = Regex("""src:\s*['"](https?://[^'"]+)['"]""")
    private val javhdServerRegex = Regex("""server\((\d+),\s*(\d+)\)""")
    private val atobRegex = Regex("""window\.atob\("([^"]+)"\)""")
    private val olidRegex = Regex("""var OLID = '([^']+)'""")

    /** `fc2ppv-5000545` → `fc[-_ ]?2[-_ ]?ppv[-_ ]?5000545`, so each site's own spelling of the code matches. */
    private fun codeBody(code: String): String? {
        val tokens = tokenRegex.findAll(code.lowercase()).map { it.value }.toList()
        if (tokens.none { it[0].isDigit() } || tokens.none { it[0].isLetter() }) return null
        return tokens.joinToString("[-_ ]?")
    }

    /**
     * Matches the code as a whole word: `MIDA-605` is found in `[4K]MIDA-605 Newcomer…` and `/jav/mida-605/`,
     * but not in `MIDA-6050` or SexTB's `MIDA-605-RM` (a different upload of the same title).
     */
    fun codeRegex(code: String): Regex? = codeBody(code)?.let {
        Regex("(?<![a-z0-9])$it(?![a-z0-9]|[-_][a-z0-9])", RegexOption.IGNORE_CASE)
    }

    /** Not every site understands `fc2ppv-NNN`, but all of them find an FC2 title by its number. */
    fun searchQuery(code: String): String = fc2Regex.find(code)?.groupValues?.get(1) ?: code

    /** Movie `data` from before it became JSON: the bare SexTB code, which the app made absolute against sextb.net. */
    fun legacyLinks(data: String, sextb: String): JavStreamLinks {
        val code = data.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return JavStreamLinks(url = if (data.startsWith("http")) data else "$sextb/$code", code = code)
    }

    fun absolute(href: String, base: String): String? =
        href.trim().takeIf { it.isNotEmpty() }?.let { runCatching { URI("$base/").resolve(it).toString() }.getOrNull() }

    private fun Element.mentions(pattern: Regex): Boolean =
        pattern.containsMatchIn(text()) || select("a[href], img[alt]").any { a ->
            listOf("href", "title", "alt").any { pattern.containsMatchIn(a.attr(it)) }
        }

    /** The page linked from the first of the [cards] that mentions [code]. */
    fun findPage(root: Element, cards: String, link: String, code: String, base: String): String? {
        val pattern = codeRegex(code) ?: return null
        return root.select(cards).firstOrNull { it.mentions(pattern) }
            ?.selectFirst(link)?.let { absolute(it.attr("href"), base) }
    }

    /** MissAV has `/en/{code}` plus `-uncensored-leak`/`-english-subtitle` uploads; the plain one first. */
    fun missavPage(root: Element, code: String): String? {
        val slug = Regex("^${codeBody(code) ?: return null}$missavSuffixes$", RegexOption.IGNORE_CASE)
        return root.select("div.grid.grid-cols-2 > div a[href]")
            .map { it.attr("href").substringBefore('?').substringBefore('#').trimEnd('/') }
            .filter { slug.matches(it.substringAfterLast('/')) }
            .minByOrNull { it.length }
    }

    fun javggUrl(base: String, code: String): String = "$base/jav/${code.lowercase()}/"

    fun javggEmbeds(root: Element): List<String> =
        root.select(".pframe > iframe").map { it.attr("src").trim() }.filter { it.startsWith("http") }.distinct()

    fun javFc2Url(base: String, code: String): String = "$base/watch/${code.lowercase()}.html"

    fun javFc2Stream(root: Element): String? =
        javFc2SrcRegex.find(root.select("#player-div script").html())?.groupValues?.get(1)

    fun javFc2Subtitles(root: Element, base: String): List<Pair<String, String>> =
        root.select("track[kind=subtitles][src]").mapNotNull { track ->
            absolute(track.attr("src"), base)?.let { track.attr("label").ifBlank { "English" } to it }
        }

    fun javhdResults(root: Element, base: String): List<String> =
        root.select("#movie-last-movie > li a[href]").mapNotNull { absolute(it.attr("href"), base) }.distinct()

    /** JavHD titles are Vietnamese; only the description names the code. */
    fun javhdMatches(root: Element, code: String): Boolean {
        val text = root.selectFirst("#film-content-wrapper")?.text() ?: return false
        return codeRegex(code)?.containsMatchIn(text) == true
    }

    /** `server(4016,3)` → `4016` to `3`. */
    fun javhdServers(root: Element): List<Pair<String, String>> =
        root.select(".user-action > .server").mapNotNull { server ->
            javhdServerRegex.find(server.attr("onclick"))?.let { it.groupValues[1] to it.groupValues[2] }
        }.distinct()

    /** The site's own jwplayer hides its playlist URL in `window.atob("…")`. */
    fun javhdStream(playerHtml: String): String? =
        atobRegex.find(playerHtml)?.groupValues?.get(1)
            ?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() }
            ?.takeIf { it.startsWith("http") }

    fun iframeSrc(playerHtml: String): String? =
        Jsoup.parse(playerHtml).selectFirst("iframe[src]")?.attr("src")?.trim()?.takeIf { it.startsWith("http") }

    /** SupJav's TV server (turbovidhls) has no DNS records any more. */
    fun supjavServers(root: Element): List<String> =
        root.select(".btns a.btn-server[data-link]")
            .filterNot { it.text().trim().equals("TV", ignoreCase = true) }
            .map { it.attr("data-link") }.filter { it.isNotBlank() }.distinct()

    /** The player page's `OLID`, reversed, is the `?c=` token whose 302 points at the host embed. */
    fun supjavToken(playerHtml: String): String? =
        olidRegex.find(playerHtml)?.groupValues?.get(1)?.reversed()
}
