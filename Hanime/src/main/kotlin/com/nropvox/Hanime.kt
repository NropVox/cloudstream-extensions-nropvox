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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * hanime.tv has no server-side search or catalogue API: the site downloads the whole catalogue once
 * (`search_hvs`, ~3.4k videos) and filters it in a worker, so this plugin does the same.
 * Trending and series grouping only exist in the server-rendered HTML.
 * Streams come from an encrypted, signed "handshake" RPC (see [HanimeCrypto]).
 */
class Hanime : MainAPI() {
    override var mainUrl = "https://hanime.tv"
    override var name = "Hanime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val catalogUrl = "https://guest.freeanimehentai.net/api/v11/search_hvs"
    private val handshakeUrl = "https://auth.hanime.tv/api/v11/handshake"
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    private val catalogLock = Mutex()
    @Volatile
    private var catalog: Catalog? = null

    override val mainPage = mainPageOf(
        "trending/weekly" to "Trending This Week",
        "sort/created" to "Recent Uploads",
        "sort/released" to "New Releases",
        "sort/views" to "Most Viewed",
        "sort/likes" to "Most Liked",
        "tag/uncensored" to "Uncensored",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, value) = request.data.split('/', limit = 2)
        val (items, hasNext) = if (kind == "trending") {
            trending(value, page)
        } else {
            val videos = catalog().videos
            val list = when (kind) {
                "tag" -> videos.filter { value in it.tags.orEmpty() }.sortedByDescending { it.createdAt }
                else -> videos.sortedByDescending {
                    when (value) {
                        "released" -> it.releasedAt
                        "views" -> it.views
                        "likes" -> it.likes
                        else -> it.createdAt
                    } ?: 0L
                }
            }
            paginate(list, page, PAGE_SIZE)
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    /** Trending has no JSON source; cards are joined to the catalogue for consistent posters. */
    private suspend fun trending(timespan: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val doc = app.get("$mainUrl/browse/trending?timespan=$timespan&page=$page", headers = browserHeaders).document
        val bySlug = catalog().bySlug
        val items = cards(doc.selectFirst("main") ?: doc).mapNotNull { card ->
            bySlug[card.slug]?.toSearchResponse() ?: newMovieSearchResponse(card.title, videoUrl(card.slug), TvType.NSFW) {
                posterUrl = card.image
            }
        }
        // Paginator island props: {"page_number":[0,2],"total_pages":[0,144],...}
        val props = doc.selectFirst("astro-island[opts*=TrendingPaginator]")?.attr("props").orEmpty()
        val totalPages = Regex(""""total_pages":\[0,(\d+)]""").find(props)?.groupValues?.get(1)?.toIntOrNull()
        return items to (items.isNotEmpty() && page < (totalPages ?: 0))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = paginate(searchCatalog(query), page, SEARCH_PAGE_SIZE)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        searchCatalog(query).take(QUICK_SEARCH_SIZE).map { it.toSearchResponse() }

    /** Every query word must appear in the title or alt titles; title matches rank first, then views. */
    private suspend fun searchCatalog(query: String): List<Video> {
        val needle = query.trim().lowercase()
        val words = needle.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return catalog().videos
            .filter { video -> words.all { it in video.haystack } }
            .sortedWith(
                compareBy<Video> {
                    val title = it.name.orEmpty().lowercase()
                    when {
                        title.startsWith(needle) -> 0
                        needle in title -> 1
                        words.all { word -> word in title } -> 2
                        else -> 3
                    }
                }.thenByDescending { it.views ?: 0L }
            )
    }

    private fun paginate(list: List<Video>, page: Int, size: Int): Pair<List<SearchResponse>, Boolean> {
        val from = (page - 1).coerceAtLeast(0) * size
        return list.drop(from).take(size).map { it.toSearchResponse() } to (list.size > from + size)
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = slugOf(url) ?: throw ErrorLoadingException("Not a hanime video URL: $url")
        val bySlug = catalog().bySlug
        // The page is only needed for the series list and duration; the catalogue covers the rest.
        val doc = runCatching { app.get(videoUrl(slug), headers = browserHeaders).document }.getOrNull()
        val moreFrom = doc?.select("section h2")?.firstOrNull { it.text().startsWith("More from ") }
        val series = moreFrom?.text()?.removePrefix("More from ")?.trim()
        val episodes = moreFrom?.parents()?.firstOrNull { it.tagName() == "section" }?.let { cards(it) }.orEmpty()

        // A series is opened through its first episode so that every episode card leads to the same entry.
        val main = (episodes.firstOrNull()?.let { bySlug[it.slug] } ?: bySlug[slug])
        val title = when {
            episodes.size > 1 && !series.isNullOrBlank() -> series
            else -> main?.name ?: doc?.selectFirst("section#VideoDetails h1")?.text()
        } ?: throw ErrorLoadingException("Could not load $slug")
        val canonicalUrl = if (episodes.size > 1) videoUrl(episodes.first().slug) else videoUrl(slug)

        val recommendations = main?.brand?.let { brand ->
            val inSeries = episodes.map { it.slug }.toSet() + slug
            catalog().videos.filter { it.brand == brand && it.slug !in inSeries }
                .sortedByDescending { it.views ?: 0L }
                .take(RECOMMENDATION_COUNT)
                .map { it.toSearchResponse() }
        }
        val runtime = doc?.let { duration(it) }

        fun LoadResponse.fill() {
            posterUrl = main?.coverUrl ?: doc?.selectFirst("section#VideoDetails img")?.attr("src")
            backgroundPosterUrl = main?.posterUrl
            plot = main?.description?.let { Jsoup.parse(it).text() }
            tags = (main?.tags.orEmpty().map { tag -> tag.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } } +
                    listOfNotNull(main?.brand)).distinct()
            year = main?.releasedAtIso?.take(4)?.toIntOrNull()
            duration = runtime
            score = main?.let { video ->
                val likes = video.likes ?: return@let null
                val total = likes + (video.dislikes ?: 0L)
                if (total > 0) Score.from100((likes * 100 / total).toInt()) else null
            }
            this.recommendations = recommendations
        }

        if (episodes.size <= 1) {
            return newMovieLoadResponse(title, canonicalUrl, TvType.NSFW, videoUrl(slug)) { fill() }
        }
        val episodeList = episodes.mapIndexed { index, card ->
            newEpisode(videoUrl(card.slug)) {
                name = bySlug[card.slug]?.name ?: card.title
                episode = index + 1
                posterUrl = card.image
                description = bySlug[card.slug]?.description?.let { Jsoup.parse(it).text() }
            }
        }
        return newTvSeriesLoadResponse(title, canonicalUrl, TvType.NSFW, episodeList) { fill() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val slug = slugOf(data) ?: data
        val videoId = runCatching { catalog().bySlug[slug]?.id }.getOrNull()
        val streams = async { runCatching { emitStreams(slug, callback) }.onFailure { Log.w(name, "Streams for $slug: $it") }.getOrDefault(false) }
        val downloads = async {
            if (videoId == null) false
            else runCatching { emitDownloads(slug, videoId, callback) }.onFailure { Log.w(name, "Downloads for $slug: $it") }.getOrDefault(false)
        }
        awaitAll(streams, downloads).any { it }
    }

    /** First-party HLS media playlists (AES-128, standard key URI). 1080p is a premium promotion with no `src`. */
    private suspend fun emitStreams(slug: String, callback: (ExtractorLink) -> Unit): Boolean {
        val response = handshake(mapOf("directive" to "htv_player_handshake", "slug" to slug))
        val token = response.headers["x-token"] ?: return false
        val sources = tryParseJson<PlayerHandshake>(HanimeCrypto.decrypt(token))?.sources.orEmpty()
            .filter { it.kind == "normal" && !it.src.isNullOrBlank() }
        for (source in sources) {
            val src = source.src ?: continue
            callback(
                newExtractorLink(name, "$name HLS", if (src.startsWith("http")) src else "$mainUrl$src", ExtractorLinkType.M3U8) {
                    quality = source.height ?: 0
                    referer = "$mainUrl/"
                    headers = browserHeaders
                }
            )
        }
        return sources.isNotEmpty()
    }

    /** MP4 mirrors on Pixeldrain; the site hides them behind a sign-in prompt but serves them to guests. */
    private suspend fun emitDownloads(slug: String, videoId: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val response = handshake(mapOf("directive" to "get_download_links", "slug" to slug, "hentai_video_id" to videoId))
        val token = tryParseJson<TokenBody>(response.text)?.token ?: return false
        val links = tryParseJson<DownloadLinks>(HanimeCrypto.decrypt(token))?.downloadLinks.orEmpty()
        var found = false
        for (link in links) {
            val url = link.url ?: continue
            // https://pixeldrain.com/u/<id> -> direct file https://pixeldrain.com/api/file/<id>
            val id = url.substringAfter("/u/", "").substringBefore('?').ifEmpty { continue }
            val host = url.substringBefore("/u/")
            callback(
                newExtractorLink(name, "$name Pixeldrain", "$host/api/file/$id", ExtractorLinkType.VIDEO) {
                    quality = link.height ?: 0
                    headers = browserHeaders
                }
            )
            found = true
        }
        return found
    }

    private suspend fun handshake(payload: Map<String, Any>): com.lagradost.nicehttp.NiceResponse {
        val time = System.currentTimeMillis() / 1000
        val token = HanimeCrypto.encrypt((mapOf("timestamp_unix" to time) + payload).toJson())
        val body = mapOf("token" to token).toJson().toRequestBody("application/json".toMediaType())
        val response = app.post(
            handshakeUrl,
            headers = browserHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "Accept" to "application/json",
                "x-signature-version" to "web2",
                "x-signature" to HanimeCrypto.signature(time, mainUrl),
                "x-time" to time.toString(),
            ),
            requestBody = body,
        )
        // 401 UA1 means the signature salts in the vendor WASM rotated (see HanimeCrypto).
        if (!response.isSuccessful) throw ErrorLoadingException("Handshake ${response.code}: ${response.text.take(200)}")
        return response
    }

    /** The full catalogue; the site marks it cacheable for an hour. */
    private suspend fun catalog(): Catalog {
        catalog?.takeIf { System.currentTimeMillis() - it.fetchedAt < CATALOG_TTL_MS }?.let { return it }
        return catalogLock.withLock {
            catalog?.takeIf { System.currentTimeMillis() - it.fetchedAt < CATALOG_TTL_MS }?.let { return@withLock it }
            val text = app.get(catalogUrl, headers = browserHeaders, timeout = CATALOG_TIMEOUT_S).text
            val videos = tryParseJson<CatalogResponse>(text)?.data?.filter { !it.slug.isNullOrBlank() && !it.name.isNullOrBlank() }
            if (videos.isNullOrEmpty()) {
                // Keep serving a stale copy rather than failing every row.
                catalog ?: throw ErrorLoadingException("Could not load the hanime catalogue")
            } else {
                Catalog(videos, System.currentTimeMillis()).also { catalog = it }
            }
        }
    }

    private fun cards(root: org.jsoup.nodes.Element): List<Card> =
        root.select("a[href^=/videos/hentai/]").mapNotNull { a ->
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val slug = slugOf(a.attr("href")) ?: return@mapNotNull null
            val title = img.attr("alt").removeSuffix(" thumbnail").removeSuffix(" HD cover image")
            Card(slug, title, img.attr("src").ifBlank { null })
        }.distinctBy { it.slug }

    /** `PT25M20S` from the page's JSON-LD, in minutes. */
    private fun duration(doc: Document): Int? {
        val ld = doc.selectFirst("script[type=application/ld+json]")?.data() ?: return null
        val iso = Regex(""""duration"\s*:\s*"(PT[^"]+)"""").find(ld)?.groupValues?.get(1) ?: return null
        val match = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso) ?: return null
        val (h, m, s) = match.destructured
        val seconds = (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        return if (seconds > 0) (seconds + 30) / 60 else null
    }

    private fun Video.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(name.orEmpty(), videoUrl(slug.orEmpty()), TvType.NSFW) {
            posterUrl = coverUrl
            year = releasedAtIso?.take(4)?.toIntOrNull()
        }

    private fun videoUrl(slug: String) = "$mainUrl/videos/hentai/$slug"

    private fun slugOf(url: String): String? =
        url.substringAfter("/videos/hentai/", "").substringBefore('?').substringBefore('#').trim('/').ifEmpty { null }

    private class Catalog(val videos: List<Video>, val fetchedAt: Long) {
        val bySlug: Map<String, Video> = videos.associateBy { it.slug.orEmpty() }
    }

    private data class Card(val slug: String, val title: String, val image: String?)

    data class CatalogResponse(@JsonProperty("data") val data: List<Video>? = null)

    data class Video(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("search_titles") val searchTitles: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("cover_url") val coverUrl: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("brand") val brand: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("views") val views: Long? = null,
        @JsonProperty("likes") val likes: Long? = null,
        @JsonProperty("dislikes") val dislikes: Long? = null,
        @JsonProperty("created_at_unix") val createdAt: Long? = null,
        @JsonProperty("released_at_unix") val releasedAt: Long? = null,
        @JsonProperty("released_at") val releasedAtIso: String? = null,
    ) {
        val haystack: String by lazy { "${name.orEmpty()} ${searchTitles.orEmpty()}".lowercase() }
    }

    data class PlayerHandshake(@JsonProperty("sources") val sources: List<Source>? = null)

    data class Source(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )

    data class TokenBody(@JsonProperty("token") val token: String? = null)

    data class DownloadLinks(@JsonProperty("download_links") val downloadLinks: List<DownloadLink>? = null)

    data class DownloadLink(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("height") val height: Int? = null,
    )

    companion object {
        private const val PAGE_SIZE = 24
        private const val SEARCH_PAGE_SIZE = 40
        private const val QUICK_SEARCH_SIZE = 10
        private const val RECOMMENDATION_COUNT = 20
        private const val CATALOG_TTL_MS = 60 * 60 * 1000L
        private const val CATALOG_TIMEOUT_S = 60L
    }
}
