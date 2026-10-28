package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Pure parsing for k-drama.in (KatDrama): server-rendered PHP pages whose data is TMDB, plus the site's own stream
 * resolvers (`2.php`, `vid2.php`, `yoy5.php`). Nothing here touches the network or the Cloudstream library.
 */
object KatDramaParser {
    data class Card(val url: String, val title: String, val poster: String?, val isMovie: Boolean, val year: Int?)

    data class CastMember(val name: String, val role: String?, val image: String?)

    data class Episode(val season: Int, val number: Int, val name: String?, val still: String?, val date: String?)

    data class Details(
        val title: String,
        val plot: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val rating: String?,
        val genres: List<String>,
        val cast: List<CastMember>,
        val seasons: List<Int>,
        val episodes: List<Episode>,
    )

    /** One file plus its subtitles, and the title the resolver matched (servers 2 and 7 look titles up by name). */
    data class Resolved(val title: String?, val year: Int?, val urls: List<String>, val subtitles: List<Pair<String, String>>)

    data class Source(val url: String, val provider: String, val quality: String?)

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // `/detail/tv/queen-of-tears-215720`, `/watch/movie/hope-1058424`, legacy `/detail/arcane-94605`
    private val pageId = Regex("""/(?:detail|watch)/(?:(tv|movie)/)?[^?#]*?-(\d+)(?:-episode-\d+)?/?(?:[?#].*)?$""")
    private val onclickUrl = Regex("""location\.href='([^']+)'""")
    private val episodeNumber = Regex("""-episode-(\d+)""")
    private val seasonParam = Regex("""[?&]season=(\d+)""")
    private val year = Regex("""\b(19|20)\d{2}\b""")
    private val backdrop = Regex("""https://image\.tmdb\.org/t/p/w1280/[A-Za-z0-9_-]+\.jpg""")
    private val titleYear = Regex("""\((\d{4})\)""")
    private val titleEpisode = Regex("""\s+-\s+Episode\s+\d+\s*$""", RegexOption.IGNORE_CASE)
    private val urlYear = Regex("""-(\d{4})-episode-\d+""")
    // One-line JS array literals; `]` and `}` stay escaped for Android's ICU regex engine.
    private val sourcesArray = Regex("""const SOURCES\s*=\s*(\[.*?\]);\s*\n""", RegexOption.DOT_MATCHES_ALL)
    private val subtitlesArray = Regex("""const SUBTITLES\s*=\s*(\[.*?\]);\s*\n""", RegexOption.DOT_MATCHES_ALL)
    // Castle: `/hls_mps/<id>/720/index_266.m3u8`; its `quality` label says 1080p for the same file.
    private val pathHeight = Regex("""/(\d{3,4})/index[^/]*\.m3u8""")
    private val mirror = Regex("""https?://[^"'\s<>]+/(?:e|v)/[A-Za-z0-9]+""")

    /** `(type, tmdbId)` from any detail or watch URL; the slug before the id is decorative. */
    fun idOf(url: String): Pair<String, Int>? {
        val match = pageId.find(url) ?: return null
        val id = match.groupValues[2].toIntOrNull() ?: return null
        return match.groupValues[1].ifEmpty { "tv" } to id
    }

    /** The site's own slug rule (search modal JS). */
    fun slugify(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun detailUrl(base: String, type: String, id: Int, title: String): String =
        "$base/detail/$type/${slugify(title).ifEmpty { type }}-$id"

    /** Explore grid cards (`div.cineapse-movie-card`, 20 per page). */
    fun cards(doc: Document, base: String): List<Card> =
        doc.select("div.cineapse-movie-card[onclick]").mapNotNull { card ->
            val path = onclickUrl.find(card.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            val img = card.selectFirst("img")
            val title = img?.attr("alt")?.ifBlank { null }
                ?: card.selectFirst(".cineapse-hover-title")?.text()?.ifBlank { null }
                ?: return@mapNotNull null
            val cardYear = card.select(".cineapse-hover-meta span").firstNotNullOfOrNull { span ->
                span.text().trim().takeIf { it.length == 4 }?.toIntOrNull()
            }
            Card(absolute(path, base), title, img?.attr("src")?.ifBlank { null }, path.startsWith("/detail/movie/"), cardYear)
        }.distinctBy { it.url }

    /** Pagination links name the next page explicitly; the page after the last has no cards and no link. */
    fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[href^=/explore?]").any { it.attr("href").contains(Regex("""[?&]page=${page + 1}(?:&|$)""")) }

    fun details(doc: Document, season: Int): Details? {
        val ld = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { mapper.readTree(script.data()) }.getOrNull()?.let(::findMedia)
        }
        val h1 = doc.selectFirst("h1")
        val title = ld?.text("name") ?: h1?.text()?.ifBlank { null } ?: return null
        // The pill row before the title: [kind, ★ rating, year, genres…].
        val pills = h1?.previousElementSibling()?.select("> span")?.map { it.text().trim() }.orEmpty()
        val genres = pills.drop(1).filter { it.isNotEmpty() && year.matchEntire(it) == null && !it.first().isDigit() }
        val cast = doc.select("h3:containsOwn(Cast & Characters)").firstOrNull()?.nextElementSibling()
            ?.children()?.mapNotNull { member ->
                val name = member.selectFirst("h5")?.text()?.ifBlank { null } ?: return@mapNotNull null
                CastMember(name, member.selectFirst("span")?.text()?.ifBlank { null }, member.selectFirst("img")?.attr("src")?.ifBlank { null })
            }.orEmpty()
        val seasons = doc.select("a.cineapse-pill[href*=season=]").mapNotNull {
            seasonParam.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()
        val episodes = doc.select("div[onclick*=/watch/tv/]").mapNotNull { card ->
            val number = episodeNumber.find(card.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            Episode(
                season = season,
                number = number,
                name = card.selectFirst("h4")?.text()?.ifBlank { null },
                still = card.selectFirst("img")?.attr("src")?.ifBlank { null },
                date = card.select("span").map { it.text().trim() }.firstOrNull { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) },
            )
        }.distinctBy { it.number }
        return Details(
            title = title,
            plot = ld?.text("description") ?: h1?.nextElementSibling()?.takeIf { it.tagName() == "p" }?.text(),
            poster = ld?.text("image") ?: doc.selectFirst("meta[property=og:image]")?.attr("content"),
            backdrop = backdrop.find(doc.html())?.value,
            year = ld?.text("datePublished")?.take(4)?.toIntOrNull() ?: pills.firstNotNullOfOrNull { year.matchEntire(it)?.value?.toIntOrNull() },
            rating = ld?.get("aggregateRating")?.text("ratingValue"),
            genres = genres,
            cast = cast,
            seasons = seasons,
            episodes = episodes,
        )
    }

    private fun findMedia(node: JsonNode): JsonNode? {
        if (node.isObject && node.path("@type").asText() in setOf("TVSeries", "Movie")) return node
        val children = if (node.isArray) node.elements() else if (node.isObject) node.elements() else return null
        for (child in children) findMedia(child)?.let { return it }
        return null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { !it.isNull }?.asText()?.ifBlank { null }

    /**
     * Server 2 (`2.php`): an iframe to `s1.devcorp.me/player/player.html` whose query carries JSON `file` and
     * `subtitle` arrays and a `title` like `Queen of Tears (2024) - Episode 1`.
     */
    fun server2(doc: Document): Resolved? {
        val src = doc.select("iframe[src*=player.html]").firstOrNull()?.attr("src") ?: return null
        val query = src.substringAfter('?', "").split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").ifEmpty { return@mapNotNull null }
            key to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }.toMap()
        val urls = query["file"]?.let { json -> readArray(json).mapNotNull { it.text("file") } }.orEmpty()
        if (urls.isEmpty()) return null
        val subtitles = query["subtitle"]?.let { json ->
            readArray(json).mapNotNull { sub -> sub.text("file")?.let { (sub.text("name") ?: "Unknown") to it } }
        }.orEmpty()
        val title = query["title"]
        return Resolved(
            title = title?.replace(titleEpisode, "")?.replace(titleYear, "")?.trim(),
            year = title?.let { titleYear.find(it)?.groupValues?.get(1)?.toIntOrNull() },
            urls = urls.distinct(),
            subtitles = subtitles.distinctBy { it.second },
        )
    }

    /**
     * Server 6 (`vid2.php`): inline `SOURCES` / `SUBTITLES` arrays. The same URL is repeated under several quality and
     * language labels, so sources are unique by URL. Subtitles go through a vidsync proxy whose `data` names the raw file.
     */
    fun server6(html: String): Pair<List<Source>, List<Pair<String, String>>> {
        val sources = sourcesArray.find(html)?.groupValues?.get(1)?.let(::readArray).orEmpty().mapNotNull { node ->
            val url = node.text("url") ?: node.text("file") ?: return@mapNotNull null
            if (node.text("type")?.equals("hls", true) == false && !url.contains(".m3u8")) return@mapNotNull null
            val height = pathHeight.find(url)?.groupValues?.get(1)?.let { "${it}p" }
            Source(url, node.text("providerName") ?: node.text("provider") ?: "Server", height ?: node.text("quality"))
        }.distinctBy { it.url }
        val subtitles = subtitlesArray.find(html)?.groupValues?.get(1)?.let(::readArray).orEmpty().mapNotNull { node ->
            val url = node.text("url") ?: node.text("file") ?: return@mapNotNull null
            val label = node.text("label")?.removePrefix("KissKH - ") ?: node.text("language") ?: "Unknown"
            label to unproxy(url)
        }.distinctBy { it.second }
        return sources to subtitles
    }

    /** `vidsync.pro/api/core/proxy?data={"url":…,"headers":…}`: the raw file answers without the headers. */
    fun unproxy(url: String): String {
        if (!url.contains("/proxy?data=")) return url
        val data = URLDecoder.decode(url.substringAfter("data=").substringBefore('&'), "UTF-8")
        return runCatching { mapper.readTree(data).text("url") }.getOrNull() ?: url
    }

    /** Server 7 (`yoy5.php?…&ajax=1`): `{success, title, dramaUrl, servers:[{name, src}]}`; `src` is a vidbasic page. */
    fun server7(json: String): Resolved? {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!root.path("success").asBoolean(false)) return null
        val urls = root.path("servers").mapNotNull { it.text("src") }
        if (urls.isEmpty()) return null
        val year = root.text("dramaUrl")?.let { urlYear.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return Resolved(root.text("title"), year, urls.distinct(), emptyList())
    }

    /** vidbasic embed pages list mirror embeds (dood, hglink, mixdrop, streamtape, …). */
    fun mirrors(html: String): List<String> =
        mirror.findAll(html).map { it.value }.filterNot { it.contains("vidbasic") }.distinct().toList()

    /**
     * Servers 2 and 7 look shows up by name and sometimes return another show (Arcane → "EXO Arcade 2"). Accept a
     * match when the normalised titles are equal or one contains the other and covers most of it, and the years (when
     * both are known) are at most one apart.
     */
    fun titleMatches(expected: String, expectedYear: Int?, got: String?, gotYear: Int?): Boolean {
        if (got.isNullOrBlank()) return false
        if (expectedYear != null && gotYear != null && kotlin.math.abs(expectedYear - gotYear) > 1) return false
        val a = normalise(expected)
        val b = normalise(got)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return long.contains(short) && short.length * 10 >= long.length * 7
    }

    private fun normalise(title: String) =
        title.replace(titleYear, "").lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

    private fun readArray(json: String): List<JsonNode> =
        runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isArray }?.toList().orEmpty()

    private fun absolute(path: String, base: String) = if (path.startsWith("http")) path else base + path
}
