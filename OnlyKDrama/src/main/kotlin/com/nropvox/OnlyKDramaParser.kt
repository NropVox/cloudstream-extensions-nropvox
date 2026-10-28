package com.nropvox

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parsing for the OnlyKDrama (WordPress + DooPlay) pages and the file-host pages its posts link to. Nothing here
 * touches the network or the Cloudstream library, so every selector is covered by a JVM test against a saved page.
 */
object OnlyKDramaParser {
    /** File host of a post link. Hosts rotate subdomains and TLDs (`new11.xcloud.asia`, `hubcloud.one`), so labels are matched. */
    enum class Host { HUBCLOUD, XCLOUD, GDFLIX, DIRECT, PIXELDRAIN, OTHER }

    private val seasonEpisode = Regex("""S(\d{1,2})[ .]?E(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val episodeOnly = Regex("""(?<![A-Za-z0-9])(?:Episode|EP|E)[ .]?(\d{1,4})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val seasonOnly = Regex("""(?<![A-Za-z0-9])S\d{1,2}(?![0-9])""", RegexOption.IGNORE_CASE)
    private val episodeHeading = Regex("""^Episode\s*(\d{1,4})$""", RegexOption.IGNORE_CASE)
    private val size = Regex("""[\[(]\s*(\d+(?:\.\d+)?\s*[KMGT]i?B)\s*[\])]""", RegexOption.IGNORE_CASE)
    private val height = Regex("""(?<![0-9])(2160|1440|1080|720|576|540|480|360)p""", RegexOption.IGNORE_CASE)
    private val titleYear = Regex("""\((\d{4})\)""")
    private val year = Regex("""\b(19|20)\d{2}\b""")
    private val pageOf = Regex("""Page\s+(\d+)\s+of\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val tmdbSize = Regex("""/t/p/(?:w\d+|original)/""")
    private val youtubeEmbed = Regex("""youtube\.com/embed/([A-Za-z0-9_-]+)""")
    private val siteTag = Regex("""-?\s*\[?OnlyKDrama\.top\]?""", RegexOption.IGNORE_CASE)
    private val dotsBetweenWords = Regex("""(?<=[A-Za-z0-9])\.(?=[A-Za-z])""")
    private val whitespace = Regex("""\s+""")
    // Genre links that describe the upload, not the show.
    private val fileTags = setOf("zip file", "av1 file", "10bit file", "2160p", "index links", "stream online",
        "full hd drama", "bluray remux", "hardcoded subtitle", "old drama gdrive link", "upcoming", "ongoing", "completed")

    private fun Element.cleanText() = text().replace(' ', ' ').replace(whitespace, " ").trim()

    private fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + href
        else -> "${base.trimEnd('/')}/$href"
    }

    fun host(url: String): Host {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        val path = url.substringAfter("://").substringAfter('/', "")
        return when {
            host.startsWith("hubcloud.") -> Host.HUBCLOUD
            host.startsWith("xcloud.") || ".xcloud." in host -> Host.XCLOUD
            // `/pack/` links are whole seasons.
            host.startsWith("gdflix.") || ".gdflix." in host -> if (path.startsWith("file/")) Host.GDFLIX else Host.OTHER
            host.endsWith(".workers.dev") && path.startsWith("download.aspx") -> Host.DIRECT
            host.startsWith("pixeldrain.") -> Host.PIXELDRAIN
            else -> Host.OTHER
        }
    }

    fun isMovie(url: String): Boolean = "/movies/" in url.substringAfter("://")

    /** TMDB poster at another width: cards ship `w92`/`w185`, too small for the app. */
    fun resize(poster: String?, width: String): String? = poster?.replace(tmdbSize, "/t/p/$width/")

    /** `article.item` of archive pages and home modules; the sidebar's `article.w_item_b` is skipped. */
    fun cards(doc: Document, base: String): List<OkCard> {
        val archive = doc.select("article.item").mapNotNull { article ->
            val link = article.selectFirst(".data h3 a[href]") ?: article.selectFirst(".poster a[href]") ?: return@mapNotNull null
            val title = article.selectFirst(".data h3")?.cleanText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = absolute(link.attr("href"), base)
            OkCard(
                url = url,
                title = title,
                poster = resize(article.selectFirst(".poster img")?.attr("src")?.takeIf { it.isNotBlank() }, "w342"),
                year = titleYear.find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: article.selectFirst(".data span")?.cleanText()?.let { year.find(it)?.value?.toIntOrNull() },
                movie = article.hasClass("movies") || isMovie(url),
            )
        }
        val search = doc.select("div.result-item").mapNotNull { item ->
            val link = item.selectFirst(".title a[href]") ?: return@mapNotNull null
            val title = link.cleanText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = absolute(link.attr("href"), base)
            OkCard(
                url = url,
                title = title,
                poster = resize(item.selectFirst(".thumbnail img")?.attr("src")?.takeIf { it.isNotBlank() }, "w342"),
                year = item.selectFirst(".meta .year")?.cleanText()?.toIntOrNull() ?: titleYear.find(title)?.groupValues?.get(1)?.toIntOrNull(),
                movie = item.selectFirst(".thumbnail span.movies") != null || isMovie(url),
            )
        }
        return (archive + search).distinctBy { it.url }
    }

    /** `div.pagination` opens with `Page 2 of 57`; pages past the last answer 404. */
    fun hasNext(doc: Document): Boolean {
        val match = doc.selectFirst("div.pagination span")?.cleanText()?.let { pageOf.find(it) } ?: return false
        return match.groupValues[1].toInt() < match.groupValues[2].toInt()
    }

    fun title(doc: Document): String? = doc.selectFirst(".sheader .data h1")?.cleanText()?.takeIf { it.isNotBlank() }

    fun poster(doc: Document): String? =
        resize(doc.selectFirst(".sheader .poster img")?.attr("src")?.takeIf { it.isNotBlank() }, "w500")

    fun year(doc: Document): Int? = title(doc)?.let { titleYear.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: doc.selectFirst(".sheader .extra span.date")?.cleanText()?.let { year.find(it)?.value?.toIntOrNull() }

    /** Synopsis paragraphs open the post body; the first block after them is the download section. */
    fun plot(doc: Document): String? {
        val body = doc.selectFirst("div[itemprop=description]") ?: return null
        return body.children().takeWhile { it.tagName() == "p" }.map { it.cleanText() }.filter { it.isNotBlank() }
            .joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    fun genres(doc: Document): List<String> =
        doc.select(".sgeneros a").map { it.cleanText() }.filter { it.isNotBlank() && it.lowercase() !in fileTags }

    /** `Ongoing` / `Completed` are genre links; null when neither is set. */
    fun ongoing(doc: Document): Boolean? {
        val all = doc.select(".sgeneros a").map { it.cleanText().lowercase() }
        return when {
            "ongoing" in all -> true
            "completed" in all -> false
            else -> null
        }
    }

    fun rating(doc: Document): Double? = doc.selectFirst(".dt_rating_vgs")?.cleanText()?.toDoubleOrNull()?.takeIf { it > 0 }

    fun trailer(doc: Document): String? = doc.selectFirst("#trailer iframe[src]")?.attr("src")
        ?.let { youtubeEmbed.find(it)?.groupValues?.get(1) }?.let { "https://www.youtube.com/watch?v=$it" }

    fun tmdbId(doc: Document): Int? = doc.selectFirst("div.svs[data-tmdb]")?.attr("data-tmdb")?.toIntOrNull()

    /**
     * Every resolvable link of the post body, in page order. Episode numbers come from the file name (`S01E05`,
     * `E05`), else from the `Episode N` accordion heading older posts group links under. Season packs (`S01` with no
     * episode, `COMPLETE`, `/pack/`) and links to captcha hosts are dropped.
     */
    fun entries(doc: Document): List<OkEntry> {
        val body = doc.selectFirst("div[itemprop=description]") ?: doc.selectFirst("div.wp-content") ?: return emptyList()
        var heading: Int? = null
        val entries = mutableListOf<OkEntry>()
        for (element in body.select("a[href], p, h1, h2, h3, h4, h5, h6, strong")) {
            if (element.tagName() != "a") {
                episodeHeading.find(element.ownText().trim())?.let { heading = it.groupValues[1].toInt() }
                continue
            }
            val url = element.attr("href").trim()
            if (host(url) == Host.OTHER) continue
            val text = element.cleanText()
            val name = text.replace(size, "").trim().trimEnd('–', '-').trim()
            val file = OkFile(name = name, size = size.find(text)?.groupValues?.get(1), url = url)
            val se = seasonEpisode.find(name)
            val entry = when {
                se != null -> OkEntry(se.groupValues[1].toInt(), se.groupValues[2].toInt(), file)
                seasonOnly.containsMatchIn(name) || name.contains("COMPLETE", ignoreCase = true) -> OkEntry(null, null, file)
                else -> episodeOnly.find(name)?.let { OkEntry(1, it.groupValues[1].toInt(), file) }
                    ?: OkEntry(heading?.let { 1 }, heading, file)
            }
            entries += entry
        }
        return entries
    }

    fun quality(name: String): Int = height.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** `100.Days.My.Prince.S01E01.1080p.AMZN.WEB-DL.Hindi.DDP2.0-…-[OnlyKDrama.top].mkv` → `1080p AMZN WEB-DL Hindi DDP2.0-…`. */
    fun release(name: String): String {
        val clean = name.replace(siteTag, "").removeSuffix(".mkv").removeSuffix(".mp4").trim().trimEnd('.', '-', ' ')
        val tail = seasonEpisode.find(clean)?.let { clean.substring(it.range.last + 1) }
            ?: height.find(clean)?.let { clean.substring(it.range.first) }
            ?: clean
        return tail.replace(dotsBetweenWords, " ").replace(whitespace, " ").trim().trim('.', '-', ' ').ifBlank { clean }
    }

    /** GDFlix file page: only its PixelDrain button works without login or captcha. */
    fun gdflixPixeldrain(doc: Document): String? =
        doc.select("a[href]").map { it.attr("href") }.firstOrNull { host(it) == Host.PIXELDRAIN }

    /** XCloud landing page: "Generate Direct Download Link" opens the server list on a Cloudflare-challenged host. */
    fun xcloudServers(doc: Document): String? = doc.select("a[href]").firstOrNull {
        it.cleanText().contains("Generate Direct Download", ignoreCase = true) && it.attr("href").startsWith("http")
    }?.attr("href")

    /**
     * XCloud server list: `Download [Cloud]` and `Download [20GBPS]` (`/apis/cloud/redirect/…`, `/apis/cloud2/…`) 302 to presigned R2
     * files. The others go to GoFile or to hosts that were not resolved.
     */
    fun xcloudMirrors(doc: Document, base: String): List<Pair<String, String>> = doc.select("a[href]").mapNotNull { a ->
        val text = a.cleanText()
        val href = a.attr("href")
        if (!text.startsWith("Download [") || "/apis/cloud" !in href) return@mapNotNull null
        text.substringAfter('[').substringBefore(']').trim() to absolute(href, base)
    }
}
