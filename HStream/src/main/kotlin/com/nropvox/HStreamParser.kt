package com.nropvox

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parsing for hstream.moe pages (Laravel + Livewire, server-rendered). Nothing here touches the network or the
 * Cloudstream library.
 */
object HStreamParser {
    /** One series, collapsed from the per-episode cards of a list. */
    data class Card(val slug: String, val title: String, val poster: String?)

    data class Episode(val url: String, val number: Int, val thumbnail: String?)

    data class Series(
        val title: String,
        val altTitle: String?,
        val cover: String?,
        val plot: String?,
        val year: Int?,
        val studio: String?,
        val tags: List<String>,
        val episodes: List<Episode>,
    )

    /** Episode pages are `/hentai/<series>-<n>`; a series slug may itself end in digits, so never strip a series URL. */
    private val episodeSlug = Regex("""^(.+)-(\d+)$""")
    // `Title - 1` (card alt / h3)
    private val episodeSuffix = Regex("""\s+-\s+\d+$""")
    // `/images/hentai/<series>/gallery-ep-<n>-0-thumbnail.webp` or `/cover-ep-<n>.webp`
    private val imageEpisode = Regex("""/images/hentai/([^/]+)/(?:gallery|cover)-ep-(\d+)""")
    private val episodesHeading = Regex("""Episodes \(\d+\)""")

    fun slug(url: String): String? =
        url.substringAfter("/hentai/", "").substringBefore('?').substringBefore('#').trim('/').ifEmpty { null }

    /** Cards always link an episode, so the trailing `-<n>` is safe to strip here (and only here). */
    fun seriesOfEpisode(episodeSlug: String): String? = this.episodeSlug.matchEntire(episodeSlug)?.groupValues?.get(1)

    fun episodeNumber(episodeSlug: String): Int? = this.episodeSlug.matchEntire(episodeSlug)?.groupValues?.get(2)?.toIntOrNull()

    /**
     * Episode cards in [root], one per series in page order. The default thumbnail view is turned into the 11:16
     * `cover-ep-<n>.webp` the poster view shows, which exists for every episode.
     */
    fun cards(root: Element, base: String): List<Card> =
        root.select("a[href*=/hentai/]:has(img)").mapNotNull { a ->
            val episode = slug(a.attr("href")) ?: return@mapNotNull null
            val series = seriesOfEpisode(episode) ?: return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").ifBlank { a.selectFirst("h3")?.text().orEmpty() }.replace(episodeSuffix, "").trim()
            if (title.isEmpty()) return@mapNotNull null
            Card(series, title, cover(img.attr("src"), base))
        }.distinctBy { it.slug }

    fun cover(src: String, base: String): String? {
        val match = imageEpisode.find(src) ?: return src.ifBlank { null }?.let { absolute(it, base) }
        val (series, number) = match.destructured
        return "$base/images/hentai/$series/cover-ep-$number.webp"
    }

    /** Livewire paginator; the last page renders no `nextPage` button. */
    fun hasNextPage(doc: Document): Boolean = doc.selectFirst("[wire:click=nextPage]") != null

    /** Home tab panel, e.g. `tabs-trending`. */
    fun homeTab(doc: Document, id: String): Element? = doc.getElementById(id)

    /** `<input id="e_id">` only exists on episode pages. */
    fun episodeId(doc: Document): String? = doc.selectFirst("input#e_id")?.attr("value")?.ifBlank { null }

    /** An episode page's title links to its series page. */
    fun seriesUrl(doc: Document, base: String): String? =
        doc.selectFirst("h1 a[href*=/hentai/]")?.attr("href")?.ifBlank { null }?.let { absolute(it, base) }

    fun series(doc: Document, seriesSlug: String, base: String): Series? {
        val title = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null } ?: return null
        val episodes = episodeRoot(doc).select("a[href*=/hentai/$seriesSlug-]").mapNotNull { a ->
            val episode = slug(a.attr("href")) ?: return@mapNotNull null
            if (seriesOfEpisode(episode) != seriesSlug) return@mapNotNull null
            val number = episodeNumber(episode) ?: return@mapNotNull null
            Episode("$base/hentai/$episode", number, a.selectFirst("img")?.attr("src")?.ifBlank { null }?.let { absolute(it, base) })
        }.distinctBy { it.number }.sortedBy { it.number }
        val released = doc.selectFirst("div:has(> i.fa-calendar)")?.ownText()?.trim()
        return Series(
            title = title,
            altTitle = doc.selectFirst("h1 + p")?.text()?.trim()?.ifEmpty { null },
            cover = doc.selectFirst("img[src*=/cover-ep-]")?.attr("src")?.let { absolute(it, base) }
                ?: "$base/images/hentai/$seriesSlug/cover-ep-1.webp",
            plot = section(doc, "Description")?.selectFirst("p")?.text()?.trim()?.ifEmpty { null },
            year = released?.take(4)?.toIntOrNull(),
            studio = doc.selectFirst("a[href*=studios%5B0%5D=]")?.text()?.trim()?.ifEmpty { null },
            tags = section(doc, "Genres")?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            episodes = episodes,
        )
    }

    /** The `Episodes (N)` box; the rest of the page also links other series of the studio. */
    private fun episodeRoot(doc: Document): Element =
        doc.getAllElements().firstOrNull { it.ownText().trim().matches(episodesHeading) }?.parent() ?: doc

    private fun section(doc: Document, heading: String): Element? =
        doc.select("h2").firstOrNull { it.ownText().trim() == heading }?.parent()

    private fun absolute(src: String, base: String) = if (src.startsWith("http")) src else "$base/${src.trimStart('/')}"
}
