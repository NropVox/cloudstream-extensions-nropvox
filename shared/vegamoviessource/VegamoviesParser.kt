package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parsing for the WordPress pages and host interstitials of Vegamovies. Nothing here touches the network or the
 * Cloudstream library, so every selector is covered by a JVM test against a saved page.
 */
object VegamoviesParser {
    enum class Kind { GDIRECT, VCLOUD, EPISODES, BATCH, OTHER }

    private val mapper = ObjectMapper()

    private val leadingNoise = Regex("""^\s*Download\s+""", RegexOption.IGNORE_CASE)
    private val leadingFlag = Regex("""^\[[^\]]*\]\s*""")
    // The title ends where the SEO tail starts: a season, the year, a language brace, or a quality/source token.
    private val titleTail = Regex(
        """\s*[(\[{]\s*(Season|S\d|\d{4}|Hindi|Dual|Multi|English|Korean|Chinese|Tamil|Telugu|ORG)""" +
            """|\s+(Season|S\d{2})\b|\s+(\d{3,4}p|WEB-DL|BluRay|Blu-Ray|HDRip|HDTC|HDCAM|Dual Audio|Hindi|Multi)""",
        RegexOption.IGNORE_CASE
    )
    private val yearInParens = Regex("""\((\d{4})\)""")
    private val seriesTitle = Regex("""\bSeason\b|[{\[]S\d|\bS\d{2}E\d|\bSeries\b|\bEpisode""", RegexOption.IGNORE_CASE)
    private val seasonPart = Regex("""\bSeason\s*(\d+)(?:\s*[–-]\s*Part\s*(\d+))?""", RegexOption.IGNORE_CASE)
    private val seasonShort = Regex("""\bS(\d{2})\b""")
    private val height = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
    private val episodeHeading = Regex("""Episodes?:\s*(\d+)""", RegexOption.IGNORE_CASE)
    // `-:Episodes: 1 To 07 | [7.5GB]:-` heads a zip of those episodes, not episode 1.
    private val episodeRange = Regex("""Episodes?:\s*\d+\s*(?:To|-|–|&)\s*\d+""", RegexOption.IGNORE_CASE)
    private val language = Regex("""\{([^}]+)\}""")
    private val technical = Regex(
        """\b(2160p|1080p|720p|480p|4K|UHD|HEVC|x265|x264|10bit|BluRay|Blu-Ray|WEB-DL|HDR|60FPS)\b""",
        RegexOption.IGNORE_CASE
    )
    private val size = Regex("""\[[^\]]*\d\s*(?:MB|GB)[^\]]*\]""", RegexOption.IGNORE_CASE)
    private val imdb = Regex("""imdb\.com/title/(tt\d+)""")

    fun cleanTitle(raw: String): String {
        var title = raw.replace(leadingNoise, "").replace(leadingFlag, "")
        titleTail.find(title)?.let { title = title.substring(0, it.range.first) }
        return title.replace(Regex("""\s+"""), " ").trim(' ', '-', '–', ':')
    }

    fun year(raw: String): Int? = yearInParens.find(raw)?.groupValues?.get(1)?.toIntOrNull()

    fun isSeries(raw: String): Boolean = seriesTitle.containsMatchIn(raw)

    /** Pixel height named in [text] (`720p`, `4K`), or 0. */
    fun quality(text: String): Int = height.find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: if (text.contains("4K", ignoreCase = true)) 2160 else 0

    /** `Season 4 - Part 02` becomes (4, 2); a season with no part is (n, 0). */
    fun part(heading: String): Pair<Int, Int>? {
        seasonPart.find(heading)?.let { return it.groupValues[1].toInt() to (it.groupValues[2].toIntOrNull() ?: 0) }
        return seasonShort.find(heading)?.let { it.groupValues[1].toInt() to 0 }
    }

    /** Audio language, codec, source and size from a quality heading, for the link name. */
    fun tags(heading: String): String = listOfNotNull(
        language.find(heading)?.value,
        technical.findAll(heading).map { it.value }.distinctBy { it.lowercase() }.joinToString(" ").ifBlank { null },
        size.find(heading)?.value,
    ).joinToString(" ")

    fun kind(label: String): Kind = when {
        label.contains("batch", true) || label.contains("zip", true) -> Kind.BATCH
        label.contains("g-direct", true) -> Kind.GDIRECT
        label.contains("v-cloud", true) -> Kind.VCLOUD
        label.contains("episode", true) -> Kind.EPISODES
        else -> Kind.OTHER
    }

    private fun Element.cleanText() = text().replace("⚡", "").replace(Regex("""\s+"""), " ").trim()

    /** Grid cards of the home and category pages (`#moviesGridMain`). */
    fun cards(doc: Document): List<VgCard> = doc.select("#moviesGridMain > a").mapNotNull { a ->
        val url = a.attr("abs:href").ifBlank { a.attr("href") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = a.selectFirst(".poster-title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val img = a.selectFirst("img")
        // Category pages lazy-load: src is a data: placeholder and the real poster is in data-src.
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val rating = a.selectFirst(".imdb-score")?.text()?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        VgCard(url, title, poster, a.selectFirst(".poster-quality")?.text()?.trim(), rating)
    }

    /** Quality headings of a detail page, each with its nexdrive buttons, in page order. */
    fun groups(doc: Document): List<VgGroup> {
        val body = doc.selectFirst("main.page-body") ?: doc
        val groups = mutableListOf<VgGroup>()
        var heading: String? = null
        var buttons = mutableListOf<VgButton>()
        fun flush() {
            heading?.let { if (buttons.isNotEmpty()) groups += VgGroup(it, buttons) }
            buttons = mutableListOf()
        }
        for (el in body.select("h3, h4, h5, a:has(button)")) {
            if (el.tagName() != "a") {
                flush()
                heading = el.cleanText()
                continue
            }
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            if (heading != null && href.startsWith("http")) buttons += VgButton(el.cleanText(), href)
        }
        flush()
        return groups
    }

    /**
     * Host links of a nexdrive page, tagged with the episode heading (`-:Episode: 12:-`) they sit under. Both the
     * buttons and the bare "Alternative Sources" links count; links under an episode-range zip heading are dropped.
     */
    fun entries(doc: Document): List<VgEntry> {
        val root = doc.selectFirst(".entry-inner") ?: doc
        var episode: Int? = null
        var zip = false
        val out = mutableListOf<VgEntry>()
        for (el in root.select("h4, a[href]")) {
            if (el.tagName() == "h4") {
                val text = el.text()
                episodeHeading.find(text)?.let {
                    episode = it.groupValues[1].toInt()
                    zip = episodeRange.containsMatchIn(text)
                }
                continue
            }
            val href = el.attr("abs:href").ifBlank { el.attr("href") }
            if (!zip && href.startsWith("http")) out += VgEntry(episode, el.cleanText(), href)
        }
        return out
    }

    /** The `Movie` node of the page's JSON-LD: TMDB poster URL and aggregate rating. */
    fun movieLd(doc: Document): JsonNode? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { mapper.readTree(script.data()) }.getOrNull() ?: continue
            val nodes = root.get("@graph")?.toList() ?: if (root.isArray) root.toList() else listOf(root)
            nodes.firstOrNull { it.get("@type")?.asText() == "Movie" }?.let { return it }
        }
        return null
    }

    fun rating(ld: JsonNode?): Double? = ld?.get("aggregateRating")?.get("ratingValue")?.asText()?.toDoubleOrNull()

    fun imdbId(doc: Document): String? = imdb.find(doc.select("main.page-body a[href*=imdb.com]").outerHtml())?.groupValues?.get(1)

    /** The paragraph after the `…SYNOPSIS/PLOT:` heading. */
    fun plot(doc: Document): String? {
        val head = doc.select("main.page-body h2, main.page-body h3").firstOrNull { it.text().contains("SYNOPSIS", true) } ?: return null
        var next = head.nextElementSibling()
        while (next != null && !next.tagName().startsWith("h")) {
            if (next.tagName() == "p" && next.text().isNotBlank()) return next.text().trim()
            next = next.nextElementSibling()
        }
        return null
    }

    fun genres(doc: Document): List<String> =
        doc.select(".post-categories a[href*=/movies-by-genres/]").map { it.text().trim() }.filter { it.isNotBlank() }

    fun isAnime(doc: Document): Boolean = doc.select(".post-categories a").any {
        it.attr("href").contains("anime-series") || it.text().equals("Anime Series", true)
    }
}
