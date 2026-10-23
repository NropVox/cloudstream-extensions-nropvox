package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Values taken from API responses and CDN playlists saved on 2026-10-23. */
class HentaverseParserTest {
    private val mapped = HvVideo(
        id = "cmbtboo370rvvw9ps5tspxim4",
        videoPath = "uploads/videos/7474d099-8688-407e-b854-ec80aa8e88e8/renditions",
        mp4Path = "uploads/videos/7474d099-8688-407e-b854-ec80aa8e88e8/renditions/1080p.mp4",
        videoType = "mapped",
    )

    @Test fun pageUrlsGiveKindAndSlug() {
        assertEquals("hentai" to "uhou-renka", HentaverseParser.parsePageUrl("https://hentaverse.com/hentai/uhou-renka"))
        assertEquals("video" to "uhou-renka-episode-1", HentaverseParser.parsePageUrl("https://hentaverse.com/video/uhou-renka-episode-1/?t=3"))
        assertNull(HentaverseParser.parsePageUrl("https://hentaverse.com/categories/bdsm"))
        assertNull(HentaverseParser.parsePageUrl("uhou-renka-episode-1"))
    }

    @Test fun cdnPathsArePrefixed() {
        assertEquals("https://cdn.hentaverse.com/uploads/series/uhou-renka.webp", HentaverseParser.cdnUrl("uploads/series/uhou-renka.webp"))
        assertEquals("https://x.test/a.webp", HentaverseParser.cdnUrl("https://x.test/a.webp"))
        assertNull(HentaverseParser.cdnUrl(""))
        assertEquals(
            "https://hentaverse.com/_next/image?url=https%3A%2F%2Fcdn.hentaverse.com%2Fuploads%2Fseries%2Fxl-joushi.webp&w=384&q=75",
            HentaverseParser.posterUrl("uploads/series/xl-joushi.webp")
        )
        assertNull(HentaverseParser.thumbnailUrl(null))
    }

    @Test fun mappedVideosHaveHlsAndRenditions() {
        assertTrue(HentaverseParser.hasHls(mapped))
        assertEquals("https://cdn.hentaverse.com/hls/cmbtboo370rvvw9ps5tspxim4/master.m3u8", HentaverseParser.masterUrl(mapped.id!!))
        assertEquals("https://cdn.hentaverse.com/uploads/videos/7474d099-8688-407e-b854-ec80aa8e88e8/renditions", HentaverseParser.renditionsBase(mapped))
        assertEquals(
            "https://cdn.hentaverse.com/uploads/videos/7474d099-8688-407e-b854-ec80aa8e88e8/renditions/1080p.mp4",
            HentaverseParser.singleMp4(mapped.copy(mp4Path = null))
        )
    }

    @Test fun otherVideoTypesPlayTheirPathDirectly() {
        val single = HvVideo(id = "x", videoPath = "uploads/videos/abc/video.mp4", videoType = "upload")
        assertFalse(HentaverseParser.hasHls(single))
        assertNull(HentaverseParser.renditionsBase(single))
        assertEquals("https://cdn.hentaverse.com/uploads/videos/abc/video.mp4", HentaverseParser.singleMp4(single))
    }

    @Test fun masterHeightsSkipIframeStreams() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2128999,RESOLUTION=1920x1080,FRAME-RATE=24.000,CODECS="avc1.640028,mp4a.40.2"
            index-sFHD-v1-a1.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=476088,RESOLUTION=640x360,FRAME-RATE=24.000,CODECS="avc1.64001e,mp4a.40.2"
            index-sLD-v1-a1.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1059203,RESOLUTION=1280x720,FRAME-RATE=24.000,CODECS="avc1.64001f,mp4a.40.2"
            index-sHD-v1-a1.m3u8

            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=53318,RESOLUTION=640x360,CODECS="avc1.64001e",URI="iframes-sLD-v1-a1.m3u8"
        """.trimIndent()
        assertEquals(listOf(1080, 720, 360), HentaverseParser.masterHeights(master))
    }

    @Test fun durationsBecomeMinutes() {
        assertEquals(28, HentaverseParser.minutes("00:28:25"))
        assertEquals(30, HentaverseParser.minutes("00:29:39"))
        assertNull(HentaverseParser.minutes("00:00:00"))
        assertNull(HentaverseParser.minutes(null))
    }
}
