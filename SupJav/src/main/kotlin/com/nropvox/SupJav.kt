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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WordPress JAV site behind a Cloudflare challenge. Each post lists host servers whose embeds come from
 * an unchallenged redirect API on lk1.supremejav.com: VAS (Vidara), EVS (EarnVids), LUC (LuluStream) and VOE;
 * older posts list TV (turbovidhls, dead), FST (fc2stream) and ST (StreamTape) instead.
 */
class SupJav : MainAPI() {
    override var mainUrl = "https://supjav.com"
    override var name = "SupJav"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val site by lazy { Challenged(CloudflareKiller()) }

    override val mainPage = mainPageOf(
        "popular?sort=week" to "Week's Popular",
        "category/censored-jav" to "Censored",
        "category/uncensored-jav" to "Uncensored",
        "category/english-subtitles" to "English Subtitles",
        "category/amateur" to "Amateur",
        "category/reducing-mosaic" to "Reducing Mosaic",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = site.get(SupJavParser.listUrl(mainUrl, request.data, page)).document
        return newHomePageResponse(
            HomePageList(request.name, SupJavParser.cards(doc).map { it.toSearchResponse() }, isHorizontalImages = true),
            hasNext = SupJavParser.hasNext(doc)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val doc = site.get(SupJavParser.searchUrl(mainUrl, query, page)).document
        return newSearchResponseList(SupJavParser.cards(doc).map { it.toSearchResponse() }, SupJavParser.hasNext(doc))
    }

    override suspend fun load(url: String): LoadResponse {
        val detail = SupJavParser.detail(site.get(url).document)
            ?: throw ErrorLoadingException("SupJav: no post found at $url")
        val links = SupJavLinks(url, detail.servers.map { SupJavLinkServer(it.name, it.link) })
        return newMovieLoadResponse(detail.title, url, TvType.NSFW, links.toJson()) {
            posterUrl = detail.poster
            plot = detail.plot
            tags = detail.tags
            recommendations = detail.related.map { it.toSearchResponse() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = tryParseJson<SupJavLinks>(data)?.servers?.map { SupJavServer(it.name, it.link) }
            ?: SupJavParser.detail(site.get(data).document)?.servers
            ?: return false
        runAllAsync(*servers.map { server ->
            suspend {
                // A stalled host would otherwise hold the player until every server gives up.
                withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                    val embed = embedOf(server) ?: return@withTimeoutOrNull
                    if (!loadExtractor(embed, "$PLAYER_ORIGIN/", subtitleCallback, callback)) {
                        Log.w(name, "${server.name}: no extractor for $embed")
                    }
                } ?: Log.w(name, "${server.name}: timed out")
            }
        }.toTypedArray())
        return servers.isNotEmpty()
    }

    /** `?l=<data-link>` returns a page with `OLID`; `?c=<OLID reversed>` 302s to the host embed. */
    private suspend fun embedOf(server: SupJavServer): String? {
        val player = app.get("$PLAYER_API?l=${server.link}", referer = "$mainUrl/")
        val token = SupJavParser.embedToken(player.text) ?: return null
        val redirect = app.get("$PLAYER_API?c=$token", referer = player.url, allowRedirects = false)
        return redirect.headers["location"]?.takeIf { it.startsWith("http") }?.let(SupJavParser::embedUrl)
    }

    private fun SupJavCard.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) { posterUrl = poster }

    private companion object {
        const val PLAYER_ORIGIN = "https://lk1.supremejav.com"
        const val PLAYER_API = "$PLAYER_ORIGIN/supjav.php"
        const val SERVER_TIMEOUT_MS = 25_000L
    }
}

/**
 * supjav.com answers every path with a Cloudflare challenge. The first request solves it alone; later ones
 * replay the clearance two at a time, since 5+ parallel requests deadlock inside CloudflareKiller.
 */
private class Challenged(private val cloudflare: CloudflareKiller) {
    @Volatile private var warmUntil = 0L
    private val lock = Mutex()
    private val slots = Semaphore(2)

    suspend fun get(url: String): NiceResponse {
        if (System.currentTimeMillis() >= warmUntil) lock.withLock {
            if (System.currentTimeMillis() >= warmUntil) {
                val response = app.get(url, interceptor = cloudflare, timeout = 60L)
                if (response.code in 200..299) warmUntil = System.currentTimeMillis() + WARM_MS
                return checked(response)
            }
        }
        return checked(slots.withPermit { app.get(url, interceptor = cloudflare, timeout = 60L) })
    }

    private fun checked(response: NiceResponse): NiceResponse {
        if (response.code in listOf(403, 503) && response.headers["server"].orEmpty().startsWith("cloudflare")) {
            warmUntil = 0L
            throw ErrorLoadingException("Cloudflare check not passed, retry in a moment")
        }
        return response
    }

    private companion object {
        const val WARM_MS = 10 * 60 * 1000L
    }
}
