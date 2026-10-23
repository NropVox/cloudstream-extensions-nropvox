package com.nropvox

import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nropvox.KayoanimeParser.DriveFile
import com.nropvox.KayoanimeParser.DriveRef
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * kayoanime.com is a WordPress blog: the catalog, search and post bodies come from the stock REST API, and a post is
 * a whole series whose Drive buttons are the only episode source. Drive is read through the user's own proxy Worker
 * (`/resolve` lists a folder, `/dl/<id>` streams a file with Range support), which is also what makes the private
 * Google-Group folders readable. The proxy URL and API key are plugin settings, so no secret ships in the build.
 */
class Kayoanime(private val sharedPref: SharedPreferences? = null) : MainAPI() {
    override var mainUrl = "https://kayoanime.com"
    override var name = "Kayoanime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val api = "$mainUrl/wp-json/wp/v2"

    // The value is a WP category id; "" is every post. Newest first.
    override val mainPage = mainPageOf(
        "" to "Latest",
        "3" to "Ongoing Anime",
        "4" to "Anime Series",
        "2" to "Anime Movies",
        "31" to "Chinese Anime",
    )

    private val proxyUrl: String
        get() = sharedPref?.getString(PROXY_URL_KEY, null)?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_PROXY_URL
    private val proxyKey: String
        get() = sharedPref?.getString(PROXY_KEY_KEY, null)?.trim().orEmpty()

    private val posters = ConcurrentHashMap<Int, String>()
    private val proxyLimit = Semaphore(PROXY_PARALLEL)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = if (request.data.isEmpty()) "" else "&categories=${request.data}"
        val (posts, totalPages) = postsPage("per_page=$PAGE_SIZE&page=$page$category")
        return newHomePageResponse(request.name, toSearchResponses(posts), hasNext = page < totalPages)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val (posts, totalPages) = postsPage("per_page=$PAGE_SIZE&page=$page&orderby=relevance&search=${encode(query.trim())}")
        return newSearchResponseList(toSearchResponses(posts), hasNext = page < totalPages)
    }

    /** One page of posts and the page count. Asking past the last page is an HTTP 400 object, not a list. */
    private suspend fun postsPage(query: String): Pair<List<KaPost>, Int> {
        val res = app.get("$api/posts?$query&_fields=id,title,categories,featured_media")
        val posts = tryParseJson<List<KaPost>>(res.text).orEmpty()
        return posts to (res.headers["X-WP-TotalPages"]?.toIntOrNull() ?: 0)
    }

    private suspend fun toSearchResponses(posts: List<KaPost>): List<SearchResponse> {
        loadPosters(posts.mapNotNull { it.featured_media })
        return posts.mapNotNull { post ->
            val id = post.id ?: return@mapNotNull null
            val title = post.title?.rendered?.let(KayoanimeParser::cleanTitle) ?: return@mapNotNull null
            newAnimeSearchResponse(title, postUrl(id), if (isMovie(post.categories)) TvType.AnimeMovie else TvType.Anime) {
                posterUrl = post.featured_media?.let { posters[it] }
            }
        }
    }

    /** Posters live behind `featured_media` ids; one `include=` request resolves a whole page of them. */
    private suspend fun loadPosters(mediaIds: List<Int>) {
        val missing = mediaIds.filter { !posters.containsKey(it) }.distinct()
        if (missing.isEmpty()) return
        val media = runCatching {
            tryParseJson<List<KaMedia>>(app.get("$api/media?include=${missing.joinToString(",")}&per_page=100&_fields=id,source_url").text)
        }.onFailure { Log.w(name, "Posters: $it") }.getOrNull().orEmpty()
        media.forEach { if (it.id != null && it.source_url != null) posters[it.id] = it.source_url }
    }

    // A movie page can also carry the series category; those hold seasons and movies together, so they stay series.
    private fun isMovie(categories: List<Int>?) = categories.orEmpty().let { MOVIE_CATEGORY in it && SERIES_CATEGORY !in it }

    private fun postUrl(id: Int) = "$mainUrl/?p=$id"

    override suspend fun load(url: String): LoadResponse {
        if (proxyKey.isEmpty()) throw ErrorLoadingException(NO_KEY_MESSAGE)
        val post = loadPost(url) ?: throw ErrorLoadingException("Could not load $url")
        val id = post.id ?: throw ErrorLoadingException("Could not load $url")
        val html = post.content?.rendered.orEmpty()
        val title = KayoanimeParser.cleanTitle(post.title?.rendered.orEmpty())

        val refs = KayoanimeParser.driveRefs(html)
        if (refs.isEmpty()) throw ErrorLoadingException("$title has no Google Drive links (Vimeo/Mega only)")
        val files = refs.amap { listFiles(it) }.flatten().distinctBy { it.id }
        val entries = KayoanimeParser.entries(files)
        if (entries.isEmpty()) {
            throw ErrorLoadingException("No playable files for $title: its Drive folders are missing, empty or only hold archives")
        }
        val details = KayoanimeParser.details(html)
        loadPosters(listOfNotNull(post.featured_media))

        fun LoadResponse.fill() {
            posterUrl = post.featured_media?.let { posters[it] }
            plot = details.plot
            tags = details.tags
            year = details.year
            duration = details.minutes
        }

        val pageUrl = postUrl(id)
        if (isMovie(post.categories) && entries.size <= MOVIE_MAX_FILES) {
            val movieFiles = entries.flatMap { it.files }
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, KayoanimeParser.encodeSources(movieFiles)) { fill() }
        }
        val episodes = entries.map { entry ->
            newEpisode(KayoanimeParser.encodeSources(entry.files)) {
                name = entry.title
                season = entry.season
                episode = entry.episode
            }
        }
        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            fill()
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private suspend fun loadPost(url: String): KaPost? {
        val fields = "_fields=id,title,content,categories,featured_media"
        KayoanimeParser.postId(url)?.let { return tryParseJson<KaPost>(app.get("$api/posts/$it?$fields").text) }
        val slug = KayoanimeParser.slugOf(url) ?: return null
        return tryParseJson<List<KaPost>>(app.get("$api/posts?slug=${encode(slug)}&$fields").text)?.firstOrNull()
    }

    // ---- Drive through the proxy ---------------------------------------------------------------------------

    /** Every file under a button, subfolders included. A dead or private-and-unshared folder yields nothing. */
    private suspend fun listFiles(ref: DriveRef, depth: Int = 0, parents: List<String> = emptyList()): List<DriveFile> {
        val node = resolve(ref.id) ?: return emptyList()
        if (node.type != "folder") return listOf(DriveFile(ref.id, node.name.orEmpty(), ref.label, parents))
        // Folder names often hold the season the file names leave out, so files remember them (nearest first).
        val folders = listOfNotNull(node.name) + parents
        val children = node.children.orEmpty()
        val files = children.filter { it.type != "folder" }.mapNotNull { child ->
            DriveFile(child.id ?: return@mapNotNull null, child.name.orEmpty(), ref.label, folders)
        }
        if (depth >= MAX_FOLDER_DEPTH) return files
        // One request per folder: the proxy's own `recursive=1` can exceed a Worker's subrequest limit.
        val nested = children.filter { it.type == "folder" && it.id != null }
            .amap { listFiles(DriveRef(it.id!!, true, ref.label), depth + 1, folders) }.flatten()
        return files + nested
    }

    private suspend fun resolve(driveId: String): KaNode? = proxyLimit.withPermit {
        repeat(2) { attempt ->
            val res = runCatching {
                app.get("$proxyUrl/resolve?url=${encode(driveId)}", headers = mapOf("X-Key" to proxyKey), timeout = 60)
            }.onFailure { Log.w(name, "Proxy resolve $driveId: $it") }.getOrNull()
            when (res?.code) {
                200 -> return@withPermit tryParseJson<KaNode>(res.text)
                // 401: wrong key. 502: Drive answered 404/403 for this id. Neither gets better by asking again.
                401, 502 -> {
                    Log.w(name, "Proxy resolve $driveId: HTTP ${res.code}")
                    return@withPermit null
                }
                else -> Log.w(name, "Proxy resolve $driveId: HTTP ${res?.code} (attempt ${attempt + 1})")
            }
        }
        null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val files = KayoanimeParser.decodeSources(data)
        if (files.isEmpty() || proxyKey.isEmpty()) {
            if (proxyKey.isEmpty()) Log.w(name, NO_KEY_MESSAGE)
            return false
        }
        files.forEach { file ->
            val quality = KayoanimeParser.quality(file.name, file.label)
            // The button text only earns a place when copies need telling apart, and not if it just repeats the quality.
            val parts = (listOfNotNull(quality?.let { "${it}p" }) + KayoanimeParser.tags(file.name) +
                listOfNotNull(KayoanimeParser.shortLabel(file.label).takeIf { files.size > 1 && it.isNotEmpty() }))
                .distinctBy { it.lowercase() }
            val label = if (parts.isEmpty()) name else "$name ${parts.joinToString(" · ")}"
            // The key rides in the query so external players and downloads, which cannot add headers, work too.
            val url = "$proxyUrl/dl/${file.id}?key=${encode(proxyKey)}"
            callback(newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                this.quality = quality ?: Qualities.Unknown.value
            })
        }
        return true
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val PREF_FILE = "Kayoanime"
        const val PROXY_URL_KEY = "proxy_url"
        const val PROXY_KEY_KEY = "proxy_key"
        const val DEFAULT_PROXY_URL = "https://gdrive-proxy.istarvin.workers.dev"
        private const val NO_KEY_MESSAGE = "Kayoanime plays files through your Google Drive proxy. Open the plugin settings and enter its API key."
        private const val PAGE_SIZE = 20
        private const val PROXY_PARALLEL = 4
        private const val MAX_FOLDER_DEPTH = 3
        private const val MOVIE_MAX_FILES = 3
        private const val SERIES_CATEGORY = 4
        private const val MOVIE_CATEGORY = 2
    }
}
