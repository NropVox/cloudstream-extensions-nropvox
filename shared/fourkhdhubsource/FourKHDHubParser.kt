package com.nropvox

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parsing for the server-rendered 4KHDHub pages and its link redirector. Nothing here touches the network or the
 * Cloudstream library, so every selector is covered by a JVM test against a saved page.
 */
object FourKHDHubParser {
    private val seriesUrl = Regex("""-series-\d+/?$""")
    private val year = Regex("""\b(19|20)\d{2}\b""")
    private val titleYear = Regex("""\s*\((\d{4})\)\s*$""")
    private val seasonNumber = Regex("""S(\d+)""", RegexOption.IGNORE_CASE)
    private val episodeNumber = Regex("""Episode-?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val actor = Regex("""\s*([^,(]+?)\s*\(([^)]*)\)""")
    private val youtubeEmbed = Regex("""youtube\.com/embed/([A-Za-z0-9_-]+)""")
    private val tmdbId = Regex("""defaultVideoId\s*=\s*'(\d+)'""")
    private val height = Regex("""\b(2160|1440|1080|720|576|480|360)p\b""", RegexOption.IGNORE_CASE)
    // The redirector page stores its target as `s('o','<payload>',180*1000)` before a timed JS redirect.
    private val redirectPayload = Regex("""s\('o','([A-Za-z0-9+/=]+)'""")
    private val redirectTarget = Regex(""""o"\s*:\s*"([A-Za-z0-9+/=]+)"""")
    private val whitespace = Regex("""\s+""")
    // Tag badges mix genres with release details; these and the audio languages are not genres.
    private val technicalTag = Regex(
        """^(\d{3,4}p|4K|10Bit|DV|HDR.*|DV HDR|SDR|WEB-DL|WEBRip|BluRay|REMUX|VOD|HEVC|x26[45]|Movies|Series|Multi Audios?|Dual Audio)$""",
        RegexOption.IGNORE_CASE
    )
    private val releaseTag = Regex(
        // Lookarounds, not \b: `HDR10+` ends in a non-word character.
        """(?<![A-Za-z0-9])(2160p|1080p|720p|480p|WEB-DL|WEBRip|BluRay|REMUX|HDR10\+|HDR|DoVi|DV|SDR|HEVC|x265|x264|AVC|H\.?26[45]|10bit|Atmos)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE
    )

    private fun Element.cleanText() = text().replace(' ', ' ').replace(whitespace, " ").trim()

    private fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> base.trimEnd('/') + href
        else -> "${base.trimEnd('/')}/$href"
    }

    fun isSeries(url: String): Boolean = seriesUrl.containsMatchIn(url.substringBefore('?'))

    /** Grid cards (`a.movie-card`) of the home, category and search pages. */
    fun cards(doc: Document, base: String): List<FkCard> = doc.select("a.movie-card").mapNotNull { a ->
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = a.selectFirst(".movie-card-title")?.cleanText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val url = absolute(href, base)
        val meta = a.selectFirst(".movie-card-meta")?.cleanText().orEmpty()
        val formats = a.select(".movie-card-format").map { it.cleanText() }
        FkCard(
            url = url,
            title = title,
            poster = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() },
            year = year.find(meta)?.value?.toIntOrNull(),
            series = isSeries(url),
            anime = formats.any { it.equals("Anime", ignoreCase = true) },
        )
    }

    /** Listings past the last page answer 200 with no cards and no next arrow. */
    fun hasNext(doc: Document): Boolean = doc.selectFirst("a.pagination-next") != null

    /** Movie headings end in ` (2026)`; series headings have no year. */
    fun title(doc: Document): String? =
        doc.selectFirst("h1.page-title")?.cleanText()?.replace(titleYear, "")?.takeIf { it.isNotBlank() }

    /** `Director:`, `Stars:`, `Release:`, `Last Air:`, `Audios:` … with the colon dropped. */
    fun metadata(doc: Document): Map<String, String> = doc.select(".metadata-item").mapNotNull { item ->
        val label = item.selectFirst(".metadata-label")?.cleanText()?.trimEnd(':')?.trim() ?: return@mapNotNull null
        val value = item.selectFirst(".metadata-value")?.cleanText() ?: return@mapNotNull null
        label to value
    }.toMap()

    fun year(doc: Document): Int? =
        doc.selectFirst("h1.page-title")?.cleanText()?.let { titleYear.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: metadata(doc)["Release"]?.let { year.find(it)?.value?.toIntOrNull() }

    fun plot(doc: Document): String? = doc.selectFirst(".content-main p.mt-4")?.cleanText()?.takeIf { it.isNotBlank() }

    fun poster(doc: Document): String? = doc.selectFirst(".poster-image img")?.attr("src")?.takeIf { it.isNotBlank() }

    /** `Stars: Anne Hathaway (Verity Crawford), Dakota Johnson (Lowen Ashleigh)` as (actor, role) pairs. */
    fun actors(stars: String?): List<Pair<String, String?>> {
        if (stars.isNullOrBlank()) return emptyList()
        val withRoles = actor.findAll(stars).map { it.groupValues[1].trim() to it.groupValues[2].trim().ifBlank { null } }.toList()
        return withRoles.ifEmpty { stars.split(',').map { it.trim() }.filter { it.isNotBlank() }.map { it to null } }
    }

    /** Genre badges, without quality, source, type and audio-language tags. */
    fun genres(doc: Document): List<String> {
        val languages = metadata(doc)["Audios"].orEmpty().split('|', ',').map { it.trim().lowercase() }.toSet()
        return doc.select(".badge-outline a").map { it.cleanText() }
            .filter { it.isNotBlank() && !technicalTag.matches(it) && it.lowercase() !in languages }
            .distinct()
    }

    fun isAnime(doc: Document): Boolean = doc.selectFirst(".badge-outline a[href*=/category/anime/]") != null

    /** The trailer button holds a YouTube embed URL; the app wants a watch URL. */
    fun trailer(doc: Document): String? = doc.selectFirst("#trailer-btn")?.attr("data-trailer-url")
        ?.let { youtubeEmbed.find(it)?.groupValues?.get(1) }?.let { "https://www.youtube.com/watch?v=$it" }

    /** TMDB id of a movie, from its "Watch Online" player script. Series pages have no player. */
    fun tmdbId(html: String): Int? = tmdbId.find(html)?.groupValues?.get(1)?.toIntOrNull()

    /** HubCloud first: HubDrive only links back to the same HubCloud drive. */
    private fun links(item: Element): List<String> = item.select("a[href]")
        .filter { it.text().contains("HubCloud", true) || it.text().contains("HubDrive", true) }
        .sortedBy { if (it.text().contains("HubCloud", true)) 0 else 1 }
        .map { it.attr("href") }
        .filter { it.startsWith("http") }

    /** Movie releases (`.download-item` under "Download Links"). On series pages these are season packs. */
    fun movieFiles(doc: Document): List<FkFile> = doc.select(".download-item").mapNotNull { item ->
        val urls = links(item).ifEmpty { return@mapNotNull null }
        val name = item.selectFirst(".file-title")?.cleanText()
            ?: item.selectFirst(".download-header")?.cleanText() ?: return@mapNotNull null
        FkFile(name, item.selectFirst(".download-header .badge")?.cleanText(), urls)
    }

    /**
     * Episodes of every (season × quality) block under "Download Individual Episodes". Blocks of one season may
     * list different episodes (a missing first episode, newest first), so callers merge them by number.
     */
    fun episodes(doc: Document): List<FkEpisode> = doc.select("div.season-item").flatMap { block ->
        val season = block.selectFirst(".episode-number")?.cleanText()
            ?.let { seasonNumber.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return@flatMap emptyList()
        block.select(".episode-download-item").mapNotNull { item ->
            val episode = item.selectFirst(".badge-psa")?.cleanText()
                ?.let { episodeNumber.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return@mapNotNull null
            val urls = links(item).ifEmpty { return@mapNotNull null }
            val name = item.selectFirst(".episode-file-title")?.cleanText()
                ?: block.selectFirst(".episode-title")?.cleanText() ?: return@mapNotNull null
            FkEpisode(season, episode, FkFile(name, item.selectFirst(".badge-size")?.cleanText(), urls))
        }
    }

    /**
     * Target of a redirector page (`https://greenmotors.club/?id=…`): base64, base64, ROT13 and base64 again give
     * `{"o":"<base64 url>"}`. [decode] is base64 to text.
     */
    fun redirectTarget(html: String, decode: (String) -> String): String? {
        val payload = redirectPayload.find(html)?.groupValues?.get(1) ?: return null
        val json = decode(rot13(decode(decode(payload))))
        return redirectTarget.find(json)?.groupValues?.get(1)?.let(decode)?.takeIf { it.startsWith("http") }
    }

    fun rot13(text: String): String = text.map { c ->
        when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

    /** HubDrive file page: its only download button opens the HubCloud drive of the same file. */
    fun hubdriveTarget(doc: Document): String? = doc.select("a[href]").map { it.attr("href") }
        .firstOrNull { NexdriveParser.host(it) == NexdriveParser.Host.HUBCLOUD }

    /** Pixel height named in a file name (`2160p`), or 0. */
    fun quality(name: String): Int = height.find(name)?.groupValues?.get(1)?.toIntOrNull()
        ?: if (Regex("""\b4K\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) 2160 else 0

    /** Release tags of a file name for the link label: `2160p HDR10+ DV WEB-DL HEVC x265`. */
    fun tags(name: String): String =
        releaseTag.findAll(name).map { it.value }.distinctBy { it.lowercase() }.joinToString(" ")
}
