package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XHamsterParserTest {
    private val base = "https://xhamster.com"

    @Test
    fun cardsAndPaginationParseSiteMarkup() {
        val doc = Jsoup.parse("""
            <div class="video-thumb">
                <a data-role="thumb-link" href="https://xhamster.com/videos/sample-xh123" aria-label="Sample Video">
                    <img src="https://ic-vt-nss.xhcdn.com/sample.webp" alt="Sample Video">
                </a>
            </div>
            <a href="https://xhamster.com/2">2</a>
        """.trimIndent())

        val card = XHamsterParser.cards(doc, base).single()
        assertEquals("Sample Video", card.title)
        assertEquals("https://xhamster.com/videos/sample-xh123", card.url)
        assertEquals("https://ic-vt-nss.xhcdn.com/sample.webp", card.poster)
        assertTrue(XHamsterParser.hasNextPage(doc, 1, base))
        assertFalse(XHamsterParser.hasNextPage(doc, 2, base))
    }

    @Test
    fun detailExtractsMetadataAndDirectMedia() {
        val doc = Jsoup.parse("""
            <meta property="og:title" content="Sample Video">
            <meta property="og:image" content="https://ic-vt-nss.xhcdn.com/sample.webp">
            <meta property="og:description" content="A full length sample">
            <video src="https://video5.xhcdn.com/key=abc/speed=0/025/114/349/480p.h264.mp4"></video>
        """.trimIndent())

        val detail = XHamsterParser.detail(doc)!!
        assertEquals("Sample Video", detail.title)
        assertEquals("https://ic-vt-nss.xhcdn.com/sample.webp", detail.poster)
        assertEquals("A full length sample", detail.plot)
        assertEquals(480, detail.media.single().quality)
    }

    @Test
    fun detailReadsEveryQualityFromPlayerState() {
        val doc = Jsoup.parse("""
            <meta property="og:title" content="Sample Video">
            <video class="player-container__no-script-video"
                   src="https://video5.xhcdn.com/key=MvmkjtnHVP-HTtQRMREYcQ,end=1793948400,limit=3/data=x/speed=0/029/566/551/480p.h264.mp4"></video>
            <script>window.state = {"sources":{"144p":"https:\/\/video5.xhcdn.com\/key=SvB7-xICASfeU4GhQcqP1A,end=1793948400,limit=3\/data=x\/speed=0\/029\/566\/551\/144p.h264.mp4","240p":"https:\/\/video5.xhcdn.com\/key=JklM+aecUoAPiv7x0M5Vng,end=1793948400,limit=3\/data=x\/speed=0\/029\/566\/551\/240p.h264.mp4","480p":"https:\/\/video5.xhcdn.com\/key=MvmkjtnHVP-HTtQRMREYcQ,end=1793948400,limit=3\/data=x\/speed=0\/029\/566\/551\/480p.h264.mp4","720p":"https:\/\/video5.xhcdn.com\/key=-mgqCjKyKJ6bWXDdgyiNiA,end=1793948400,limit=3\/data=x\/speed=0\/029\/566\/551\/720p.h264.mp4"}};</script>
        """.trimIndent())

        val media = XHamsterParser.detail(doc)!!.media
        assertEquals(listOf(144, 240, 480, 720), media.map { it.quality })
        assertTrue(media.all { it.url.startsWith("https://video5.xhcdn.com/key=") && !it.url.contains("\\") })
    }

    @Test
    fun detailFindsHlsMasterAndVariantHeights() {
        val master = "https://video-nss-a.xhcdn.com/qyp/media=hls4/multi=256x144:144p:,3840x2160:2160p:/029/175/904/_TPL_.av1.mp4.m3u8"
        val doc = Jsoup.parse("""
            <meta property="og:title" content="Sample Video">
            <script>{"hls":"${master.replace("/", "\\/")}"}</script>
        """.trimIndent())

        assertEquals(master, XHamsterParser.detail(doc)!!.hlsMaster)
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=210888,RESOLUTION=256x144
            144p.av1.mp4.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=5225805,RESOLUTION=1920x1080
            1080p.av1.mp4.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=14591257,RESOLUTION=3840x2160
            2160p.av1.mp4.m3u8
        """.trimIndent()
        assertEquals(listOf(144, 1080, 2160), XHamsterParser.hlsHeights(playlist))
    }

    @Test
    fun variantUrlsFollowMasterNaming() {
        val master = "https://video-nss-a.xhcdn.com/qyp/hls4/029/175/904/_TPL_.av1.mp4.m3u8"
        assertEquals(
            "https://video-nss-a.xhcdn.com/qyp/hls4/029/175/904/2160p.h264.mp4.m3u8",
            XHamsterParser.h264Variant(master, 2160),
        )
        assertEquals(
            "https://video-nss-a.xhcdn.com/qyp/hls4/029/175/904/1080p.av1.mp4.m3u8",
            XHamsterParser.av1Variant(master, 1080),
        )
    }
}
