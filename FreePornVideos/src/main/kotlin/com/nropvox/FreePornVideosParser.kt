package com.nropvox

import org.jsoup.nodes.Document
import java.net.URI

object FreePornVideosParser {
    data class Card(val title: String, val url: String, val poster: String?)
    data class Media(val url: String, val quality: Int?)
    data class Detail(val title: String, val poster: String?, val plot: String?, val media: List<Media>)

    fun cards(doc: Document, base: String): List<Card> = doc.select(".list-videos .item").mapNotNull { item ->
        val link = item.selectFirst("a[href*=/videos/]") ?: return@mapNotNull null
        val title = link.attr("title").ifBlank { link.text() }.trim()
        if (title.isBlank()) return@mapNotNull null
        val image = item.selectFirst("img")
        Card(title, absolute(link.attr("href"), base), image?.posterUrl())
    }.distinctBy { it.url }

    fun hasNextPage(doc: Document, page: Int): Boolean = doc.select("a[href]").any { link ->
        val path = URI(link.attr("href")).path.orEmpty().trimEnd('/')
        path.substringAfterLast('/').toIntOrNull()?.let { it > page } == true
    }

    fun detail(doc: Document): Detail? {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val media = doc.select("video source").mapNotNull { source ->
            val url = source.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Media(url, Regex("(\\d+)").find(source.attr("label"))?.groupValues?.get(1)?.toIntOrNull())
        }
        return Detail(
            title = title,
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null },
            media = media,
        )
    }

    private fun org.jsoup.nodes.Element.posterUrl(): String? =
        listOf("data-src", "src").firstNotNullOfOrNull { attribute ->
            attr(attribute).takeIf { it.isNotBlank() && !it.startsWith("data:image") }
        }

    private fun absolute(path: String, base: String): String =
        runCatching { URI(base).resolve(path).toString() }.getOrDefault(path)
}