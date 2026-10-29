package com.nropvox

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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom

/**
 * zlive.st is a React SPA over a JSON API on iptv.zlive.st. Channels are a plain GET; live events and stream URLs
 * are POSTs whose body is AES-GCM sealed with a single-use ticket (see [ZLiveParser.seal]). The API serves a decoy
 * (an empty event list, a proxy URL) unless the request carries `Origin: https://zlive.st` and a non-browser UA.
 */
class ZLive : MainAPI() {
    override var mainUrl = "https://zlive.st"
    override var name = "ZLive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val api = "https://iptv.zlive.st"

    // A browser UA without the matching sec-fetch/sec-ch-ua headers also gets the decoy.
    private val apiHeaders get() = mapOf("User-Agent" to API_USER_AGENT, "Origin" to mainUrl, "Accept" to "application/json")

    private val random = SecureRandom()
    private val channelLock = Mutex()
    private val eventLock = Mutex()
    @Volatile private var channels: Pair<List<ZChannel>, Long>? = null
    @Volatile private var events: Pair<List<ZEvent>, Long>? = null

    override val mainPage = mainPageOf(
        "events" to "Live Events",
        "Sports" to "Sports Channels",
        "F1" to "Formula 1",
        "Entertainment" to "Entertainment",
        "Kids" to "Kids",
        "Other" to "Other Channels",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = when {
            page > 1 -> emptyList()
            request.data == "events" -> events().map { it.toSearchResponse() }
            else -> channels().filter { ZLiveParser.row(it) == request.data }.map { it.toSearchResponse() }
        }
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isEmpty() || page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val hits = events().filter { it.name!!.contains(q, ignoreCase = true) }.map { it.toSearchResponse() } +
            channels().filter { it.name!!.contains(q, ignoreCase = true) }.map { it.toSearchResponse() }
        return newSearchResponseList(hits, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    private fun ZChannel.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(name!!, ZLiveParser.watchUrl(mainUrl, id!!), TvType.Live) {
            posterUrl = ZLiveParser.flagPoster(flag)
        }

    private fun ZEvent.toSearchResponse(): SearchResponse =
        newLiveSearchResponse(name!!, ZLiveParser.watchUrl(mainUrl, id!!), TvType.Live) {
            posterUrl = ZLiveParser.cleanUrl(thumbnail)
        }

    override suspend fun load(url: String): LoadResponse {
        val id = ZLiveParser.pageId(url) ?: throw ErrorLoadingException("Not a zlive.st watch URL: $url")
        val pageUrl = ZLiveParser.watchUrl(mainUrl, id)

        events().firstOrNull { it.id == id }?.let { event ->
            return newLiveStreamLoadResponse(event.name!!, pageUrl, pageUrl) {
                posterUrl = ZLiveParser.cleanUrl(event.thumbnail)
                backgroundPosterUrl = posterUrl
                plot = ZLiveParser.eventPlot(event, System.currentTimeMillis())
                tags = listOfNotNull(event.type?.ifBlank { null })
            }
        }

        val all = channels()
        val channel = all.firstOrNull { it.id == id }
            ?: throw ErrorLoadingException("$id is no longer listed on zlive.st (events disappear when they end)")
        val row = ZLiveParser.row(channel)
        return newLiveStreamLoadResponse(channel.name!!, pageUrl, pageUrl) {
            posterUrl = ZLiveParser.flagPoster(channel.flag)
            plot = listOfNotNull(channel.tagline?.trim()?.ifEmpty { null }, epgPlot(id)).joinToString("\n\n").ifBlank { null }
            tags = listOfNotNull(channel.sport?.ifBlank { null }, channel.region?.ifBlank { null }, channel.quality)
            recommendations = all.filter { it.id != id && ZLiveParser.row(it) == row }.take(24).map { it.toSearchResponse() }
        }
    }

    private suspend fun epgPlot(id: String): String? = try {
        val response = app.get("$api/epg/$id.json", headers = apiHeaders, timeout = EPG_TIMEOUT_S)
        if (response.isSuccessful) ZLiveParser.epg(response.text)?.let { ZLiveParser.epgPlot(it) } else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = ZLiveParser.pageId(data) ?: return false
        val event = events().firstOrNull { it.id == id }
        val channel = if (event == null) channels().firstOrNull { it.id == id } else null
        // Ids equal source keys today, so an event that dropped off the list can still be tried by its id.
        val sources = event?.source?.let(::listOf) ?: channel?.sources ?: listOf(ZSource(id, null))
        val quality = ZLiveParser.quality(channel?.quality)

        var found = false
        for (source in sources) {
            val key = source.key?.ifBlank { null } ?: continue
            val location = resolve(key) ?: continue
            val label = source.label?.ifBlank { null } ?: "HLS"
            callback(newExtractorLink(name, "$name $label", location, ExtractorLinkType.M3U8) {
                // tims-001.sytes.net 404s without a zlive Referer or Origin; the other hosts ignore both.
                referer = "$mainUrl/"
                headers = mapOf("Origin" to mainUrl)
                quality?.let { this.quality = it }
            })
            found = true
        }
        return found
    }

    /** The playlist URL for a source key, or null when the API answers with its decoy proxy. */
    private suspend fun resolve(key: String): String? {
        val text = sealedPost("/resolve", ZLiveParser.resolvePayload(key, System.currentTimeMillis() / 1000))
        val location = ZLiveParser.location(text)
        if (location == null) {
            Log.w(name, "resolve $key: ${text.take(200)}")
            return null
        }
        if (ZLiveParser.isDecoy(location)) {
            Log.w(name, "resolve $key returned the decoy proxy; the bundle secret may have rotated")
            return null
        }
        return location
    }

    private suspend fun channels(): List<ZChannel> {
        channels?.takeIf { fresh(it.second, CHANNEL_TTL_MS) }?.let { return it.first }
        return channelLock.withLock {
            channels?.takeIf { fresh(it.second, CHANNEL_TTL_MS) }?.let { return@withLock it.first }
            val list = ZLiveParser.channels(app.get("$api/channels.json", headers = apiHeaders).text)
            if (list.isEmpty()) channels?.first ?: throw ErrorLoadingException("zlive.st returned no channels")
            else list.also { channels = it to System.currentTimeMillis() }
        }
    }

    private suspend fun events(): List<ZEvent> {
        events?.takeIf { fresh(it.second, EVENT_TTL_MS) }?.let { return it.first }
        return eventLock.withLock {
            events?.takeIf { fresh(it.second, EVENT_TTL_MS) }?.let { return@withLock it.first }
            val list = try {
                ZLiveParser.events(sealedPost("/streams", ZLiveParser.streamsPayload(System.currentTimeMillis() / 1000)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "events: ${e.message}")
                return@withLock events?.first.orEmpty()
            }
            // An empty list is also what the decoy looks like, so don't cache it for long.
            list.also { events = it to if (it.isEmpty()) System.currentTimeMillis() - EVENT_TTL_MS + 15_000 else System.currentTimeMillis() }
        }
    }

    private fun fresh(at: Long, ttl: Long) = System.currentTimeMillis() - at < ttl

    /** A sealed POST to iptv.zlive.st; each one burns its own ticket. */
    private suspend fun sealedPost(path: String, payload: String): String {
        val ticket = tryParseJson<ZTicket>(app.get("$api/session/ticket", headers = apiHeaders).text)?.w
            ?: throw ErrorLoadingException("zlive.st gave no session ticket")
        val iv = ByteArray(12).also(random::nextBytes)
        val sealed = ZLiveParser.seal(payload, ticket, ZLiveParser.utcDay(System.currentTimeMillis()), iv)
        val body = ZLiveParser.sealedJson(sealed).toRequestBody("application/json".toMediaType())
        return app.post("$api$path", headers = apiHeaders, requestBody = body).text
    }

    companion object {
        private const val API_USER_AGENT = "okhttp/4.12.0"
        private const val CHANNEL_TTL_MS = 10 * 60_000L
        private const val EVENT_TTL_MS = 2 * 60_000L
        private const val EPG_TIMEOUT_S = 10L
    }
}
