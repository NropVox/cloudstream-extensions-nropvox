package com.nropvox

import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Collections

/**
 * 4KHDHub's search and stream resolver: every file is a HubCloud drive behind an encoded redirector. Shared by the
 * 4KHDHub and OmniStream plugins.
 */
object FourKHDHubSource {
    const val SITE = "https://4khdhub.one"

    /** Where search goes: [SITE], unless a plugin learned the current domain at runtime. */
    @Volatile
    var site = SITE

    private val hosts = NexdriveResolver("4KHDHub")

    /** Cards of one page of `/?s=`. */
    suspend fun search(query: String, page: Int = 1): Pair<List<FkCard>, Boolean> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$site/?s=$q" else "$site/page/$page/?s=$q"
        val doc = app.get(url, timeout = 30L).document
        val cards = FourKHDHubParser.cards(doc, site)
        return cards to (cards.isNotEmpty() && FourKHDHubParser.hasNext(doc))
    }

    suspend fun loadLinks(link: FkLink, name: String, callback: (ExtractorLink) -> Unit): Boolean {
        val sink = Sink(name, callback)
        link.files.amap { file ->
            safely(name, "release ${file.name.take(40)}") {
                // The HubDrive link of a release opens the same HubCloud drive, so the first one that resolves wins.
                val drive = file.urls.firstNotNullOfOrNull { url -> safely(name, "redirect") { hubcloudDrive(url) } }
                    ?: return@safely
                hosts.resolve(drive) { host, url, streamable, _ -> sink.add(file, host, url, streamable) }
            }
        }
        return sink.isNotEmpty()
    }

    /** Redirector link → `https://hubcloud.ist/drive/<id>`, through a HubDrive page if that is where it points. */
    private suspend fun hubcloudDrive(url: String): String? {
        val target = FourKHDHubParser.redirectTarget(app.get(url, timeout = 30L).text) { base64Decode(it) } ?: return null
        return when (NexdriveParser.host(target)) {
            NexdriveParser.Host.HUBCLOUD -> target
            else -> FourKHDHubParser.hubdriveTarget(app.get(target, timeout = 30L).document)
        }
    }

    /**
     * Download-only links (see [NexdriveSink]) play from the start but cannot seek. Those rank as Unknown quality
     * under their own source name so every stream is picked first.
     */
    private class Sink(private val name: String, private val callback: (ExtractorLink) -> Unit) {
        private val emitted = Collections.synchronizedSet(HashSet<String>())

        fun isNotEmpty() = emitted.isNotEmpty()

        suspend fun add(file: FkFile, host: String, url: String, streamable: Boolean) {
            if (!emitted.add(url)) return
            val kind = if (streamable) "Stream" else "Download"
            val source = if (streamable) name else "$name Download"
            val label = listOfNotNull(host, FourKHDHubParser.tags(file.name).ifBlank { null }, file.size).joinToString(" · ")
            val height = FourKHDHubParser.quality(file.name).takeIf { it > 0 } ?: Qualities.Unknown.value
            callback(newExtractorLink(source, "$name [$kind] $label", url, ExtractorLinkType.VIDEO) {
                quality = if (streamable) height else Qualities.Unknown.value
            })
        }
    }
}
