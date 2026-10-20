package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * hentaimama.io is a WordPress/DooPlay site with no JSON catalog API, so catalogs, search and details
 * are parsed from HTML. The stream step is clean: `admin-ajax.php?action=get_player_contents` returns an
 * iframe per mirror, and each embed page is a JW Player page whose `sources` hold the media URL.
 * No Referer, cookie or token is needed (Cloudflare serves it without a challenge).
 *
 * Mirrors on every episode: option 5 = labelled MP4s (1080/720/480), option 3 = signed HLS (~1 h expiry),
 * option 1 = a single unlabelled MP4.
 */
class HentaiMama : MainAPI() {
    override var mainUrl = "https://hentaimama.io"
    override var name = "HentaiMama"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "tvshows/" to "Recent Series",
        "trending/" to "Trending",
        "episodes/" to "Latest Episodes",
        "genre/uncensored/?filter=rating" to "Uncensored (Top Rated)",
        "genre/3d/?filter=rating" to "3D (Top Rated)",
        "genre/harem/?filter=rating" to "Harem (Top Rated)",
        "genre/maid/?filter=rating" to "Maid (Top Rated)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page), headers = browserHeaders).document
        return newHomePageResponse(request.name, doc.toSearchResponses(), doc.hasNextPage())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page > 1) "$mainUrl/page/$page/?s=$q" else "$mainUrl/?s=$q"
        val doc = app.get(url, headers = browserHeaders).document
        return newSearchResponseList(doc.toSearchResponses(), doc.hasNextPage())
    }

    override suspend fun load(url: String): LoadResponse {
        var seriesUrl = url
        var doc = app.get(url, headers = browserHeaders).document
        if ("/episodes/" in url) {
            // Latest-episode cards link to the episode; the page itself points at its series.
            seriesUrl = (doc.selectFirst(".episode-series-img a[href*=/tvshows/]")
                ?: doc.selectFirst("a[href*=/tvshows/]"))?.absUrl("href")
                ?: throw ErrorLoadingException("No series link on $url")
            doc = app.get(seriesUrl, headers = browserHeaders).document
        }

        val title = doc.selectFirst("h1.dsc-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Not a series page: $seriesUrl")
        val poster = doc.selectFirst("div.dsc-poster img")?.absUrl("src")
        val plot = doc.selectFirst("div.dsc-desc")?.text()?.trim()?.ifBlank { null }
        val tags = doc.select("div.dsc-genres a").map { it.text().trim() }
        val rating = doc.selectFirst("b.dsc-score")?.text()?.trim()?.toDoubleOrNull()
        val year = doc.select("div.dsc-stat")
            .firstOrNull { it.selectFirst("span")?.text()?.trim() == "Aired" }
            ?.selectFirst("b")?.text()?.let { YEAR.find(it)?.value?.toIntOrNull() }

        val episodes = doc.select("div#episodes a.dt-se-item").mapIndexed { index, el ->
            newEpisode(el.absUrl("href")) {
                name = el.selectFirst("b.dt-se-title")?.text()?.trim()
                episode = el.selectFirst("b.dt-se-num")?.text()?.let { DIGITS.find(it)?.value?.toIntOrNull() }
                    ?: (index + 1)
                posterUrl = el.selectFirst("span.dt-se-thumb img")?.absUrl("src")
                description = el.selectFirst("span.dt-se-desc")?.text()?.trim()
                    ?.takeUnless { it.startsWith("No synopsis") }
            }
        }

        fun LoadResponse.fill() {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            score = rating?.let { Score.from10(it) }
        }

        if (episodes.size == 1) {
            return newMovieLoadResponse(title, seriesUrl, TvType.NSFW, episodes.first().data) { fill() }
        }
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.NSFW, episodes) { fill() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, headers = browserHeaders).text
        val postId = POST_ID.find(page)?.groupValues?.get(1) ?: return false
        val mirrors = Jsoup.parse(page).select("ul.dt-mi-tabs a.options").mapNotNull { a ->
            val option = a.attr("href").removePrefix("#option-").toIntOrNull() ?: return@mapNotNull null
            option to a.text().trim()
        }

        var found = false
        coroutineScope {
            mirrors.map { (option, label) ->
                async {
                    runCatching { emitMirror(postId, option, label, callback) }
                        .onSuccess { if (it) found = true }
                        .onFailure { Log.w(name, "Mirror $label ($option): $it") }
                }
            }.awaitAll()
        }
        return found
    }

    /** ajax -> iframe -> embed page -> JW Player `sources` -> links. */
    private suspend fun emitMirror(
        postId: String,
        option: Int,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = browserHeaders,
            data = mapOf("action" to "get_player_contents", "a" to postId, "i" to option.toString())
        ).text
        val iframe = tryParseJson<List<String>>(res)?.firstOrNull { it.isNotBlank() } ?: return false
        val embedUrl = Jsoup.parse(iframe).selectFirst("iframe")?.attr("src")?.ifBlank { null } ?: return false

        val embed = app.get(embedUrl, headers = browserHeaders).text
        val sources = SOURCES.find(embed)?.groupValues?.get(1)?.let { tryParseJson<List<JwSource>>(it) }
            ?: return false

        var emitted = false
        for (source in sources) {
            val file = source.file?.ifBlank { null } ?: continue
            if (source.type == "hls" || ".m3u8" in file) {
                generateM3u8(source = "$name $label HLS", streamUrl = file, referer = mainUrl).forEach(callback)
            } else {
                callback(newExtractorLink(name, "$name $label", file, ExtractorLinkType.VIDEO) {
                    quality = source.label?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                    referer = mainUrl
                })
            }
            emitted = true
        }
        return emitted
    }

    /** `path` may carry a query (`?filter=`); the page segment goes before it. */
    private fun pageUrl(path: String, page: Int): String {
        val base = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val paged = if (page > 1) "$mainUrl/${base}page/$page/" else "$mainUrl/$base"
        return if (query.isEmpty()) paged else "$paged?$query"
    }

    /** `article.item` (poster grids, episode feed) and `article.series-card` (genre/search pages). */
    private fun Document.toSearchResponses(): List<SearchResponse> =
        select("article.series-card, article.item").mapNotNull { it.toSearchResponse() }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("h3 a[href]") ?: selectFirst("a.dt-ep-cover[href]") ?: return null
        val href = link.absUrl("href").ifBlank { return null }
        // Episode cards name the series in `.season_m .b`; the h3 holds the episode title.
        val title = selectFirst("div.season_m span.b")?.text()?.trim()?.ifBlank { null }
            ?: link.text().trim().ifBlank { return null }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = selectFirst("img")?.absUrl("src")?.ifBlank { null }
        }
    }

    private fun Document.hasNextPage(): Boolean {
        val pagination = selectFirst("div.pagination[data-pages]") ?: return false
        val pages = pagination.attr("data-pages").toIntOrNull() ?: return false
        val current = pagination.attr("data-page").toIntOrNull() ?: return false
        return current < pages
    }

    data class JwSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    companion object {
        private val YEAR = Regex("""\d{4}""")
        private val DIGITS = Regex("""\d+""")
        private val POST_ID = Regex("""\ba:\s*'(\d+)'""")
        private val SOURCES = Regex("""sources:\s*(\[\{.*?\}\])""", RegexOption.DOT_MATCHES_ALL)
    }
}
