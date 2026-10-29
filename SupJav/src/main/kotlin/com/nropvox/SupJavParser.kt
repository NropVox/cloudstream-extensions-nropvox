package com.nropvox

import org.jsoup.nodes.Element
import java.net.URLEncoder

internal data class SupJavCard(val url: String, val title: String, val poster: String?)

internal data class SupJavServer(val name: String, val link: String)

internal data class SupJavDetail(
    val title: String,
    val poster: String?,
    val plot: String?,
    val tags: List<String>,
    val servers: List<SupJavServer>,
    val related: List<SupJavCard>,
)

/** HTML of supjav.com (WordPress theme `supjav2`) and its player API on lk1.supremejav.com. */
internal object SupJavParser {
    private val olidRegex = Regex("""var OLID = '([^']+)'""")

    /** `category/censored-jav?sort=views` page 3 → `…/category/censored-jav/page/3?sort=views`. */
    fun listUrl(base: String, path: String, page: Int): String {
        val route = path.substringBefore('?').trim('/')
        val query = path.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val paged = if (page > 1) listOf(route, "page/$page").filter { it.isNotEmpty() }.joinToString("/") else route
        return "$base/$paged$query"
    }

    fun searchUrl(base: String, query: String, page: Int): String =
        listUrl(base, "?s=" + URLEncoder.encode(query, "UTF-8"), page)

    fun cards(root: Element): List<SupJavCard> =
        root.select("div.post").mapNotNull { post ->
            val link = post.selectFirst("h3 a[href]") ?: post.selectFirst("a.img[href]") ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { link.text() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            SupJavCard(link.absUrl("href").ifEmpty { link.attr("href") }, title, thumb(post.selectFirst("img.thumb")))
        }.distinctBy { it.url }

    /** Lazy cards keep the real image in `data-original` and a 1×1 data: PNG in `src`. */
    private fun thumb(img: Element?): String? {
        img ?: return null
        return listOf(img.attr("data-original"), img.attr("src"))
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    fun hasNext(root: Element): Boolean = root.selectFirst(".pagination li.next-page a[href]") != null

    fun detail(root: Element): SupJavDetail? {
        val meta = root.selectFirst(".post-meta") ?: return null
        val title = meta.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val servers = root.select(".btns a.btn-server[data-link]").mapNotNull { a ->
            a.attr("data-link").takeIf { it.isNotBlank() }?.let { SupJavServer(a.text().trim(), it) }
        }
        val related = root.select("div.content")
            .firstOrNull { it.selectFirst(".archive-title")?.text()?.contains("Also Like", true) == true }
            ?.let { cards(it) }.orEmpty()
        return SupJavDetail(
            title = title,
            poster = meta.selectFirst("img.img")?.attr("src")?.takeIf { it.isNotBlank() },
            plot = root.selectFirst(".post-content")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            tags = meta.select(".cats a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            servers = servers,
            related = related,
        )
    }

    /** The player page's `OLID`, reversed, is the `?c=` token whose 302 points at the host embed. */
    fun embedToken(playerHtml: String): String? =
        olidRegex.find(playerHtml)?.groupValues?.get(1)?.reversed()

    /** Embeds carry a `#supjav.com@CODE.mp4` fragment that the hosts don't need. */
    fun embedUrl(location: String): String = location.substringBefore('#')
}
