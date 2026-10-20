package com.nropvox

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class GoPlay : MainAPI() {
    override var mainUrl = "https://goplay.su"
    override var name = "GoPlay"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    private companion object {
        const val MAX_LINK_REFS = 200
    }

    // Title links start with a hex-encoded provider ID, e.g. /562d3132343433/Descendants-of-the-Sun/
    private val titlePath = Regex("""^/[0-9a-fA-F]+/[^/?#]+/""")
    private val episodeNumber = Regex("""/Episode-(\d+)(?:\.\d+)?/""")

    override val mainPage = mainPageOf(
        "" to "Home",
        "browse?type=1" to "Series",
        "browse?type=2" to "Movies",
        "browse?type=3" to "Variety",
        "browse?region=1" to "Korean",
        "browse?region=2" to "Chinese",
        "browse?region=3" to "Japanese",
        "browse?region=4" to "Thai",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isEmpty()) {
            val document = GoPlaySession.get("$mainUrl/").document
            // Each homepage section is an #indextitle followed by its #indexepisodelist.
            val lists = document.select("#indexepisodelist").mapNotNull { list ->
                val title = list.previousElementSiblings()
                    .firstOrNull { it.id() == "indextitle" }?.ownText()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val items = list.select("#indexepisodes").mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
                if (items.isEmpty()) null else HomePageList(title, items)
            }
            return newHomePageResponse(lists, hasNext = false)
        }

        val document = GoPlaySession.get("$mainUrl/${request.data}&page=$page").document
        val items = document.select(".browse_entry").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = document.lastPage() > page
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = GoPlaySession.get("$mainUrl/?search=$encoded").document
        val items = document.select(".indexsearchepisodes").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        // GoPlay returns every match on one page.
        return newSearchResponseList(items, hasNext = false)
    }

    /** Browse (.browse_entry), homepage (#indexepisodes) and search (.indexsearchepisodes) cards. */
    private fun Element.toSearchResult(): SearchResponse? {
        val href = select("a[href]").firstNotNullOfOrNull { titleLink(it.attr("href").trim()) }
            ?: return null
        val titleElement = selectFirst(".browse_entry_title, #indexepisodetitle, #indexsearchepisodetitle")
            ?: return null
        val title = titleElement.ownText().trim().ifEmpty { titleElement.text().trim() }
        if (title.isEmpty()) return null

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            posterUrl = fixUrlNull(this@toSearchResult.selectFirst("img")?.attr("src"))
        }
    }

    /** Some homepage rows link relatively without a leading slash. */
    private fun titleLink(href: String): String? {
        val path = when {
            href.startsWith(mainUrl) -> href.removePrefix(mainUrl)
            href.startsWith("/") -> href
            href.contains("://") || href.startsWith("?") || href.startsWith("#") -> return null
            else -> "/$href"
        }
        return if (titlePath.containsMatchIn(path)) mainUrl + path else null
    }

    private fun Document.lastPage(): Int =
        select("#search_page_select option").mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull() ?: 1

    override suspend fun load(url: String): LoadResponse? {
        val document = GoPlaySession.get(url).document
        val info = document.selectFirst("#infotitle") ?: return null
        val title = stripOriginalTitle(info.ownText().trim()).ifEmpty { return null }
        val plot = document.selectFirst("#desctext")?.text()?.trim()

        val actors = document.select(".artists_entry_main").mapNotNull { entry ->
            val name = entry.selectFirst(".artists_entry_name a")?.attr("title")?.trim()
                ?.ifEmpty { null } ?: return@mapNotNull null
            ActorData(
                Actor(name, fixUrlNull(entry.selectFirst("img.artists_img")?.attr("src"))),
                roleString = entry.selectFirst(".artists_entry_character")?.text()?.trim()?.ifEmpty { null }
            )
        }

        val episodeLinks = document.select("#episodes a[href]")
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(episodeLinks.firstOrNull()?.selectFirst("img")?.attr("src"))

        // Movies show "Feature Film" instead of an episode count and link only to themselves.
        val isMovie = document.selectFirst("#episodecount")?.text()?.trim()?.toIntOrNull() == null
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.actors = actors
            }
        }

        val episodes = episodeLinks.mapNotNull { link ->
            val href = link.attr("href")
            val number = episodeNumber.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(fixUrl(href)) {
                episode = number
                name = "Episode $number"
                posterUrl = fixUrlNull(link.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            posterUrl = poster
            this.plot = plot
            this.actors = actors
        }
    }

    /** "Title (원제)" → "Title"; original titles may contain parentheses themselves. */
    private fun stripOriginalTitle(label: String): String {
        if (!label.endsWith(")")) return label
        var depth = 0
        for (i in label.indices.reversed()) {
            when (label[i]) {
                ')' -> depth++
                '(' -> if (--depth == 0) {
                    return if (i > 0 && label[i - 1].isWhitespace()) label.substring(0, i).trim() else label
                }
            }
        }
        return label
    }

    private class ResolvedSource(
        val file: String,
        val type: ExtractorLinkType,
        val label: String?,
        val headers: Map<String, String>,
        val clearKey: GoPlayPlayer.ClearKey?,
    )

    private class Resolved(
        val server: String?,
        val sources: List<ResolvedSource>,
        val tracks: List<GoPlayPlayer.PlayerTrack>,
    )

    /** Fetches the page and decrypts the player; every call yields fresh single-use media URLs. */
    private suspend fun resolve(data: String): Resolved? {
        val response = GoPlaySession.get(data)
        val config = GoPlayPlayer.extract(response.text) { scriptUrl ->
            try {
                app.get(
                    scriptUrl,
                    headers = mapOf("User-Agent" to GoPlaySession.currentUserAgent),
                    referer = "$mainUrl/"
                ).takeIf { it.isSuccessful }?.text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "Could not load $scriptUrl: ${e.message}")
                null
            }
        } ?: return null.also { Log.w(name, "No player configuration on $data") }
        val server = response.document.selectFirst("#sourceid_placeholder")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

        val mediaHeaders = mapOf("User-Agent" to GoPlaySession.currentUserAgent)
        val sources = mutableListOf<ResolvedSource>()
        val tracks = mutableListOf<GoPlayPlayer.PlayerTrack>()
        for (item in config.playlist?.takeIf { it.isNotEmpty() } ?: listOf(config)) {
            val itemSources = item.sources?.takeIf { it.isNotEmpty() }
                ?: listOf(GoPlayPlayer.PlayerSource(item.file, item.type, item.label, item.headers, item.drm))
            for (source in itemSources) {
                val file = source.file?.let { fixUrl(it) } ?: continue
                val type = linkType(file, source.type) ?: continue
                sources.add(
                    ResolvedSource(
                        file, type, source.label?.ifBlank { null },
                        mediaHeaders + config.headers.orEmpty() + item.headers.orEmpty() + source.headers.orEmpty(),
                        (source.drm ?: item.drm ?: config.drm)?.clearkey
                    )
                )
            }
            item.tracks.orEmpty()
                .filter { it.kind == null || it.kind == "captions" || it.kind == "subtitles" }
                .filter { it.file != null }
                .let(tracks::addAll)
        }
        return Resolved(server, sources, tracks)
    }

    /** A single-use media URL handed to the player, and how to get a fresh one for it. */
    private class LinkRef(val data: String, val pick: (Resolved) -> String?) {
        val used = AtomicBoolean(false)
    }

    private val linkRefs = ConcurrentHashMap<String, LinkRef>()

    private fun register(file: String, data: String, pick: (Resolved) -> String?) {
        // Keyed as OkHttp renders the request URL so the interceptor can look it up verbatim.
        if (linkRefs.size > MAX_LINK_REFS) linkRefs.clear()
        linkRefs[file.toHttpUrlOrNull()?.toString() ?: file] = LinkRef(data, pick)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolved = resolve(data) ?: return false
        val sourceName = listOfNotNull(name, resolved.server).joinToString(" ")

        // Manifest and subtitle URLs are single-use: hand them straight to the player, never prefetch.
        resolved.sources.forEachIndexed { index, source ->
            val linkName = listOfNotNull(sourceName, source.label).joinToString(" - ")
            val clearKey = source.clearKey

            // The source must equal the API name for getVideoInterceptor to be consulted.
            val link = if (clearKey?.key != null && clearKey.keyId != null) {
                newDrmExtractorLink(name, linkName, source.file, source.type, CLEARKEY_UUID) {
                    kid = jwkKey(clearKey.keyId)
                    key = jwkKey(clearKey.key)
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                    this.headers = source.headers
                }
            } else {
                newExtractorLink(name, linkName, source.file, source.type) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                    this.headers = source.headers
                }
            }
            register(source.file, data) { it.sources.getOrNull(index)?.file }
            callback(link)
        }

        val mediaHeaders = mapOf("User-Agent" to GoPlaySession.currentUserAgent)
        resolved.tracks.forEachIndexed { index, track ->
            val file = fixUrl(track.file!!)
            register(file, data) { it.tracks.getOrNull(index)?.file?.let(::fixUrl) }
            subtitleCallback(
                newSubtitleFile(track.label ?: track.language ?: "Unknown", file) {
                    this.headers = mediaHeaders
                }
            )
        }
        return resolved.sources.isNotEmpty()
    }

    /**
     * CloudStream caches the links from [loadLinks], so replaying an episode reuses media and
     * subtitle URLs the server already invalidated. The first request for a URL passes through
     * untouched; any later request for the same URL is pointed at a freshly resolved one.
     */
    private val refreshInterceptor = Interceptor { chain ->
        val request = chain.request()
        val ref = linkRefs[request.url.toString()]
        if (ref == null || !ref.used.getAndSet(true)) return@Interceptor chain.proceed(request)

        Log.i(name, "Refreshing spent link ${request.url.encodedPath}")
        val fresh = try {
            runBlocking { resolve(ref.data) }?.let(ref.pick)?.toHttpUrlOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "Link refresh failed: ${e.message}")
            null
        }
        chain.proceed(if (fresh == null) request else request.newBuilder().url(fresh).build())
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = refreshInterceptor

    private fun linkType(url: String, type: String?): ExtractorLinkType? =
        when (type?.lowercase()) {
            "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> ExtractorLinkType.M3U8
            "dash", "mpd", "application/dash+xml" -> ExtractorLinkType.DASH
            "mp4", "video/mp4" -> ExtractorLinkType.VIDEO
            else -> {
                val path = url.substringBefore("?").lowercase()
                when {
                    path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
                    path.endsWith(".mpd") -> ExtractorLinkType.DASH
                    path.endsWith(".mp4") -> ExtractorLinkType.VIDEO
                    else -> null
                }
            }
        }

    /** GoPlay ships ClearKey values as hex (optionally dashed); the player expects unpadded base64url. */
    private fun jwkKey(value: String): String {
        val hex = value.replace("-", "")
        val bytes = if (hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            Base64.decode(value.replace('+', '-').replace('/', '_'), Base64.URL_SAFE)
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
