package com.nropvox

import java.net.URLEncoder

/** Pure helpers, kept apart from [Hentaverse] so they can be unit tested without the app. */
object HentaverseParser {
    const val CDN = "https://cdn.hentaverse.com"

    private val renditionsDir = Regex("""/renditions/?$""", RegexOption.IGNORE_CASE)
    private val streamHeight = Regex("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)""")

    /** API paths are relative (`uploads/series/x.webp`); a few may already be absolute. */
    fun cdnUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return "$CDN/${path.trimStart('/')}"
    }

    /**
     * The CDN answers image requests in 3–60 s (no edge cache), long enough for the app's image loader to time out.
     * The site's own Next.js image proxy serves the same files, cached and resized, in under a second.
     */
    private fun proxiedImage(path: String?, width: Int): String? =
        cdnUrl(path)?.let { "https://hentaverse.com/_next/image?url=${URLEncoder.encode(it, "UTF-8")}&w=$width&q=75" }

    fun posterUrl(path: String?) = proxiedImage(path, 384)

    fun thumbnailUrl(path: String?) = proxiedImage(path, 640)

    /** `/hentai/{seriesSlug}` or `/video/{videoSlug}` to (kind, slug). */
    fun parsePageUrl(url: String): Pair<String, String>? {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#').trim('/')
        val kind = path.substringBefore('/')
        val slug = path.substringAfter('/', "").substringBefore('/')
        if (slug.isEmpty() || (kind != "hentai" && kind != "video")) return null
        return kind to slug
    }

    /** The player's rule: `mapped` videos (and legacy ones stored under `/renditions`) have an HLS master. */
    fun hasHls(video: HvVideo): Boolean = video.videoType?.let { it == "mapped" }
        ?: (video.videoPath.isNullOrBlank() || renditionsDir.containsMatchIn(video.videoPath))

    fun masterUrl(videoId: String) = "$CDN/hls/$videoId/master.m3u8"

    /** Folder holding `{height}p.mp4` files, or null when the video is a single file. */
    fun renditionsBase(video: HvVideo): String? {
        val dir = video.mp4Path?.substringBeforeLast('/')?.takeIf { renditionsDir.containsMatchIn("$it/") }
            ?: video.videoPath?.takeIf { renditionsDir.containsMatchIn(it) }
            ?: return null
        return cdnUrl(dir.trimEnd('/'))
    }

    /** The one file to play when there are no renditions to choose from. */
    fun singleMp4(video: HvVideo): String? = cdnUrl(video.mp4Path)
        ?: video.videoPath?.let { path -> cdnUrl(if (renditionsDir.containsMatchIn(path)) "${path.trimEnd('/')}/1080p.mp4" else path) }

    /** Variant heights in a master playlist, highest first. The MP4 renditions are named after them. */
    fun masterHeights(master: String): List<Int> =
        streamHeight.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sortedDescending().toList()

    /** `00:28:25` to minutes. */
    fun minutes(duration: String?): Int? {
        val parts = duration?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
        val seconds = parts.fold(0) { acc, part -> acc * 60 + part }
        return if (seconds > 0) (seconds + 30) / 60 else null
    }
}
