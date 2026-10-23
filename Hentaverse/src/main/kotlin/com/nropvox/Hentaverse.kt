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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nropvox.HentaverseParser.cdnUrl
import com.nropvox.HentaverseParser.posterUrl
import com.nropvox.HentaverseParser.thumbnailUrl
import java.net.URLEncoder

/**
 * hentaverse.com is a Next.js skin over a public JSON API (`apiv2.hentaverse.com/api/v1/content`).
 * Every video has an HLS master on the CDN and `{height}p.mp4` renditions next to it; nothing needs headers.
 */
class Hentaverse : MainAPI() {
    override var mainUrl = "https://hentaverse.com"
    override var name = "Hentaverse"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val api = "https://apiv2.hentaverse.com/api/v1/content"

    // `series?…` rows list series; `videos?…` rows list single episodes with landscape thumbnails.
    override val mainPage = mainPageOf(
        "series?sort=trending&period=week" to "Trending This Week",
        "videos?sort=latest" to "Latest Episodes",
        "videos?sort=trending&viewsPeriod=day" to "Hot Today",
        "series?sort=latest" to "Recently Added",
        "series?sort=popular&period=all" to "Most Popular",
        "series?sort=latest&seriesType=TV" to "TV Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$api/${request.data}&page=$page&limit=$PAGE_SIZE"
        val text = app.get(url).text
        // The API stops at 200 results whatever the filter; `hasMore` turns false there.
        return if (request.data.startsWith("videos")) {
            val list = tryParseJson<HvVideoList>(text)
            val items = list?.data?.items.orEmpty().mapNotNull { it.toSearchResponse() }
            newHomePageResponse(HomePageList(request.name, items, true), hasNext = list?.pagination?.hasMore == true)
        } else {
            val list = tryParseJson<HvSeriesList>(text)
            val items = list?.data?.items.orEmpty().mapNotNull { it.toSearchResponse() }
            newHomePageResponse(HomePageList(request.name, items), hasNext = list?.pagination?.hasMore == true)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val data = searchSeries(query, page, PAGE_SIZE)
        val totalPages = data?.pagination?.totalPages ?: 0
        return newSearchResponseList(data.toSearchResponses(), hasNext = page < totalPages)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        if (query.isBlank()) emptyList() else searchSeries(query, 1, QUICK_SEARCH_SIZE).toSearchResponses()

    private suspend fun searchSeries(query: String, page: Int, limit: Int): HvSearchData? =
        tryParseJson<HvSearch>(app.get("$api/search/series?q=${encode(query.trim())}&page=$page&limit=$limit").text)?.data

    // The search index also holds catalogue stubs that have no videos yet.
    private fun HvSearchData?.toSearchResponses(): List<SearchResponse> =
        this?.series.orEmpty().filter { (it.videoCount ?: 0) > 0 }.mapNotNull { it.toSearchResponse() }

    override suspend fun load(url: String): LoadResponse {
        val (kind, slug) = HentaverseParser.parsePageUrl(url) ?: throw ErrorLoadingException("Not a $name URL: $url")
        // An episode card opens its series, so every episode of a series shares one entry.
        val seriesSlug = if (kind == "hentai") slug else {
            val video = video(slug)
            video.series?.slug ?: return loadSingleVideo(video)
        }
        val series = tryParseJson<HvSeriesDetail>(app.get("$api/series/$seriesSlug").text)?.data
            ?: throw ErrorLoadingException("Could not load $name series $seriesSlug")
        val title = series.name ?: throw ErrorLoadingException("Could not load $name series $seriesSlug")
        val videos = series.videos.orEmpty().filter { !it.slug.isNullOrBlank() }.sortedBy { it.episode ?: Int.MAX_VALUE }
        val related = series.id?.let { id ->
            runCatching {
                tryParseJson<HvRelated>(app.get("$api/series/related/$id?limit=$RELATED_SIZE").text)?.data?.relatedSeries
            }.onFailure { Log.w(name, "Related for $seriesSlug: $it") }.getOrNull()
        }.orEmpty().mapNotNull { it.toSearchResponse() }

        fun LoadResponse.fill() {
            // About a fifth of the series have no poster; the first episode's thumbnail stands in.
            posterUrl = posterUrl(series.image) ?: thumbnailUrl(videos.firstOrNull()?.thumbnail)
            plot = series.description
            year = series.year
            tags = (series.categories.orEmpty().mapNotNull { it.name } + series.studios.orEmpty().mapNotNull { it.name }).distinct()
            recommendations = related
        }

        val seriesUrl = "$mainUrl/hentai/${series.slug ?: seriesSlug}"
        if (videos.size == 1) {
            val only = videos.single()
            return newMovieLoadResponse(title, seriesUrl, TvType.NSFW, videoUrl(only.slug!!)) {
                fill()
                duration = HentaverseParser.minutes(only.duration)
            }
        }
        val episodes = videos.mapIndexed { index, video ->
            newEpisode(videoUrl(video.slug!!)) {
                name = video.title
                episode = video.episode ?: (index + 1)
                posterUrl = thumbnailUrl(video.thumbnail)
                runTime = HentaverseParser.minutes(video.duration)
            }
        }
        return newTvSeriesLoadResponse(title, seriesUrl, TvType.NSFW, episodes) { fill() }
    }

    /** A video without a series; not seen on the site, but the API allows `series: null`. */
    private suspend fun loadSingleVideo(video: HvVideo): LoadResponse {
        val slug = video.slug ?: throw ErrorLoadingException("Could not load $name video")
        return newMovieLoadResponse(video.title ?: slug, videoUrl(slug), TvType.NSFW, videoUrl(slug)) {
            posterUrl = thumbnailUrl(video.thumbnail)
            plot = video.description
            duration = HentaverseParser.minutes(video.duration)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Episode data is the `/video/{slug}` page URL; Cloudstream makes any relative data absolute.
        val slug = HentaverseParser.parsePageUrl(data)?.second ?: data.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val video = video(slug)

        for (subtitle in video.subtitles.orEmpty()) {
            val url = cdnUrl(subtitle.filePath) ?: continue
            val language = subtitle.language ?: "Unknown"
            subtitleCallback(newSubtitleFile(if (subtitle.isAIGenerated == true) "$language (AI)" else language, url))
        }

        val id = video.id
        if (id == null || !HentaverseParser.hasHls(video)) {
            val mp4 = HentaverseParser.singleMp4(video) ?: return false
            callback(newExtractorLink(name, "$name MP4", mp4, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
            return true
        }

        val master = HentaverseParser.masterUrl(id)
        // The master lists the heights, and the MP4 renditions are named after them (`1080p.mp4` …).
        val heights = runCatching { HentaverseParser.masterHeights(app.get(master).text) }
            .onFailure { Log.w(name, "Master for $slug: $it") }.getOrDefault(emptyList())
        callback(newExtractorLink(name, "$name HLS", master, ExtractorLinkType.M3U8) {
            quality = heights.firstOrNull() ?: Qualities.Unknown.value
        })
        // About 40% of videos only have HLS: their renditions folder answers 404 for every height, so probe the top one.
        val base = HentaverseParser.renditionsBase(video)
        if (base != null && heights.isNotEmpty()) {
            if (exists("$base/${heights.first()}p.mp4")) {
                for (height in heights) {
                    callback(newExtractorLink(name, "$name MP4", "$base/${height}p.mp4", ExtractorLinkType.VIDEO) {
                        quality = height
                    })
                }
            }
        } else {
            HentaverseParser.singleMp4(video)?.takeIf { exists(it) }?.let { mp4 ->
                callback(newExtractorLink(name, "$name MP4", mp4, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
            }
        }
        return true
    }

    private suspend fun exists(url: String): Boolean = runCatching {
        app.get(url, headers = mapOf("Range" to "bytes=0-0"), timeout = PROBE_TIMEOUT_S).code in 200..299
    }.getOrDefault(false)

    private suspend fun video(slug: String): HvVideo =
        tryParseJson<HvVideoDetail>(app.get("$api/videos/${encode(slug)}").text)?.data
            ?: throw ErrorLoadingException("Could not load $name video $slug")

    private fun HvSeries.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        return newMovieSearchResponse(name ?: return null, "$mainUrl/hentai/$slug", TvType.NSFW) {
            posterUrl = posterUrl(image)
            year = this@toSearchResponse.year
        }
    }

    private fun HvVideo.toSearchResponse(): SearchResponse? {
        val slug = slug ?: return null
        return newMovieSearchResponse(title ?: return null, videoUrl(slug), TvType.NSFW) {
            posterUrl = thumbnailUrl(thumbnail)
        }
    }

    private fun videoUrl(slug: String) = "$mainUrl/video/$slug"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val PAGE_SIZE = 20
        private const val QUICK_SEARCH_SIZE = 10
        private const val RELATED_SIZE = 15
        private const val PROBE_TIMEOUT_S = 15L
    }
}
