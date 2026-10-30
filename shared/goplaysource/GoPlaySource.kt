package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A browse, homepage or search card. */
data class GoPlayCard(val title: String, val url: String, val poster: String?)

/** A title page: its English name, the original name GoPlay puts in parentheses, and episode page URLs by number. */
data class GoPlayTitle(
    val url: String,
    val title: String,
    val originalTitle: String?,
    val isMovie: Boolean,
    val episodes: Map<Int, String>,
)

/**
 * GoPlay's pages behind its Turnstile session ([GoPlaySession]), its encrypted player ([GoPlayPlayer]) and the
 * single-use media URLs that player hands out. Shared by the GoPlay and OmniStream plugins.
 */
object GoPlaySource {
    const val SITE = "https://goplay.su"
    private const val TAG = "GoPlay"
    private const val MAX_LINK_REFS = 200

    // Title links start with a hex-encoded provider ID, e.g. /562d3132343433/Descendants-of-the-Sun/
    private val titlePath = Regex("""^/[0-9a-fA-F]+/[^/?#]+/""")
    val episodeNumber = Regex("""/Episode-(\d+)(?:\.\d+)?/""")

    /** GoPlay returns every match on one page. */
    suspend fun search(query: String): List<GoPlayCard> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return cards(GoPlaySession.get("$SITE/?search=$encoded").document.select(".indexsearchepisodes"))
    }

    /** Browse (.browse_entry), homepage (#indexepisodes) and search (.indexsearchepisodes) cards. */
    fun cards(elements: List<Element>): List<GoPlayCard> = elements.mapNotNull { element ->
        val href = element.select("a[href]").firstNotNullOfOrNull { titleLink(it.attr("href").trim()) }
            ?: return@mapNotNull null
        val titleElement = element.selectFirst(".browse_entry_title, #indexepisodetitle, #indexsearchepisodetitle")
            ?: return@mapNotNull null
        val title = titleElement.ownText().trim().ifEmpty { titleElement.text().trim() }
        if (title.isEmpty()) return@mapNotNull null
        GoPlayCard(title, href, element.selectFirst("img")?.attr("src")?.let(::fixUrl))
    }.distinctBy { it.url }

    /** Some homepage rows link relatively without a leading slash. */
    private fun titleLink(href: String): String? {
        val path = when {
            href.startsWith(SITE) -> href.removePrefix(SITE)
            href.startsWith("/") -> href
            href.contains("://") || href.startsWith("?") || href.startsWith("#") -> return null
            else -> "/$href"
        }
        return if (titlePath.containsMatchIn(path)) SITE + path else null
    }

    suspend fun title(url: String): GoPlayTitle? = title(url, GoPlaySession.get(url).document)

    fun title(url: String, document: Document): GoPlayTitle? {
        val label = document.selectFirst("#infotitle")?.ownText()?.trim() ?: return null
        val title = stripOriginalTitle(label).ifEmpty { return null }
        val original = label.removePrefix(title).trim().removeSurrounding("(", ")").trim().ifEmpty { null }
        // Movies show "Feature Film" instead of an episode count and link only to themselves.
        val isMovie = document.selectFirst("#episodecount")?.text()?.trim()?.toIntOrNull() == null
        val episodes = document.select("#episodes a[href]").mapNotNull { link ->
            val href = link.attr("href")
            val number = episodeNumber.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            number to fixUrl(href)
        }.distinctBy { it.first }.toMap()
        return GoPlayTitle(url, title, original, isMovie, episodes)
    }

    /** "Title (원제)" → "Title"; original titles may contain parentheses themselves. */
    fun stripOriginalTitle(label: String): String {
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

    fun fixUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> SITE + url
        else -> "$SITE/$url"
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
                    referer = "$SITE/"
                ).takeIf { it.isSuccessful }?.text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not load $scriptUrl: ${e.message}")
                null
            }
        } ?: return null.also { Log.w(TAG, "No player configuration on $data") }
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

    /**
     * Emits the streams of an episode or movie page ([data]). [source] must be the calling API's name, because the
     * app asks only that API for a video interceptor, and [interceptor] is what renews spent URLs. [label] starts
     * each link name.
     */
    suspend fun loadLinks(
        data: String,
        source: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolved = resolve(data) ?: return false
        val sourceName = listOfNotNull(label, resolved.server).joinToString(" ")

        // Manifest and subtitle URLs are single-use: hand them straight to the player, never prefetch.
        resolved.sources.forEachIndexed { index, resolvedSource ->
            val linkName = listOfNotNull(sourceName, resolvedSource.label).joinToString(" - ")
            val clearKey = resolvedSource.clearKey

            val link = if (clearKey?.key != null && clearKey.keyId != null) {
                newDrmExtractorLink(source, linkName, resolvedSource.file, resolvedSource.type, CLEARKEY_UUID) {
                    kid = jwkKey(clearKey.keyId)
                    key = jwkKey(clearKey.key)
                    referer = "$SITE/"
                    quality = Qualities.Unknown.value
                    this.headers = resolvedSource.headers
                }
            } else {
                newExtractorLink(source, linkName, resolvedSource.file, resolvedSource.type) {
                    referer = "$SITE/"
                    quality = Qualities.Unknown.value
                    this.headers = resolvedSource.headers
                }
            }
            register(resolvedSource.file, data) { it.sources.getOrNull(index)?.file }
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
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val ref = linkRefs[request.url.toString()]
        if (ref == null || !ref.used.getAndSet(true)) return@Interceptor chain.proceed(request)

        Log.i(TAG, "Refreshing spent link ${request.url.encodedPath}")
        val fresh = try {
            runBlocking { resolve(ref.data) }?.let(ref.pick)?.toHttpUrlOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Link refresh failed: ${e.message}")
            null
        }
        chain.proceed(if (fresh == null) request else request.newBuilder().url(fresh).build())
    }

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
    @OptIn(ExperimentalEncodingApi::class)
    fun jwkKey(value: String): String {
        val hex = value.replace("-", "")
        val bytes = if (hex.length == 32 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(value.replace('+', '-').replace('/', '_'))
        }
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    }
}
