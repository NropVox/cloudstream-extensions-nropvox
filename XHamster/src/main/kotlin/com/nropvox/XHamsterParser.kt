package com.nropvox

import org.jsoup.nodes.Document
import java.net.URI

object XHamsterParser {
    data class Card(val title: String, val url: String, val poster: String?)
    data class Detail(
        val title: String,
        val poster: String?,
        val plot: String?,
        val media: List<Media>,
        val hlsMaster: String? = null,
    )
    data class Media(val url: String, val quality: Int?)

    fun cards(doc: Document, base: String): List<Card> = doc.select(".video-thumb").mapNotNull { item ->
        val link = item.selectFirst("[data-role=thumb-link][href]") ?: return@mapNotNull null
        val title = link.attr("aria-label").ifBlank {
            item.selectFirst("img[alt]")?.attr("alt").orEmpty()
        }.trim()
        if (title.isBlank()) return@mapNotNull null
        val poster = item.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
        Card(title, absolute(link.attr("href"), base), poster)
    }.distinctBy { it.url }

    fun hasNextPage(doc: Document, page: Int, base: String): Boolean = doc.select("a[href]").any { link ->
        val path = runCatching { URI(absolute(link.attr("href"), base)).path.orEmpty() }.getOrDefault("")
        path.trim('/').toIntOrNull()?.let { it > page } == true
    }

    // The player state lists one MP4 per quality ("144p" .. "720p") inside script data, with
    // slashes escaped as \/. The <video src> inside <noscript> only carries the 480p fallback.
    private val mp4Quality = Regex("""https://[^"\s\\<>]+?/(\d{3,4})p\.h264\.mp4""")

    // Multi-variant HLS master. Its variants are named "<height>p.<codec>.mp4.m3u8" next to it.
    private const val MASTER_SUFFIX = "_TPL_.av1.mp4.m3u8"
    private val hlsMasterUrl = Regex("""https://[^"\s\\<>]+?/_TPL_\.av1\.mp4\.m3u8""")
    private val resolutionHeight = Regex("""RESOLUTION=\d+x(\d+)""")

    fun detail(doc: Document): Detail? {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val html = doc.html().replace("\\/", "/")
        val fromState = mp4Quality.findAll(html).map {
            Media(it.value, it.groupValues[1].toIntOrNull())
        }
        val fromVideoTag = doc.select("video[src]").mapNotNull { video ->
            val url = video.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Media(url, Regex("(\\d+)p(?:\\.|$)").find(url)?.groupValues?.get(1)?.toIntOrNull())
        }
        val media = (fromState + fromVideoTag).distinctBy { it.quality ?: it.url }.sortedBy { it.quality ?: 0 }.toList()
        return Detail(
            title = title,
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null },
            media = media,
            hlsMaster = hlsMasterUrl.find(html)?.value,
        )
    }

    /** Heights advertised by a master playlist, e.g. [144, 240, 480, 720, 1080, 2160]. */
    fun hlsHeights(master: String): List<Int> =
        resolutionHeight.findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()

    /** H.264 MPEG-TS variant: the form the phone's decoder is most likely to handle. */
    fun h264Variant(master: String, height: Int): String =
        master.replace(MASTER_SUFFIX, "${height}p.h264.mp4.m3u8")

    /** AV1 fMP4 variant, used only when the H.264 one is missing. */
    fun av1Variant(master: String, height: Int): String =
        master.replace("_TPL_", "${height}p")

    private fun absolute(path: String, base: String): String =
        runCatching { URI(base).resolve(path).toString() }.getOrDefault(path)
}
