package com.nropvox

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * streamcorner.st is a React SPA whose schedules come from Cloudflare Workers (`data.*.workers.dev/corner?p=…`)
 * behind an encrypted request/reply that only the site's own worker speaks, so [StreamCornerBridge] runs that worker
 * in a WebView. Amazon embeds carry ClearKey configuration; other embeds are framed to capture their playlist.
 */
class StreamCorner internal constructor(private val bridgeFactory: (String) -> StreamCornerBridge) : MainAPI() {
    override var mainUrl = "https://streamcorner.st"
    override var name = "StreamCorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    private val bridge by lazy { bridgeFactory(mainUrl) }
    private val drm by lazy { StreamCornerDrmResolver(bridge, mainUrl, ::fetchPlaylist) }
    private val bootstrapLock = Mutex()
    @Volatile private var bootstrap: Pair<SCBootstrap, Long>? = null
    private val lists = ConcurrentHashMap<String, Pair<List<SCItem>, Long>>()
    private val details = ConcurrentHashMap<String, Pair<SCItem, Long>>()

    override val mainPage = mainPageOf(*StreamCornerParser.providers.map { it.key to it.row }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val provider = StreamCornerParser.provider(request.data) ?: throw ErrorLoadingException("Unknown row ${request.data}")
        val items = if (page > 1) emptyList() else visible(provider).map { it.toSearchResponse(provider.key) }
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        // The site has no search; match the schedules it already shows.
        val hits = coroutineScope {
            StreamCornerParser.providers.map { provider ->
                async {
                    val items = try {
                        visible(provider)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(name, "search ${provider.key}: ${e.message}")
                        emptyList()
                    }
                    items.filter { it.matches(q) }.map { it.toSearchResponse(provider.key) }
                }
            }.awaitAll().flatten()
        }
        return newSearchResponseList(hits, hasNext = false)
    }

    private fun SCItem.matches(q: String): Boolean =
        listOfNotNull(StreamCornerParser.title(this), league, category, homeTeam, awayTeam).any { it.contains(q, ignoreCase = true) }

    private fun SCItem.toSearchResponse(provider: String): SearchResponse =
        newLiveSearchResponse(StreamCornerParser.title(this)!!, StreamCornerParser.pageUrl(mainUrl, provider, StreamCornerParser.id(this)!!), TvType.Live) {
            posterUrl = StreamCornerParser.poster(this@toSearchResponse)
        }

    override suspend fun load(url: String): LoadResponse {
        val (key, id) = StreamCornerParser.pageRef(url) ?: throw ErrorLoadingException("Not a streamcorner.st stream URL: $url")
        val provider = StreamCornerParser.provider(key) ?: throw ErrorLoadingException("Unsupported streamcorner.st section: $key")
        val item = detail(provider, id)
        val pageUrl = StreamCornerParser.pageUrl(mainUrl, key, id)
        val title = StreamCornerParser.title(item) ?: throw ErrorLoadingException("$id is no longer listed on streamcorner.st")
        val now = System.currentTimeMillis()
        val sources = StreamCornerParser.sources(item)
        return newLiveStreamLoadResponse(title, pageUrl, pageUrl) {
            posterUrl = StreamCornerParser.poster(item)
            backgroundPosterUrl = posterUrl
            plot = listOfNotNull(
                StreamCornerParser.plot(item, now).ifEmpty { null },
                if (sources.isEmpty()) "No streams yet; the site adds them shortly before the start." else null,
            ).joinToString("\n\n")
            tags = listOfNotNull(provider.row, item.league?.ifBlank { null }, item.category?.ifBlank { null }).distinct()
            recommendations = runCatching { visible(provider) }.getOrDefault(emptyList())
                .filter { StreamCornerParser.id(it) != id }.take(24).map { it.toSearchResponse(key) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (providerKey, id) = StreamCornerParser.pageRef(data) ?: return false
        val provider = StreamCornerParser.provider(providerKey) ?: return false
        val sources = StreamCornerParser.sources(detail(provider, id, refresh = true))
        if (sources.isEmpty()) return false

        // Each embed is a WebView; a few at once keeps the phone responsive and the player's wait bounded.
        val permits = Semaphore(EMBED_PARALLEL)
        val found = coroutineScope {
            sources.map { (label, embed) ->
                async {
                    permits.withPermit {
                        try {
                            val decoded = if (drm.supports(embed)) try {
                                drm.resolve(embed)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(name, "$label decoder failed: ${e.javaClass.simpleName}")
                                null
                            } else null
                            val manifest = if (decoded != null || StreamCornerParser.isManifest(embed)) null else bridge.manifest(embed, EMBED_TIMEOUT_MS)
                            val url = decoded?.url ?: manifest?.url ?: embed.takeIf { StreamCornerParser.isManifest(it) }
                            if (url == null) {
                                Log.w(name, "no playlist from $label $embed")
                                return@withPermit false
                            }
                            val seen = manifest?.headers.orEmpty()
                            val embedOrigin = URI(embed).let { "${it.scheme}://${it.authority}" }
                            val referer = if (decoded != null) "$embedOrigin/" else
                                seen.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
                            val headers = listOfNotNull(
                                if (decoded != null) "Origin" to embedOrigin else
                                    seen.entries.firstOrNull { it.key.equals("Origin", true) }?.let { "Origin" to it.value },
                                manifest?.userAgent?.let { "User-Agent" to it },
                            ).toMap()
                            val requestHeaders = headers + listOfNotNull(referer.ifEmpty { null }?.let { "Referer" to it })
                            val key = decoded?.keys?.singleOrNull()
                            if (decoded != null && decoded.keys.size > 1) {
                                Log.w(name, "$label requires multiple DRM keys, unsupported by this app")
                                return@withPermit false
                            }
                            val link = if (key != null) {
                                val playable = drmDash(url, requestHeaders, key.kid)
                                newDrmExtractorLink(name, "$name $label", playable.url, ExtractorLinkType.DASH, CLEARKEY_UUID) {
                                    kid = jwk(key.kid)
                                    this.key = jwk(key.key)
                                    this.referer = referer
                                    this.headers = headers
                                    extractorData = playable.manifest
                                    quality = StreamCornerParser.quality(label) ?: playable.quality
                                }
                            } else {
                                if (isDrm(url, requestHeaders)) {
                                    Log.w(name, "skipping unresolved DRM: $label")
                                    return@withPermit false
                                }
                                newExtractorLink(name, "$name $label", url,
                                    if (StreamCornerParser.isDash(url)) ExtractorLinkType.DASH else ExtractorLinkType.M3U8) {
                                    this.referer = referer
                                    this.headers = headers
                                    StreamCornerParser.quality(label)?.let { quality = it }
                                }
                            }
                            Log.i(name, "$label: ${link.type}, ClearKey=${key != null}")
                            callback(link)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(name, "$label: ${e.message}")
                            false
                        }
                    }
                }
            }.awaitAll()
        }
        return found.any { it }
    }

    private fun jwk(hex: String): String = Base64.encodeToString(
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    /** Cloudstream's DRM branch uses DASH even for an HLS link. Repackage the observed NBA VOD layout. */
    private data class DrmPlayback(val url: String, val quality: Int = 0, val manifest: String? = null)

    private suspend fun drmDash(url: String, headers: Map<String, String>, kid: String): DrmPlayback = withContext(Dispatchers.IO) {
        if (StreamCornerParser.isDash(url)) return@withContext DrmPlayback(url)
        val uri = URI(url)
        require(uri.host == "lpnba.akamaized.net" && uri.path.startsWith("/vod-pz/p/hls-wvpr/")) {
            "DRM HLS format is not supported by this app"
        }
        val selection = StreamCornerDash.select(fetchPlaylist(url, headers), url) ?: error("Unsupported NBA master playlist")
        val video = StreamCornerDash.media(fetchPlaylist(selection.videoUrl, headers), selection.videoUrl, kid)
            ?: error("Unsupported NBA video playlist")
        val audio = StreamCornerDash.media(fetchPlaylist(selection.audioUrl, headers), selection.audioUrl, kid)
            ?: error("Unsupported NBA audio playlist")
        fun scale(track: StreamCornerDash.Track): Int = track.initializations.map {
            StreamCornerDash.timescale(fetchBytes(it, headers)) ?: error("Invalid media initialization")
        }.distinct().singleOrNull() ?: error("Track timescale changed")
        val videoScale = scale(video)
        val audioScale = scale(audio)
        val mpd = StreamCornerDash.mpd(selection, video, audio, kid, videoScale, audioScale) ?: error("NBA tracks do not align")
        val synthetic = url.substringBefore('?').removeSuffix(".m3u8") + ".cs3.mpd" +
            uri.rawQuery?.let { "?$it" }.orEmpty()
        DrmPlayback(synthetic, selection.height, mpd)
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val mpd = extractorLink.extractorData?.takeIf { it.startsWith("<?xml") } ?: return null
        val url = extractorLink.url.toHttpUrl()
        return Interceptor { chain ->
            val request = chain.request()
            if (request.url != url) chain.proceed(request) else Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(mpd.toResponseBody("application/dash+xml".toMediaType())).build()
        }
    }

    /**
     * Whether [url] is a Widevine/PlayReady HLS feed (e.g. NBA League Pass `hls-wvpr`), which the player can't open.
     * Keys sit in the master's session keys or only in the variants, so a master without them costs one more fetch.
     * An unreachable playlist counts as playable: the player gets to try it.
     */
    private suspend fun isDrm(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val master = fetchPlaylist(url, headers)
            StreamCornerParser.isDrm(master) ||
                StreamCornerParser.firstVariant(master, url)?.let { StreamCornerParser.isDrm(fetchPlaylist(it, headers)) } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(name, "DRM check failed for $url: ${e.message}")
            false
        }
    }

    private fun fetchPlaylist(url: String, headers: Map<String, String>): String {
        return fetchBytes(url, headers).toString(Charsets.UTF_8)
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return probeClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            res.body.bytes()
        }
    }

    /** NiceHttp's timeout doesn't bound the 30 s connect, so playlist checks use their own short-timeout client. */
    private val probeClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** A provider's schedule as the site would show it: ended events dropped, except NBA games with replays. */
    private suspend fun visible(provider: StreamCornerParser.Provider): List<SCItem> =
        StreamCornerParser.sorted(list(provider), System.currentTimeMillis(), keepEnded = provider.key == "nba")
            .filter { StreamCornerParser.title(it) != null }

    private suspend fun list(provider: StreamCornerParser.Provider): List<SCItem> {
        lists[provider.key]?.takeIf { fresh(it.second, LIST_TTL_MS) }?.let { return it.first }
        val boot = bootstrap()
        val reply = bridge.call(boot.workerPath, StreamCornerParser.message("provider-list", provider, hosts(boot)))
        return StreamCornerParser.list(reply).also { lists[provider.key] = it to System.currentTimeMillis() }
    }

    private suspend fun detail(provider: StreamCornerParser.Provider, id: String, refresh: Boolean = false): SCItem {
        val cacheKey = "${provider.key}:$id"
        if (!refresh) details[cacheKey]?.takeIf { fresh(it.second, DETAIL_TTL_MS) }?.let { return it.first }
        val boot = bootstrap()
        val reply = try {
            bridge.call(boot.workerPath, StreamCornerParser.message("provider-detail", provider, hosts(boot), id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "StreamCorner request failed")
        }
        return StreamCornerParser.detail(reply).also { details[cacheKey] = it to System.currentTimeMillis() }
    }

    /** The site hands its worker five random data hosts per request; the worker falls back through them. */
    private fun hosts(boot: SCBootstrap) = boot.hosts.shuffled().take(HOSTS_PER_REQUEST)

    /** Worker script and data hosts, read from the homepage's modules since both change with each site build. */
    private suspend fun bootstrap(): SCBootstrap {
        bootstrap?.takeIf { fresh(it.second, BOOTSTRAP_TTL_MS) }?.let { return it.first }
        return bootstrapLock.withLock {
            bootstrap?.takeIf { fresh(it.second, BOOTSTRAP_TTL_MS) }?.let { return@withLock it.first }
            val html = app.get("$mainUrl/").text
            val found = StreamCornerParser.modulePaths(html).firstNotNullOfOrNull { path ->
                StreamCornerParser.bootstrap(app.get("$mainUrl$path", referer = "$mainUrl/").text)
            } ?: bootstrap?.first ?: throw ErrorLoadingException("streamcorner.st changed: no data worker found in its scripts")
            Log.i(name, "worker ${found.workerPath}, ${found.hosts.size} data hosts")
            found.also { bootstrap = it to System.currentTimeMillis() }
        }
    }

    private fun fresh(at: Long, ttl: Long) = System.currentTimeMillis() - at < ttl

    companion object {
        private const val HOSTS_PER_REQUEST = 5
        private const val EMBED_PARALLEL = 3
        private const val EMBED_TIMEOUT_MS = 20_000L
        private const val LIST_TTL_MS = 3 * 60_000L
        private const val DETAIL_TTL_MS = 60_000L
        private const val BOOTSTRAP_TTL_MS = 30 * 60_000L
    }
}
