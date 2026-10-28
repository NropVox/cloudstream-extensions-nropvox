package com.nropvox

import com.nropvox.OnlyKDramaParser.Host
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages saved from onlykdrama.shop and its file hosts on 2026-10-29, scripts and styles stripped (src/test/resources). */
class OnlyKDramaParserTest {
    private val base = "https://onlykdrama.shop"

    private fun page(name: String, uri: String = base): Document =
        Jsoup.parse(javaClass.classLoader!!.getResource(name)!!.readText(), uri)

    @Test fun archiveCardsSkipSidebar() {
        val doc = page("archive-drama-p2.html")
        val cards = OnlyKDramaParser.cards(doc, base)
        assertEquals(60, cards.size)
        val lucky = cards.first()
        assertEquals("Lucky’s First Love (2019) Hindi Chinese Drama", lucky.title)
        assertEquals("https://onlykdrama.shop/drama/luckys-first-love-2019-hindi-chinese-drama/", lucky.url)
        assertEquals("https://image.tmdb.org/t/p/w342/bbG6odHs2A5uXJKN0iH2TFbdID5.jpg", lucky.poster)
        assertEquals(2019, lucky.year)
        assertFalse(lucky.movie)
        assertTrue(OnlyKDramaParser.hasNext(doc))
    }

    @Test fun searchCardsCarryType() {
        val doc = page("search-prince.html")
        val cards = OnlyKDramaParser.cards(doc, base)
        assertEquals(60, cards.size)
        assertEquals("Princess Silver (2019) Hindi Chinese Drama", cards.first().title)
        assertEquals("https://image.tmdb.org/t/p/w342/nGHIcpN52RhRaW4UAb3jU46JSh3.jpg", cards.first().poster)
        assertEquals(2019, cards.first().year)
        assertTrue(cards.any { it.movie && "/movies/" in it.url })
        assertTrue(cards.none { !it.movie && "/movies/" in it.url })
        assertTrue(OnlyKDramaParser.hasNext(doc))
    }

    @Test fun hostsAreMatchedByLabel() {
        assertEquals(Host.HUBCLOUD, OnlyKDramaParser.host("https://hubcloud.ist/video/pdafr1qpwxqdx2t"))
        assertEquals(Host.HUBCLOUD, OnlyKDramaParser.host("https://hubcloud.one/drive/abc"))
        assertEquals(Host.XCLOUD, OnlyKDramaParser.host("https://new11.xcloud.asia/efca13510bad79f"))
        assertEquals(Host.GDFLIX, OnlyKDramaParser.host("https://gdflix.dev/file/hUDtTg5tFffO6yf"))
        assertEquals(Host.OTHER, OnlyKDramaParser.host("https://gdflix.dev/pack/S1ShSWzJG5"))
        assertEquals(Host.DIRECT, OnlyKDramaParser.host("https://fsl.bawoy82668.workers.dev/download.aspx?file=DJtw&expiry=Ip"))
        assertEquals(Host.PIXELDRAIN, OnlyKDramaParser.host("https://pixeldrain.dev/u/Um5bZwzB"))
        assertEquals(Host.OTHER, OnlyKDramaParser.host("https://tmbcloud.dev/download/UI0Q9A"))
        assertEquals(Host.OTHER, OnlyKDramaParser.host("https://new4.filepress.baby/file/6a35a551a43515bfd1ef8204"))
        assertEquals(Host.OTHER, OnlyKDramaParser.host("https://onlykdrama.shop/how-to-download/"))
    }

    @Test fun showDetails() {
        val doc = page("show-100-days.html")
        assertEquals("100 Days My Prince (2018) Hindi Korean Drama", OnlyKDramaParser.title(doc))
        assertEquals("https://image.tmdb.org/t/p/w500/hzrcHzm5AgPZUVyyW5reAj2jHsy.jpg", OnlyKDramaParser.poster(doc))
        assertEquals(2018, OnlyKDramaParser.year(doc))
        assertEquals(81821, OnlyKDramaParser.tmdbId(doc))
        assertEquals(8.0, OnlyKDramaParser.rating(doc)!!, 0.01)
        assertEquals("https://www.youtube.com/watch?v=iBr2LKzW414", OnlyKDramaParser.trailer(doc))
        assertEquals(false, OnlyKDramaParser.ongoing(doc))
        val genres = OnlyKDramaParser.genres(doc)
        assertTrue("Historical" in genres && "Hindi Dubbed Drama" in genres)
        assertFalse("Completed" in genres)
        val plot = OnlyKDramaParser.plot(doc)!!
        assertTrue(plot.startsWith("Yi Yul, Crown Prince of Joseon"))
        assertTrue("Native Title" in plot)
        assertFalse("Download" in plot)
    }

    @Test fun currentLayoutEpisodes() {
        val entries = OnlyKDramaParser.entries(page("show-100-days.html"))
        // 16 episodes × (5 HubCloud + 5 XCloud releases); TMBCloud is captcha-gated and dropped.
        val episodes = entries.filter { it.episode != null }
        assertEquals(160, episodes.size)
        assertEquals((1..16).toList(), episodes.map { it.episode!! }.distinct().sorted())
        assertTrue(episodes.all { it.season == 1 })
        assertEquals(10, episodes.count { it.episode == 5 })
        // The two GDFlix season packs are /file/ links without an episode number.
        assertEquals(2, entries.count { it.episode == null })
        val first = episodes.first().file
        assertEquals("https://hubcloud.ist/video/pdafr1qpwxqdx2t", first.url)
        assertEquals("5.04 GB", first.size)
        assertTrue(first.name.startsWith("100.Days.My.Prince.S01E01.1080p"))
    }

    @Test fun olderLayoutGroupsByEpisodeHeading() {
        val entries = OnlyKDramaParser.entries(page("show-bloody-flower.html"))
        val episodes = entries.filter { it.episode != null }
        assertEquals((1..8).toList(), episodes.map { it.episode!! }.distinct().sorted())
        val hosts = episodes.filter { it.episode == 1 }.map { OnlyKDramaParser.host(it.file.url) }.toSet()
        assertEquals(setOf(Host.DIRECT, Host.HUBCLOUD, Host.GDFLIX), hosts)
    }

    @Test fun movieFiles() {
        val doc = page("movie-yeonae.html")
        assertTrue(OnlyKDramaParser.isMovie("https://onlykdrama.shop/movies/love-is-a-crazy-thing-2005-korean-movie/"))
        val files = OnlyKDramaParser.entries(doc).map { it.file }
        // 2 HubCloud + 3 XCloud; TMBCloud, FileDitch and FilePress are not resolvable.
        assertEquals(5, files.size)
        assertEquals(setOf(Host.HUBCLOUD, Host.XCLOUD), files.map { OnlyKDramaParser.host(it.url) }.toSet())
        assertEquals("1.69 GB", files.first().size)
        assertEquals(1080, OnlyKDramaParser.quality(files.first().name))
    }

    @Test fun releaseLabels() {
        assertEquals(
            "1080p AMZN WEB-DL Hindi DDP2.0-Korean DDP2.0 H.264",
            OnlyKDramaParser.release("100.Days.My.Prince.S01E01.1080p.AMZN.WEB-DL.Hindi.DDP2.0-Korean.DDP2.0.H.264-[OnlyKDrama.top].mkv"),
        )
        assertEquals("1080p WEBRip x264 AAC", OnlyKDramaParser.release("Yeonae.2005.1080p.WEBRip.x264.AAC-[OnlyKDrama.top].mkv"))
        assertEquals(540, OnlyKDramaParser.quality("Robber.S01E01.540p.NF.WEB-DL.x265-SiN"))
    }

    @Test fun hubcloudVideoPageLeadsToServerList() {
        val next = NexdriveParser.hubcloudNext(page("hubcloud-video.html", "https://hubcloud.ist/video/pdafr1qpwxqdx2t"))!!
        assertTrue(next.startsWith("https://gamerxyt.com/hubcloud.php?host=hubvid&id=pdafr1qpwxqdx2t&token="))
        val mirrors = NexdriveParser.mirrors(page("hubcloud-servers.html")).toMap()
        assertEquals("https://pixeldrain.dev/u/Um5bZwzB", mirrors["PixelServer:2"])
        assertTrue(mirrors["Server : 10Gbps"]!!.startsWith("https://gpdl2.hubcloud.ist/?id="))
    }

    @Test fun gdflixPixeldrain() {
        assertEquals("https://pixeldrain.dev/u/nBkDmmQF", OnlyKDramaParser.gdflixPixeldrain(page("gdflix-file.html")))
    }

    @Test fun xcloudChain() {
        val servers = OnlyKDramaParser.xcloudServers(page("xcloud-landing.html", "https://xcloud.tattoo/efca13510bad79f"))!!
        assertTrue(servers.startsWith("https://xcloud1.lol/efca13510bad79f?token="))
        val mirrors = OnlyKDramaParser.xcloudMirrors(page("xcloud-servers.html"), servers)
        assertEquals(listOf("Cloud", "20GBPS"), mirrors.map { it.first })
        assertTrue(mirrors.all { it.second.startsWith("https://xcloud1.lol/apis/cloud") })
        assertNull(OnlyKDramaParser.xcloudServers(page("gdflix-file.html")))
    }
}
