package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URI

object PornhubParser {
    data class Card(val title: String, val url: String, val poster: String?)

    data class Media(val url: String, val quality: Int?, val format: String)

    data class Detail(
        val title: String,
        val poster: String?,
        val plot: String?,
        val media: List<Media>,
    )

    private val pagePattern = Regex("""[?&]page=(\d+)""")
    private val flashvarsPattern = Regex("""var\s+flashvars_\d+\s*=\s*(\{.*?\});""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val mapper = ObjectMapper()

    fun cards(doc: Document, base: String): List<Card> = doc.select("li.pcVideoListItem").mapNotNull { item ->
        val link = item.selectFirst("a[href*=/view_video.php?viewkey=]") ?: return@mapNotNull null
        val image = item.selectFirst("img")
        val title = link.attr("title").ifBlank { link.text() }.ifBlank { image?.attr("alt").orEmpty() }.trim()
        if (title.isEmpty()) return@mapNotNull null
        Card(title, absolute(link.attr("href"), base), image?.posterUrl())
    }.distinctBy { it.url }

    fun hasNextPage(doc: Document, page: Int): Boolean = doc.select("a[href*='page=']").any { link ->
        pagePattern.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { it > page } == true
    }

    fun detail(doc: Document): Detail? {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.title().substringBefore(" - Pornhub.com").trim().ifBlank { null }
            ?: return null
        val media = flashvars(doc)?.get("mediaDefinitions")?.mapNotNull { node ->
            val url = node.get("videoUrl")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Media(url, node.get("height")?.asInt(), node.get("format")?.asText().orEmpty())
        }.orEmpty()
        return Detail(
            title = title,
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null },
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null },
            media = media,
        )
    }

    private fun flashvars(doc: Document): JsonNode? {
        val script = doc.select("script").firstNotNullOfOrNull { flashvarsPattern.find(it.data())?.groupValues?.get(1) }
            ?: return null
        return runCatching { mapper.readTree(script) }.getOrNull()
    }

    private fun org.jsoup.nodes.Element.posterUrl(): String? =
        listOf("src", "data-image", "data-src", "data-original", "data-thumb")
            .firstNotNullOfOrNull { attribute ->
                attr(attribute).takeIf { value ->
                    value.isNotBlank() && !value.startsWith("data:image") &&
                        !value.contains("placeholder", ignoreCase = true)
                }
            }

    private fun absolute(path: String, base: String): String =
        runCatching { URI(base).resolve(path).toString() }.getOrDefault(path)
}
