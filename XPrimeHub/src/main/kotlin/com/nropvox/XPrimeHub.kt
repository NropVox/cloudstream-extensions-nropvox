package com.nropvox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

class XPrimeHub : MainAPI() {
    override var mainUrl = "https://xprimehub.pics"
    override var name = "XPrimeHub"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val hosts = NexdriveResolver(name)

    // WP category term ids (`wp/v2/categories`); blank is every post.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "3" to "ULLU Originals",
        "9250" to "NeonX",
        "10042" to "MoodX",
        "14" to "HotX Originals",
        "12018" to "Atrangii",
        "25" to "Xprime",
        "27" to "PrimeShots",
        "22" to "Niksindian",
        "12051" to "Brazzers",
        "1032" to "English",
        "9013" to "Tagalog",
        "170" to "Korean",
        "7051" to "Japanese",
        "237" to "Dual Audio",
    )

    /**
     * Cloudflare caches `/wp-json/…` by path and ignores the query string, so every page, search and category
     * would get whichever response was cached first. `/index.php?rest_route=` bypasses the edge cache.
     */
    private fun posts(params: String) = "$mainUrl/index.php?rest_route=/wp/v2/posts&$params"

    private suspend fun postList(params: String): Pair<List<XphPost>, Int> {
        val response = app.get(posts("$params&$LIST_FIELDS"), timeout = 30L)
        // Past the last page WP answers 400 `rest_post_invalid_page_number`.
        val items = tryParseJson<List<XphPost>>(response.text).orEmpty()
        return items to (response.headers["x-wp-totalpages"]?.toIntOrNull() ?: 0)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = if (request.data.isBlank()) "" else "&categories=${request.data}"
        val (items, pages) = postList("per_page=$PAGE_SIZE&page=$page$category")
        return newHomePageResponse(request.name, items.mapNotNull { it.toSearchResponse() }, hasNext = page < pages)
    }

    private fun XphPost.toSearchResponse(): SearchResponse? {
        val url = link ?: return null
        val rendered = title?.rendered ?: return null
        return newMovieSearchResponse(XPrimeHubParser.cleanTitle(rendered).ifBlank { rendered }, url, TvType.NSFW) {
            posterUrl = poster
            year = XPrimeHubParser.year(rendered)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val (items, pages) = postList("search=${encode(query.trim())}&per_page=$PAGE_SIZE&page=$page")
        return newSearchResponseList(items.mapNotNull { it.toSearchResponse() }, hasNext = page < pages)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val post = tryParseJson<List<XphPost>>(
            app.get(posts("slug=${encode(slug)}&_embed=wp:featuredmedia"), timeout = 30L).text
        )?.firstOrNull() ?: throw ErrorLoadingException("Could not load $name post $slug")
        val rendered = post.title?.rendered ?: return null
        val title = XPrimeHubParser.cleanTitle(rendered).ifBlank { rendered }
        val doc = XPrimeHubParser.body(post.content?.rendered.orEmpty(), mainUrl)
        val groups = XPrimeHubParser.groups(doc)
        // `{Episode N}` headings split a series post; "Complete" season posts are one merged file per quality.
        val episodic = groups.mapNotNull { group -> XPrimeHubParser.episode(group.heading)?.let { it to group } }

        val response: LoadResponse = if (episodic.isNotEmpty()) {
            val season = XPrimeHubParser.season(rendered) ?: 1
            val episodes = episodic.groupBy({ it.first }, { it.second }).toSortedMap().map { (number, parts) ->
                newEpisode(XphLink(parts.flatMap { it.buttons }).toJson()) {
                    this.season = XPrimeHubParser.season(parts.first().heading) ?: season
                    this.episode = number
                    name = "Episode $number"
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes)
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, XphLink(groups.flatMap { it.buttons }).toJson())
        }
        return response.apply {
            posterUrl = post.poster
            backgroundPosterUrl = XPrimeHubParser.screenshot(doc, URI(mainUrl).host)
            // imgbb.fit screenshots answer a Cloudflare 403 without the site as Referer.
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            plot = XPrimeHubParser.plot(doc)
            year = XPrimeHubParser.year(rendered)
            tags = (XPrimeHubParser.list(XPrimeHubParser.detail(doc, "Genre")) +
                XPrimeHubParser.list(XPrimeHubParser.detail(doc, "Language"))).distinct()
            addActors(XPrimeHubParser.list(XPrimeHubParser.detail(doc, "Cast")))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = tryParseJson<XphLink>(data) ?: return false
        val sink = Sink(callback)
        // Every nexdrive page, host and mirror resolves at once; only the Cloudflare-challenged V-Cloud hops queue.
        link.pages.amap { page ->
            safely(name, "nexdrive ${page.label}") {
                XPrimeHubParser.hostLinks(app.get(page.url, timeout = 30L).document).amap { url ->
                    hosts.resolve(url) { host, file, streamable, quality -> sink.add(page, host, file, streamable, quality) }
                }
            }
        }
        return sink.isNotEmpty()
    }

    /** Download-only links (see [NexdriveSink]) rank as Unknown quality under their own source name. */
    private inner class Sink(private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        fun isNotEmpty() = emitted.isNotEmpty()

        /** [quality] overrides the heading's for transcodes, whose size no longer applies; the player shows it. */
        suspend fun add(page: XphPage, host: String, url: String, streamable: Boolean, quality: Int? = null) {
            if (!emitted.add(url)) return
            val height = quality ?: XPrimeHubParser.quality("${page.label} ${page.heading}").takeIf { it > 0 }
            val size = if (quality != null) null else XPrimeHubParser.size(page.label) ?: XPrimeHubParser.size(page.heading)
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, height?.let { "${it}p" }, size).joinToString(" · ")
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                this.quality = if (streamable) height ?: Qualities.Unknown.value else Qualities.Unknown.value
            })
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val PAGE_SIZE = 20
        const val LIST_FIELDS = "_fields=id,link,title,_links.wp:featuredmedia,_embedded&_embed=wp:featuredmedia"
    }
}
