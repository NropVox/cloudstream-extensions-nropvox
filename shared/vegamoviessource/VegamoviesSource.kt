package com.nropvox

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nropvox.VegamoviesParser.Kind
import java.net.URLEncoder
import java.util.Collections

/** A Vegamovies post: its raw heading, IMDb id and quality groups. */
data class VgPost(val url: String, val rawTitle: String, val imdbId: String?, val groups: List<VgGroup>)

/**
 * Vegamovies' search, post reader and stream resolver (nexdrive pages → host chains). Shared by the Vegamovies and
 * OmniStream plugins.
 */
object VegamoviesSource {
    // The site rotates its domain; the nexdrive, fastdl and vcloud hosts are always read from the page links.
    const val SITE = "https://new1.vegamovies.lease"

    /** Where search and permalinks go: [SITE], unless a plugin learned the current domain at runtime. */
    @Volatile
    var site = SITE
    const val SEARCH_PAGE_SIZE = 15

    // One resolver per plugin class loader: it holds the V-Cloud clearance.
    private val hosts = NexdriveResolver("Vegamovies")

    // `/ts-search.php` is a Typesense proxy with 15 hits per page; wp-json search is poisoned by Cloudflare's cache.
    suspend fun search(query: String, page: Int = 1): VgSearch? =
        app.get("$site/ts-search.php?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page", timeout = 30L).parsedSafe<VgSearch>()

    suspend fun post(url: String): VgPost? {
        val doc = app.get(url, timeout = 30L).document
        val rawTitle = doc.selectFirst("h1.post-title")?.text()?.trim() ?: return null
        return VgPost(url, rawTitle, VegamoviesParser.imdbId(doc), VegamoviesParser.groups(doc))
    }

    /** Batch zips hold whole seasons, never an episode, so they are not a stream source. */
    fun pagesOf(group: VgGroup) = group.buttons
        .filter { VegamoviesParser.kind(it.label) != Kind.BATCH }
        .map { VgPage(group.heading, it.label, it.url) }

    suspend fun loadLinks(link: VgLink, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        val sink = Sink(name, callback)
        // Every nexdrive page, host and mirror resolves at once; only the Cloudflare-challenged V-Cloud hops queue.
        link.pages.amap { page ->
            safely(name, "nexdrive ${page.label}") {
                val entries = VegamoviesParser.entries(app.get(page.url, timeout = 30L).document)
                    .filter { it.episode == link.episode }
                entries.amap { entry ->
                    hosts.resolve(entry.url) { host, url, streamable, quality -> sink.add(page, host, url, streamable, quality) }
                }
            }
        }
        return sink.isNotEmpty()
    }

    /**
     * Download-only links (see [NexdriveSink]) play from the start but cannot seek. Those rank as Unknown quality
     * (priority 4) under their own source name so every 480p+ stream is picked first, and users can still reorder
     * them in source priority.
     */
    private class Sink(private val name: String, private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        fun isNotEmpty() = emitted.isNotEmpty()

        /** [quality] overrides the heading's for transcodes, whose size and codec tags no longer apply; the player shows it. */
        suspend fun add(page: VgPage, host: String, url: String, streamable: Boolean, quality: Int? = null) {
            if (!emitted.add(url)) return
            val tags = if (quality != null) null else VegamoviesParser.tags(page.heading).ifBlank { page.heading.take(60) }
            val height = quality ?: VegamoviesParser.quality("${page.heading} ${page.label}").takeIf { it > 0 }
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, tags).joinToString(" · ")
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                this.quality = if (streamable) height ?: Qualities.Unknown.value else Qualities.Unknown.value
            })
        }
    }
}
