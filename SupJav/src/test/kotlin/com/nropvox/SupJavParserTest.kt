package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages from Wayback copies of supjav.com (2026-06..08) and a player page fetched 2026-10-29; see supjav.com-recon. */
class SupJavParserTest {
    private val base = "https://supjav.com"

    private fun page(name: String): Document {
        val html = javaClass.getResource("/supjav/$name")!!.readText()
        return Jsoup.parse(html, "$base/")
    }

    @Test fun listUrlsPageBeforeTheQuery() {
        assertEquals("$base/category/censored-jav", SupJavParser.listUrl(base, "category/censored-jav", 1))
        assertEquals("$base/category/censored-jav/page/2?sort=views", SupJavParser.listUrl(base, "category/censored-jav?sort=views", 2))
        assertEquals("$base/popular/page/3?sort=week", SupJavParser.listUrl(base, "popular?sort=week", 3))
    }

    @Test fun searchUrlsEncodeAndPageAtTheRoot() {
        assertEquals("$base/?s=ssis-001", SupJavParser.searchUrl(base, "ssis-001", 1))
        assertEquals("$base/page/2?s=fc2+ppv+4946175", SupJavParser.searchUrl(base, "fc2 ppv 4946175", 2))
    }

    @Test fun categoryCardsUseLazyThumbs() {
        val doc = page("category-censored-views.html")
        val cards = SupJavParser.cards(doc)
        assertEquals(24, cards.size)
        val first = cards.first()
        assertEquals("$base/420447.html", first.url)
        assertTrue(first.title.startsWith("[4K]MIDA-605 Newcomer"))
        assertEquals("https://img.supjav.com/images/2026/04/mida605pl.jpg!320x216.jpg", first.poster)
        assertTrue(cards.none { it.poster.orEmpty().startsWith("data:") })
        assertTrue(SupJavParser.hasNext(doc))
    }

    @Test fun homeSwiperCardsUsePlainSrc() {
        val cards = SupJavParser.cards(page("home.html"))
        val fc2 = cards.first { it.url == "$base/445777.html" }
        assertEquals("https://img.supjav.com/images/2026/07/FC2PPV-4946175.jpg!320x216.jpg", fc2.poster)
        assertEquals(cards.size, cards.map { it.url }.distinct().size)
    }

    @Test fun searchAndPopularPages() {
        val search = page("search-zuko.html")
        assertEquals(24, SupJavParser.cards(search).size)
        assertTrue(SupJavParser.hasNext(search))
        assertEquals(24, SupJavParser.cards(page("popular-week.html")).size)
    }

    @Test fun lastPageHasNoNext() {
        val doc = Jsoup.parse("""<div class="pagination"><ul><li class="prev-page"><a href="/page/2?s=x">&lt;</a></li><li class="active"><span>3</span></li><li class="next-page"></li></ul></div>""")
        assertFalse(SupJavParser.hasNext(doc))
    }

    @Test fun detailReadsServersTagsAndRelated() {
        val detail = SupJavParser.detail(page("detail-447006.html"))!!
        assertTrue(detail.title.startsWith("586HNHU-0214 A Slender, Cute Beauty"))
        assertEquals("https://img.supjav.com/images/2026/08/pb_e_586hnhu-0214.jpg", detail.poster)
        assertNull(detail.plot)
        assertEquals(listOf("Amateur", "Creampie", "Nampa", "Older Sister", "Slender", "Tits"), detail.tags)
        assertEquals(listOf("TV", "FST", "ST", "VOE"), detail.servers.map { it.name })
        assertTrue(detail.servers[2].link.startsWith("1dd3426e72823c98"))
        assertTrue(detail.related.isNotEmpty())
        assertEquals("$base/446999.html", detail.related.first().url)
        // "Today's Popular" in the sidebar isn't related.
        assertTrue(detail.related.size <= 24)
    }

    @Test fun listingPageIsNotADetail() {
        assertNull(SupJavParser.detail(page("category-censored-views.html")))
    }

    @Test fun playerTokenIsTheReversedOlid() {
        val html = javaClass.getResource("/supjav/player-ST.html")!!.readText()
        assertEquals(
            "9c81c23b6111e9407b62bade2df8b9454f795c1005f7369cd7737d7948522232831a43963a2b9851a06c690659517a60521b30b51619cce589c32827e6243dd1",
            SupJavParser.embedToken(html)
        )
        assertNull(SupJavParser.embedToken("<html></html>"))
    }

    @Test fun embedUrlDropsTheSiteFragment() {
        assertEquals("https://voe.sx/e/bhnyqlficthn", SupJavParser.embedUrl("https://voe.sx/e/bhnyqlficthn#supjav.com@586HNHU-0214.mp4"))
        assertEquals("https://streamtape.com/e/elOzLK9mJ0UY4dv/586HNHU-0214.mp4", SupJavParser.embedUrl("https://streamtape.com/e/elOzLK9mJ0UY4dv/586HNHU-0214.mp4"))
    }
}
