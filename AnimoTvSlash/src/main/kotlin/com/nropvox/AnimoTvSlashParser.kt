package com.nropvox

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Pure helpers, kept apart from [AnimoTvSlash] so they can be unit tested without the app. */
object AnimoTvSlashParser {
    /** One entry of the episode page's `select.mirror`: `Sub - ANIMO-P`, `SoftSub - Moon`, `Dub - VidHide`... */
    data class ServerOption(val lang: String, val server: String, val src: String)

    private val optionTag = Regex("""<option\s+value="([A-Za-z0-9+/=]{16,})"[^>]*>\s*([^<]+?)\s*</option>""")
    private val iframeSrc = Regex("""<iframe[^>]*?\ssrc="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val mp4Height = Regex("""-(\d{3,4})p\.mp4""", RegexOption.IGNORE_CASE)
    private val aapEps = Regex("""window\.AAP_EPS\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val optionLabel = Regex("""^(\S+?)\s*-\s*(.+)$""")
    private val watchEmbedUrl = Regex("""animotvslash\.ru/watch/(\d+)/(\d+)\?lang=(sub|dub)""")
    private val seriesPath = Regex("""^/anime/[^/]+/?$""")
    private val digits = Regex("""\d+""")
    private val year = Regex("""\d{4}""")

    /** Catalog page: `/anime/` archives are paged as `/anime/page/N/?query`, 48 cards each. */
    fun listUrl(mainUrl: String, query: String, page: Int): String {
        val base = if (page > 1) "$mainUrl/anime/page/$page/" else "$mainUrl/anime/"
        return if (query.isEmpty()) base else "$base?$query"
    }

    /** `?page=N` is ignored by the site, so search is paged through the path too. */
    fun searchUrl(mainUrl: String, encodedQuery: String, page: Int): String =
        if (page > 1) "$mainUrl/page/$page/?s=$encodedQuery" else "$mainUrl/?s=$encodedQuery"

    /** True for `/anime/<slug>/`; false for episode pages and `/anime/?status=...` archives. */
    fun isSeriesUrl(url: String): Boolean {
        val path = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('#').substringBefore('?')
        return seriesPath.matches(path)
    }

    /** Hrefs scraped from HTML carry `&#038;` / `&amp;`. */
    fun unescape(s: String): String = s.replace("&#038;", "&").replace("&amp;", "&")

    /** Every server of an episode page, in page order. Options whose value is not an iframe are dropped. */
    fun serverOptions(html: String): List<ServerOption> = optionTag.findAll(html).mapNotNull { m ->
        // Labels are `Sub - ANIMO-P` on newer episodes but `Sub -HD0` on older ones.
        val label = optionLabel.find(m.groupValues[2]) ?: return@mapNotNull null
        val lang = label.groupValues[1].trim()
        val server = label.groupValues[2].trim()
        val snippet = decodeBase64(m.groupValues[1]) ?: return@mapNotNull null
        val src = iframeSrc.find(snippet)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
        ServerOption(lang, server, src)
    }.toList()

    /** AniList id, episode and language of an `animotvslash.ru/watch/<anilistId>/<episode>?lang=sub|dub` embed (the `HD0` server). */
    data class WatchEmbed(val anilistId: String, val episode: String, val lang: String)

    fun watchEmbed(src: String): WatchEmbed? =
        watchEmbedUrl.find(unescape(src))?.let { WatchEmbed(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    /** `720p` to 720. */
    fun qualityHeight(label: String?): Int? = label?.let { digits.find(it)?.value?.toIntOrNull() }

    /** The JSON inside a first-party player URL: `player.animotv.ru/#<b64>` or `/jw-player/<b64>`. */
    fun playerPayload(src: String): String? {
        val token = when {
            "#" in src -> src.substringAfterLast('#')
            "-player/" in src -> src.substringAfterLast("-player/")
            else -> return null
        }.substringBefore('?')
        return decodeBase64(token)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64(token: String): String? {
        val clean = token.trim().trimEnd('=')
        if (clean.isEmpty()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val codec = if ('-' in clean || '_' in clean) Base64.UrlSafe else Base64.Default
        return runCatching { String(codec.decode(padded), Charsets.UTF_8) }.getOrNull()
    }

    /** Height of a direct MP4: `...-480p.mp4`; the `...-mp4-source.mp4` upload is the 1080p original. Null when unknown. */
    fun mp4Height(url: String): Int? {
        mp4Height.find(url)?.let { return it.groupValues[1].toInt() }
        return if (url.substringBefore('?').endsWith("source.mp4", ignoreCase = true)) 1080 else null
    }

    /** Raw JSON object of `window.AAP_EPS`, keyed by episode number. */
    fun aapEpisodesJson(html: String): String? = aapEps.find(html)?.groupValues?.get(1)

    /** `.epl-num` text to an episode number; fractional numbers (`12.5`) keep their integer part. */
    fun episodeNumber(text: String): Int? = digits.find(text)?.value?.toIntOrNull()

    fun yearOf(text: String): Int? = year.find(text)?.value?.toIntOrNull()
}
