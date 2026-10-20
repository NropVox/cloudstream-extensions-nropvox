package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

/**
 * hentaihaven.xxx is a Next.js skin over a headless WordPress (Madara "wp-manga" model: a series is a
 * `wp-manga` post, an episode is a "chapter"). Everything comes from JSON; no HTML parsing.
 * Both hosts sit behind Cloudflare but only reject requests without a User-Agent.
 *
 * Streams: newer uploads are "Octopus" imports (`octopusmanifest.org/<uuid>/playlist.m3u8`, fMP4 with
 * in-manifest WebVTT subtitles). Older ones point at `master-lengs.org`, whose segment hosts are
 * partly dead; the site itself can't play those either.
 */
class HentaiHaven : MainAPI() {
    override var mainUrl = "https://hentaihaven.xxx"
    override var name = "HentaiHaven"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val cmsApi = "https://cms.hentaihaven.xxx/wp-json"
    private val imageHost = "https://img.hentaihaven.xxx/"
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "episodes" to "Latest Episodes",
        "trending/month" to "Trending This Month",
        "ranked/views" to "Most Viewed",
        "ranked/rating" to "Top Rated",
        "browse/modified" to "Recently Updated",
        "genre/107" to "Uncensored",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, value) = (request.data.split('/', limit = 2) + "").let { it[0] to it[1] }
        val (items, hasNext) = when (kind) {
            "episodes" -> {
                val res = get<EpisodeFeed>("$cmsApi/hhaven/v1/episodes?page=$page&per_page=$PAGE_SIZE&locale=en")
                // One card per series; the feed lists every new episode separately.
                res.items.orEmpty().distinctBy { it.mangaSlug }.mapNotNull { it.toSearchResponse() } to res.pagination?.hasNext
            }
            "trending" -> get<CardFeed>("$cmsApi/hhaven/v1/catalog/trending?period=$value&page=$page&per_page=$PAGE_SIZE").let {
                it.items.orEmpty().mapNotNull { card -> card.toSearchResponse() } to it.pagination?.hasNext
            }
            "ranked" -> get<CardFeed>("$cmsApi/hhaven/v1/catalog/ranked?sort=$value&page=$page&per_page=$PAGE_SIZE&locale=en").let {
                it.items.orEmpty().mapNotNull { card -> card.toSearchResponse() } to it.pagination?.hasNext
            }
            "genre" -> browse(page, "orderby" to "modified", "genre" to value)
            else -> browse(page, "orderby" to value)
        }
        return newHomePageResponse(request.name, items, hasNext == true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val needle = query.trim()
        val (items, hasNext) = browse(page, "orderby" to "date", "search" to needle)
        // The API only orders by date and also matches descriptions; lift title matches within the page.
        val ranked = items.sortedBy {
            when {
                it.name.equals(needle, ignoreCase = true) -> 0
                it.name.contains(needle, ignoreCase = true) -> 1
                else -> 2
            }
        }
        return newSearchResponseList(ranked, hasNext)
    }

    /** The site's own search/browse route (`/search/` page). Matches title and description text. */
    private suspend fun browse(page: Int, vararg params: Pair<String, String>): Pair<List<SearchResponse>, Boolean> {
        val orderby = params.firstOrNull { it.first == "orderby" }?.second ?: "date"
        val query = (listOf(
            "per_page" to PAGE_SIZE.toString(),
            "live" to "1",
            "locale" to "en",
            "order" to if (orderby == "title") "asc" else "desc",
            "page" to page.toString(),
        ) + params).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val res = get<BrowseFeed>("$mainUrl/api/manga/?$query")
        val items = res.data.orEmpty().mapNotNull { it.toSearchResponse() }
        return items to (page < (res.totalPages ?: 0))
    }

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val slug = slugOf(url) ?: throw ErrorLoadingException("Not a hentaihaven series URL: $url")
        val chaptersJob = async { get<List<Chapter>>("$cmsApi/hhaven/v1/manga/by-slug/$slug/chapters") }
        val series = get<List<WpManga>>(
            "$cmsApi/wp/v2/wp-manga?slug=$slug&_fields=id,slug,title,content,meta,wp-manga-genre,wp-manga-release,wp-manga-author"
        ).firstOrNull() ?: throw ErrorLoadingException("Series not found: $slug")

        val genresJob = async { termNames("wp-manga-genre", series.genres) }
        val releaseJob = async { termNames("wp-manga-release", series.releases) }
        val authorJob = async { termNames("wp-manga-author", series.authors) }

        val title = series.title?.rendered?.let { Jsoup.parse(it).text() } ?: slug
        val seriesUrl = seriesUrl(slug)
        val poster = series.meta?.thumbnail?.let { imageUrl(it) }
        val plot = series.content?.rendered?.let { Jsoup.parse(it).text() }?.ifBlank { null }
        val chapters = chaptersJob.await().filter { it.isDeleted != true && it.hasSource }
            .sortedBy { it.number ?: Int.MAX_VALUE }
        val tags = genresJob.await() + authorJob.await()
        val year = releaseJob.await().firstNotNullOfOrNull { it.toIntOrNull() }

        fun LoadResponse.fill() {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }

        if (chapters.size == 1) {
            val only = chapters.first()
            return@coroutineScope newMovieLoadResponse(title, seriesUrl, TvType.NSFW, only.toData()) {
                fill()
                duration = only.durationSeconds?.takeIf { it > 0 }?.let { (it + 30) / 60 }
            }
        }
        val episodes = chapters.mapIndexed { index, chapter ->
            newEpisode(chapter.toData()) {
                name = chapter.chapterName
                episode = chapter.number ?: (index + 1)
                posterUrl = chapter.thumbnail()
                runTime = chapter.durationSeconds?.takeIf { it > 0 }?.let { (it + 30) / 60 }
                addDate(chapter.chapterDate?.take(10))
            }
        }
        newTvSeriesLoadResponse(title, seriesUrl, TvType.NSFW, episodes) { fill() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = tryParseJson<EpisodeData>(data) ?: return false
        val uuid = episode.uuid?.ifBlank { null }
            ?: episode.src?.let { OCTOPUS_UUID.find(it)?.value }
        if (uuid != null) {
            val master = "$OCTOPUS_HOST/$uuid/playlist.m3u8"
            callback(newExtractorLink(name, "$name H.264", master, ExtractorLinkType.M3U8))
            callback(newExtractorLink(name, "$name VP9", "$OCTOPUS_HOST/$uuid/playlist_vp9.m3u8", ExtractorLinkType.M3U8))
            runCatching { emitSubtitles(master, subtitleCallback) }.onFailure { Log.w(name, "Subtitles for $uuid: $it") }
            return true
        }
        val src = episode.src?.takeIf { it.endsWith(".m3u8") }
            ?: episode.slug?.let { slug -> runCatching { streamSource(slug) }.onFailure { Log.w(name, "Stream for $slug: $it") }.getOrNull() }
            ?: return false
        return emitLegacy(src, callback)
    }

    /**
     * Legacy `master-lengs.org` masters sometimes omit BANDWIDTH, which ExoPlayer rejects, so each variant
     * is handed over directly. Their MPEG-TS segments (named `.html`) live on per-title hosts, many of
     * which no longer resolve; probe one segment so a dead episode reports "no links" instead of a player error.
     */
    private suspend fun emitLegacy(master: String, callback: (ExtractorLink) -> Unit): Boolean {
        val base = master.substringBefore('?').substringBeforeLast('/')
        fun resolve(uri: String) = if (uri.startsWith("http")) uri else "$base/${uri.trimStart('/')}"
        val lines = app.get(master, headers = browserHeaders).text.lines().map { it.trim() }
        val variants = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            resolve(uri) to Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
        }.ifEmpty { listOf(master to null) }

        val probe = variants.first().first
        val segment = app.get(probe, headers = browserHeaders).text.lines().firstOrNull { it.startsWith("http") }
        if (segment != null) {
            val alive = runCatching {
                app.get(segment, headers = browserHeaders + ("Range" to "bytes=0-187"), timeout = PROBE_TIMEOUT_S).isSuccessful
            }.getOrDefault(false)
            if (!alive) {
                Log.w(name, "Legacy segment host is down: ${segment.substringBefore("/theme/")}")
                return false
            }
        }
        for ((url, height) in variants) {
            callback(newExtractorLink(name, if (height != null) "$name ${height}p" else name, url, ExtractorLinkType.M3U8) {
                quality = height ?: Qualities.Unknown.value
            })
        }
        return true
    }

    /**
     * The master lists its WebVTT tracks as SUBTITLES renditions, but each URI is a bare `.vtt` file
     * rather than a media playlist, so ExoPlayer drops them. Hand them over as external files instead.
     */
    private suspend fun emitSubtitles(master: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val base = master.substringBeforeLast('/')
        app.get(master, headers = browserHeaders).text.lineSequence()
            .filter { it.startsWith("#EXT-X-MEDIA:") && "TYPE=SUBTITLES" in it }
            .forEach { line ->
                fun attr(key: String) = Regex("""$key="([^"]*)"""").find(line)?.groupValues?.get(1)
                val uri = attr("URI") ?: return@forEach
                val code = attr("LANGUAGE")
                val label = code?.let { Locale(it).getDisplayLanguage(Locale.ENGLISH) }?.takeIf { it != code }
                    ?: attr("NAME") ?: code ?: "Unknown"
                subtitleCallback(newSubtitleFile(label, if (uri.startsWith("http")) uri else "$base/$uri"))
            }
    }

    /** What the player does for non-Octopus episodes. The returned `hash`/`expirein` are not enforced. */
    private suspend fun streamSource(dataSlug: String): String? {
        val body = mapOf("slug" to dataSlug).toJson().toRequestBody("application/json".toMediaType())
        val text = app.post("$mainUrl/api/stream/", headers = browserHeaders, requestBody = body).text
        return tryParseJson<StreamResponse>(text)?.data?.let { it.sources.orEmpty() + it.fallbackSources.orEmpty() }
            ?.firstNotNullOfOrNull { it.src?.ifBlank { null } }
    }

    private suspend fun termNames(taxonomy: String, ids: List<Int>?): List<String> {
        if (ids.isNullOrEmpty()) return emptyList()
        return runCatching {
            get<List<Term>>("$cmsApi/wp/v2/$taxonomy?include=${ids.joinToString(",")}&per_page=100&_fields=id,name")
                .mapNotNull { it.name?.let { name -> Jsoup.parse(name).text() } }
        }.onFailure { Log.w(name, "Terms $taxonomy: $it") }.getOrDefault(emptyList())
    }

    private suspend inline fun <reified T : Any> get(url: String): T =
        parseJson<T>(app.get(url, headers = browserHeaders).text)

    private fun Card.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        return newMovieSearchResponse(title?.let { Jsoup.parse(it).text() } ?: return null, seriesUrl(slug), TvType.NSFW) {
            posterUrl = thumbnailUrl
        }
    }

    private fun FeedEpisode.toSearchResponse(): SearchResponse? {
        val slug = mangaSlug ?: return null
        return newMovieSearchResponse(mangaTitle?.let { Jsoup.parse(it).text() } ?: return null, seriesUrl(slug), TvType.NSFW) {
            posterUrl = coverUrl ?: thumbnailUrl
        }
    }

    private fun WpManga.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        return newMovieSearchResponse(title?.rendered?.let { Jsoup.parse(it).text() } ?: return null, seriesUrl(slug), TvType.NSFW) {
            posterUrl = meta?.thumbnail?.let { imageUrl(it) }
        }
    }

    private fun Chapter.toData() = EpisodeData(videoUuid, indexableContentUrl, dataSlug).toJson()

    /**
     * `chapter_thumbnail` is stored half-built: Octopus imports hold `http://YYYY/MM/DD/<name>/`
     * (a coverlanyvd.org storage path), legacy ones `/hls/hentaihaven/<data_slug>/s_poster.jpg`.
     */
    private fun Chapter.thumbnail(): String? {
        indexableThumbnailUrl?.takeIf { it.startsWith("https://") }?.let { return it }
        val raw = chapterThumbnail ?: return null
        Regex("""^https?://(\d{4}/\d{2}/\d{2}/[^/]+)/?$""").find(raw)?.let {
            return "https://coverlanyvd.org/storage/${it.groupValues[1]}/s_thumbnail.webp"
        }
        if (raw.startsWith("/hls/hentaihaven/")) return "https://himg.nl/images/hh/" + raw.removePrefix("/hls/hentaihaven/")
        return raw.takeIf { it.startsWith("https://") }
    }

    private fun imageUrl(path: String) =
        if (path.startsWith("http")) path else imageHost + path.trimStart('/').replace(" ", "%20")

    private fun seriesUrl(slug: String) = "$mainUrl/watch/$slug/"

    private fun slugOf(url: String): String? =
        url.substringAfter("/watch/", "").substringBefore('?').substringBefore('#').substringBefore('/').ifEmpty { null }

    data class Pagination(@JsonProperty("has_next_page") val hasNext: Boolean? = null)

    data class Card(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
    )

    data class CardFeed(
        @JsonProperty("items") val items: List<Card>? = null,
        @JsonProperty("pagination") val pagination: Pagination? = null,
    )

    data class FeedEpisode(
        @JsonProperty("manga_slug") val mangaSlug: String? = null,
        @JsonProperty("manga_title") val mangaTitle: String? = null,
        @JsonProperty("cover_url") val coverUrl: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
    )

    data class EpisodeFeed(
        @JsonProperty("items") val items: List<FeedEpisode>? = null,
        @JsonProperty("pagination") val pagination: Pagination? = null,
    )

    data class Rendered(@JsonProperty("rendered") val rendered: String? = null)

    data class WpMeta(@JsonProperty("vraven_remote_thumbnail") val thumbnail: String? = null)

    data class WpManga(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("title") val title: Rendered? = null,
        @JsonProperty("content") val content: Rendered? = null,
        @JsonProperty("meta") val meta: WpMeta? = null,
        @JsonProperty("wp-manga-genre") val genres: List<Int>? = null,
        @JsonProperty("wp-manga-release") val releases: List<Int>? = null,
        @JsonProperty("wp-manga-author") val authors: List<Int>? = null,
    )

    data class BrowseFeed(
        @JsonProperty("data") val data: List<WpManga>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
    )

    data class Term(@JsonProperty("name") val name: String? = null)

    data class Chapter(
        @JsonProperty("chapter_name") val chapterName: String? = null,
        @JsonProperty("episode_slug") val episodeSlug: String? = null,
        @JsonProperty("chapter_thumbnail") val chapterThumbnail: String? = null,
        @JsonProperty("is_deleted") val isDeleted: Boolean? = null,
        @JsonProperty("video_uuid") val videoUuid: String? = null,
        @JsonProperty("data_slug") val dataSlug: String? = null,
        @JsonProperty("indexable_content_url") val indexableContentUrl: String? = null,
        @JsonProperty("indexable_thumbnail_url") val indexableThumbnailUrl: String? = null,
        @JsonProperty("chapter_date") val chapterDate: String? = null,
        @JsonProperty("duration_seconds") val durationSeconds: Int? = null,
    ) {
        val number: Int? get() = episodeSlug?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() }
        val hasSource: Boolean
            get() = !videoUuid.isNullOrBlank() || !indexableContentUrl.isNullOrBlank() || !dataSlug.isNullOrBlank()
    }

    data class EpisodeData(
        @JsonProperty("uuid") val uuid: String? = null,
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("slug") val slug: String? = null,
    )

    data class StreamSource(@JsonProperty("src") val src: String? = null)

    data class StreamData(
        @JsonProperty("sources") val sources: List<StreamSource>? = null,
        @JsonProperty("fallbackSources") val fallbackSources: List<StreamSource>? = null,
    )

    data class StreamResponse(@JsonProperty("data") val data: StreamData? = null)

    companion object {
        private const val PAGE_SIZE = 24
        private const val PROBE_TIMEOUT_S = 10L
        private const val OCTOPUS_HOST = "https://octopusmanifest.org"
        private val OCTOPUS_UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)
    }
}
