package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages fetched from each site on 2026-10-30; SupJav's come from Wayback copies (supjav.com-recon). */
class JavStreamParserTest {
    private fun page(name: String, base: String): Document =
        Jsoup.parse(javaClass.getResource("/javstream/$name")!!.readText(), "$base/")

    @Test fun codesMatchEachSiteSpelling() {
        val fc2 = JavStreamParser.codeRegex("fc2ppv-5000545")!!
        listOf("FC2PPV 5000545", "fc2-ppv-5000545", "FC2-PPV-5000545", "/watch/fc2ppv-5000545.html")
            .forEach { assertTrue(it, fc2.containsMatchIn(it)) }
        val mida = JavStreamParser.codeRegex("mida-605")!!
        listOf("[4K]MIDA-605 Newcomer", "https://javgg.net/jav/mida-605/", "MIDA605")
            .forEach { assertTrue(it, mida.containsMatchIn(it)) }
        listOf("MIDA-6050", "XMIDA-605", "MIDA-605-RM", "mida-605-reduce-mosaic")
            .forEach { assertFalse(it, mida.containsMatchIn(it)) }
        assertNull(JavStreamParser.codeRegex("search"))
    }

    @Test fun fc2CodesAreSearchedByNumber() {
        assertEquals("5000545", JavStreamParser.searchQuery("fc2ppv-5000545"))
        assertEquals("5000545", JavStreamParser.searchQuery("FC2-PPV-5000545"))
        assertEquals("ipzz-979", JavStreamParser.searchQuery("ipzz-979"))
    }

    @Test fun legacyDataWasTheCodeMadeAbsolute() {
        assertEquals(
            JavStreamLinks("https://sextb.net/dldss-560", "dldss-560"),
            JavStreamParser.legacyLinks("https://sextb.net/dldss-560", "https://sextb.net")
        )
        assertEquals(
            JavStreamLinks("https://sextb.net/dldss-560", "dldss-560"),
            JavStreamParser.legacyLinks("dldss-560", "https://sextb.net")
        )
    }

    @Test fun sextbSkipsTheReducingMosaicUpload() {
        val base = "https://sextb.net"
        assertEquals(
            "$base/fvfpmkqz3",
            JavStreamParser.findPage(page("sextb-search-mida-605.html", base), "div.tray-item", "a", "mida-605", base)
        )
    }

    @Test fun javtifulCardTitleCarriesTheCode() {
        val base = "https://javtiful.com"
        assertEquals(
            "$base/video/115024/dldss-560-reducing-mosaic",
            JavStreamParser.findPage(
                page("javtiful-search-dldss-560.html", base),
                "article.video-card:not(.video-card--partner)", "a.video-card__title", "dldss-560", base
            )
        )
    }

    @Test fun missavPicksThePlainUpload() {
        val doc = page("missav-search-dldss-560.html", "https://missav.live")
        assertEquals("https://missav.live/en/dldss-560", JavStreamParser.missavPage(doc, "dldss-560"))
        assertNull(JavStreamParser.missavPage(doc, "ipzz-979"))
    }

    @Test fun javggSearchAndEmbeds() {
        val base = "https://javgg.net"
        assertEquals("$base/jav/mida-605/", JavStreamParser.javggUrl(base, "MIDA-605"))
        assertEquals(
            "$base/jav/mida-605/",
            JavStreamParser.findPage(page("javgg-search-mida-605.html", base), ".result-item", "a[href]", "mida-605", base)
        )
        assertEquals(
            listOf("https://f4jav.xyz/e/4g5hqofv", "https://earnvidjavgg.xyz/embed/t87wvwevuu2r", "https://vidara.so/e/fiNtTPFUaypO"),
            JavStreamParser.javggEmbeds(page("javgg-ipzz-979.html", base))
        )
    }

    @Test fun supjavSearchServersAndToken() {
        val base = "https://supjav.com"
        assertEquals(
            "$base/280884.html",
            JavStreamParser.findPage(page("supjav-search-zuko.html", base), "div.post", "a[href]", "zuko-040", base)
        )
        assertNull(JavStreamParser.findPage(page("supjav-search-zuko.html", base), "div.post", "a[href]", "zuko-04", base))
        val servers = JavStreamParser.supjavServers(page("supjav-detail-447006.html", base))
        assertEquals(3, servers.size) // FST, ST, VOE; the dead TV server is dropped
        val voe = page("supjav-player-voe.html", base).html()
        assertEquals(servers.last().reversed(), JavStreamParser.supjavToken(voe))
    }

    @Test fun javfc2WatchPageHasThePlaylist() {
        val base = "https://javfc2.xyz"
        assertEquals("$base/watch/fc2ppv-5000545.html", JavStreamParser.javFc2Url(base, "FC2PPV-5000545"))
        assertEquals(
            "https://stream.javfc2vid.top/hello/getmickey/a4cqormioqp4",
            JavStreamParser.javFc2Stream(page("javfc2-watch-fc2ppv-5000545.html", base))
        )
        assertEquals(
            "$base/watch/fns-269.html",
            JavStreamParser.findPage(
                page("javfc2-search-fns-269.html", base), ".movie-container > div", ".movie-title a", "fns-269", base
            )
        )
    }

    @Test fun javhdChecksTheCodeOnTheDetailPage() {
        val base = "https://javhdz.life"
        assertEquals(
            "$base/cap-nhu-hoa-cang-hong-cua-em-idol-sato-meru-4016.html",
            JavStreamParser.javhdResults(page("javhd-search-sone-302.html", base), base).first()
        )
        val detail = page("javhd-detail-sone-302.html", base)
        assertTrue(JavStreamParser.javhdMatches(detail, "sone-302"))
        assertFalse(JavStreamParser.javhdMatches(detail, "sone-301"))
        assertEquals(listOf("4016" to "1", "4016" to "3", "4016" to "4"), JavStreamParser.javhdServers(detail))
        val stream = JavStreamParser.javhdStream(detail.selectFirst("#video")!!.html())
        assertTrue(stream, stream!!.startsWith("https://"))
    }

    @Test fun javhdAjaxPlayerIsAnIframe() {
        val player = """<div class="openload"><iframe src="https://asnwish.com/e/fmr81hu69ih2" scrolling="no"></iframe></div>"""
        assertEquals("https://asnwish.com/e/fmr81hu69ih2", JavStreamParser.iframeSrc(player))
        assertNull(JavStreamParser.javhdStream(player))
    }
}
