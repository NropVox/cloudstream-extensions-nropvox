package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections

/**
 * animotvslash.org is a WordPress site on the AnimeStream theme: no JSON catalog API, so catalogs, search,
 * details and episodes are parsed from HTML. Every episode page carries all servers as base64 `<option>`s
 * (`Sub - ...`, `SoftSub - ...`, `Dub - ...`). The first-party ones (ANIMO-P/H/M/D) hold plain HLS/MP4 URLs
 * on open CDNs, so they play without any extractor; the rest are third-party embeds handed to `loadExtractor`.
 * Sub/Dub is therefore chosen per link, not per episode list.
 */
class AnimoTvSlash : MainAPI() {
    override var mainUrl = "https://animotvslash.org"
    override var name = "AnimoTvSlash"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "order=update" to "Latest Update",
        "status=Ongoing&order=update" to "Ongoing",
        "order=popular" to "Most Viewed",
        "order=rating" to "Top Rated",
        "type=Movie&order=update" to "Movies",
        "sub=Dub&order=update" to "Dubbed",
        "status=Completed&order=update" to "Completed",
        "status=Upcoming&order=update" to "Upcoming",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(AnimoTvSlashParser.listUrl(mainUrl, request.data, page), headers = browserHeaders).document
        return newHomePageResponse(request.name, doc.toSearchResponses(), doc.hasNextPage())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val res = app.get(AnimoTvSlashParser.searchUrl(mainUrl, q, page), headers = browserHeaders)
        // A page past the last one is a 404 rather than an empty list.
        if (res.code == 404) return newSearchResponseList(emptyList(), false)
        val doc = res.document
        return newSearchResponseList(doc.toSearchResponses(), doc.hasNextPage())
    }

    override suspend fun load(url: String): LoadResponse {
        var seriesUrl = url
        var doc = app.get(url, headers = browserHeaders).document
        if (!AnimoTvSlashParser.isSeriesUrl(url)) {
            // Episode pages (from links outside the catalog) name their series in the breadcrumb.
            seriesUrl = doc.select("a[href*=/anime/]").map { it.absUrl("href") }
                .firstOrNull { AnimoTvSlashParser.isSeriesUrl(it) }
                ?: throw ErrorLoadingException("No series link on $url")
            doc = app.get(seriesUrl, headers = browserHeaders).document
        }

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Not a series page: $seriesUrl")
        val info = doc.select(".info-content .spe > span").associate { span ->
            span.selectFirst("b")?.text()?.trim()?.removeSuffix(":").orEmpty() to span.text().substringAfter(':').trim()
        }
        val type = when (info["Type"]) {
            "Movie" -> TvType.AnimeMovie
            "OVA", "ONA", "Special", "Music" -> TvType.OVA
            else -> TvType.Anime
        }
        val poster = doc.selectFirst("div.thumb img")?.absUrl("src")?.ifBlank { null }
        val plot = (doc.selectFirst(".ts-syn-full") ?: doc.selectFirst("div[itemprop=description]"))
            ?.text()?.trim()?.ifBlank { null }
        val score = doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()
            ?.takeIf { it > 0 }?.let { Score.from10(it) }
        val stills = AnimoTvSlashParser.aapEpisodesJson(doc.html())
            ?.let { tryParseJson<Map<String, AapEpisode>>(it) }.orEmpty()

        val episodes = doc.select("#ts-ep-list li a").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            val number = a.selectFirst(".epl-num")?.text()?.let { AnimoTvSlashParser.episodeNumber(it) }
            val still = number?.let { stills[it.toString()] }
            newEpisode(href) {
                name = still?.title?.ifBlank { null }
                episode = number
                posterUrl = still?.image?.ifBlank { null }
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        if (type == TvType.AnimeMovie && episodes.isNotEmpty()) {
            return newMovieLoadResponse(title, seriesUrl, type, episodes.first().data) {
                this.posterUrl = poster
                this.plot = plot
                this.score = score
                this.tags = doc.select(".genxed a").map { it.text().trim() }
                this.year = info["Released"]?.let { AnimoTvSlashParser.yearOf(it) }
            }
        }
        return newAnimeLoadResponse(title, seriesUrl, type) {
            this.posterUrl = poster
            this.backgroundPosterUrl = doc.selectFirst("div.bigcover img")?.absUrl("src")?.ifBlank { null }
            this.plot = plot
            this.score = score
            this.tags = doc.select(".genxed a").map { it.text().trim() }
            this.year = info["Released"]?.let { AnimoTvSlashParser.yearOf(it) }
            this.japName = doc.selectFirst("span.alter")?.text()?.trim()?.ifBlank { null }
            this.showStatus = when (info["Status"]?.lowercase()) {
                "completed" -> ShowStatus.Completed
                "ongoing" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, headers = browserHeaders).text
        val options = AnimoTvSlashParser.serverOptions(page)
        if (options.isEmpty()) return false

        // The same video is offered by several servers; emit each media URL once.
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val subtitles = Collections.synchronizedSet(HashSet<String>())
        var found = false

        for (option in options) {
            val label = "$name ${option.lang} ${option.server}"
            val payload = AnimoTvSlashParser.playerPayload(option.src)
            when {
                "player.animotv.ru" in option.src -> {
                    val p = payload?.let { tryParseJson<AnimoPayload>(it) } ?: continue
                    p.master?.let { if (emitHls(label, it, emitted, callback)) found = true }
                    p.alt.orEmpty().filter { it.endsWith(".mp4", true) }.forEach { mp4 ->
                        if (emitMp4("$label MP4", mp4, AnimoTvSlashParser.mp4Height(mp4), emitted, callback)) found = true
                    }
                    p.subs.orEmpty().forEach { s ->
                        val url = s.url ?: return@forEach
                        if (subtitles.add(url)) subtitleCallback(newSubtitleFile(s.label ?: s.lang ?: "Subtitles", url))
                    }
                }
                "/vidstack-player/" in option.src -> {
                    val p = payload?.let { tryParseJson<WrapperPayload>(it) } ?: continue
                    listOf(p.url480 to 480, p.url720 to 720, p.url1080 to 1080).forEach { (url, height) ->
                        if (!url.isNullOrBlank() && emitMp4(label, url, height, emitted, callback)) found = true
                    }
                }
                "-player/" in option.src -> {
                    val url = payload?.let { tryParseJson<WrapperPayload>(it) }?.url?.ifBlank { null } ?: continue
                    if (emitHls(label, url, emitted, callback)) found = true
                }
            }
        }

        // Older episodes only have AniList-keyed servers (`HD0`..`HD4`); `HD0` is the site's own player,
        // whose API hands back ready-to-play streams from several providers.
        coroutineScope {
            options.mapNotNull { option -> AnimoTvSlashParser.watchEmbed(option.src)?.let { option to it } }
                .flatMap { (option, embed) -> STREAM_SERVERS.map { server -> Triple(option, embed, server) } }
                .map { (option, embed, server) ->
                    async {
                        withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                            runCatching { emitStreamServer(option.lang, embed, server, emitted, subtitles, subtitleCallback, callback) }
                                .onSuccess { if (it) found = true }
                                .onFailure { Log.w(name, "Stream $server ${embed.anilistId}/${embed.episode}: $it") }
                        }
                    }
                }.awaitAll()
        }

        // Third-party embeds: slow and sometimes unsupported, so run them in parallel with a deadline.
        coroutineScope {
            options.filterNot { it.isFirstParty() || it.isHandledElsewhere() }.map { option ->
                async {
                    val links = Collections.synchronizedList(ArrayList<ExtractorLink>())
                    withTimeoutOrNull(EMBED_TIMEOUT_MS) {
                        runCatching { loadExtractor(option.src, "$mainUrl/", subtitleCallback) { links.add(it) } }
                            .onFailure { Log.w(name, "Embed ${option.server} (${option.src}): $it") }
                    }
                    // Extractor names don't say sub or dub, so rebuild each link with the page's language.
                    links.toList().forEach { link ->
                        if (!emitted.add(link.url)) return@forEach
                        found = true
                        callback(newExtractorLink(link.source, "${option.lang} ${link.name}", link.url, link.type) {
                            quality = link.quality
                            referer = link.referer
                            headers = link.headers
                            extractorData = link.extractorData
                        })
                    }
                }
            }.awaitAll()
        }
        return found
    }

    private fun AnimoTvSlashParser.ServerOption.isFirstParty(): Boolean =
        "player.animotv.ru" in src || "-player/" in src

    /** The `HD0` API already resolves these; the other `HD` embeds are encrypted or SPA pages no extractor reads. */
    private fun AnimoTvSlashParser.ServerOption.isHandledElsewhere(): Boolean =
        UNSUPPORTED_HOSTS.any { it in src }

    /** One provider of `animotvslash.ru/api/stream/sources/<server>`; false when it has nothing for this episode. */
    private suspend fun emitStreamServer(
        lang: String,
        embed: AnimoTvSlashParser.WatchEmbed,
        server: String,
        emitted: MutableSet<String>,
        subtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "$STREAM_API/sources/$server?anilistId=${embed.anilistId}&episode=${embed.episode}&lang=${embed.lang}"
        val res = app.get(url, headers = browserHeaders + mapOf("Referer" to STREAM_REFERER)).text
        val source = tryParseJson<StreamSources>(res) ?: return false
        val label = "$name $lang ${source.server ?: server}"
        var emittedAny = false
        if (source.type == "mp4") {
            val files = source.qualities.orEmpty().mapNotNull { q -> q.url?.let { it to AnimoTvSlashParser.qualityHeight(q.quality) } }
                .ifEmpty { listOfNotNull(source.url?.let { it to null }) }
            for ((file, height) in files) {
                if (emitStream("$label${height?.let { " ${it}p" }.orEmpty()}", file, ExtractorLinkType.VIDEO, height, emitted, callback)) emittedAny = true
            }
        } else {
            val file = source.url ?: return false
            emittedAny = emitStream(label, file, ExtractorLinkType.M3U8, null, emitted, callback)
        }
        source.subtitles.orEmpty().forEach { s ->
            val subUrl = s.url ?: return@forEach
            if (subtitles.add(subUrl)) {
                subtitleCallback(newSubtitleFile(s.label ?: "Subtitles", subUrl) { headers = mapOf("Referer" to STREAM_REFERER) })
            }
        }
        return emittedAny
    }

    private suspend fun emitStream(label: String, url: String, type: ExtractorLinkType, height: Int?, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        if (!emitted.add(url)) return false
        // The master carries separate audio renditions, so it is handed to the player whole rather than split by resolution.
        callback(newExtractorLink(name, label, url, type) {
            quality = height ?: Qualities.Unknown.value
            referer = STREAM_REFERER
        })
        return true
    }

    /** Splits an HLS master into one link per rendition; a master that can't be read is offered as-is. */
    private suspend fun emitHls(label: String, url: String, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        if (!emitted.add(url)) return false
        val links = runCatching { generateM3u8(source = label, streamUrl = url, referer = mediaReferer(url)) }
            .onFailure { Log.w(name, "HLS $url: $it") }
            .getOrNull().orEmpty()
        if (links.isEmpty()) {
            callback(newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value
                referer = mediaReferer(url)
            })
        } else {
            links.forEach(callback)
        }
        return true
    }

    private suspend fun emitMp4(label: String, url: String, height: Int?, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        if (!emitted.add(url)) return false
        callback(newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
            quality = height ?: Qualities.Unknown.value
            referer = mediaReferer(url)
        })
        return true
    }

    /** cdn.videas.fr answers 403 to `Referer: animotvslash.org` but plays for its own player's origin or no referer. */
    private fun mediaReferer(url: String): String = if ("videas.fr" in url) PLAYER_REFERER else ""

    private fun Document.toSearchResponses(): List<SearchResponse> =
        select("article.bs").mapNotNull { it.toSearchResponse() }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { return null }
        val title = link.attr("title").ifBlank { selectFirst(".tt h2")?.text().orEmpty() }.trim().ifBlank { return null }
        val type = when (selectFirst("div.typez")?.text()?.trim()) {
            "Movie" -> TvType.AnimeMovie
            "OVA", "ONA", "Special", "Music" -> TvType.OVA
            else -> TvType.Anime
        }
        // Pill = "<sub episodes> / <dub episodes> / <total>"; "?" when the total is unknown.
        val counts = select(".aw-pill > span").map { it.text().trim().toIntOrNull() }
        val subs = counts.getOrNull(0) ?: 0
        val dubs = counts.getOrNull(1) ?: 0
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = selectFirst("img")?.absUrl("src")?.ifBlank { null }
            addDubStatus(dubExist = dubs > 0, subExist = subs > 0, dubEpisodes = dubs.takeIf { it > 0 }, subEpisodes = subs.takeIf { it > 0 })
        }
    }

    private fun Document.hasNextPage(): Boolean = selectFirst("a.next.page-numbers") != null

    companion object {
        private const val EMBED_TIMEOUT_MS = 20_000L
        private const val STREAM_TIMEOUT_MS = 25_000L
        private const val PLAYER_REFERER = "https://player.animotv.ru/"

        /** `stream.animotvslash.ru` and the API both answer 403 unless the Referer is the player's own site. */
        private const val STREAM_REFERER = "https://animotvslash.ru/"
        private const val STREAM_API = "https://animotvslash.ru/api/stream"

        /** Providers that returned plain HLS/MP4 in recon; the others (kaa, xanime, mmani) failed. */
        private val STREAM_SERVERS = listOf("koto", "animo-ss", "miku", "aniwaves", "beep")
        private val UNSUPPORTED_HOSTS = listOf("animotvslash.ru/watch", "tryembed.us.cc", "vidnest.fun", "megaplay.buzz")
    }
}
