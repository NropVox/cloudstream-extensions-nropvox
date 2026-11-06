package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PornhubParserTest {
    private val base = "https://www.pornhub.com"

    @Test
    fun cardsParseStableVideoMarkup() {
        val doc = Jsoup.parse(
            """<ul>
                <li class="pcVideoListItem"><a href="/view_video.php?viewkey=abc123" title="Sample Video"><img src="https://img.example/poster.jpg"></a></li>
                <li class="pcVideoListItem"><a href="/view_video.php?viewkey=abc123" title="Sample Video"><img src="x"></a></li>
                <li class="pcVideoListItem"><a href="/view_video.php?viewkey=def456"><img src="data:image/gif;base64,placeholder" data-image="https://img.example/other.jpg" alt="Other Video"></a></li>
            </ul><a href="/video?page=2">Next</a>"""
        )
        val cards = PornhubParser.cards(doc, base)
        assertEquals(2, cards.size)
        assertEquals("Sample Video", cards[0].title)
        assertEquals("$base/view_video.php?viewkey=abc123", cards[0].url)
        assertEquals("https://img.example/poster.jpg", cards[0].poster)
        assertEquals("Other Video", cards[1].title)
        assertTrue(PornhubParser.hasNextPage(doc, 1))
        assertFalse(PornhubParser.hasNextPage(doc, 2))
    }

    @Test
    fun detailParsesMetadataAndSignedMediaDefinitions() {
        val doc = Jsoup.parse(
            """<html><head>
                <title>Sample Video - Pornhub.com</title>
                <meta property="og:title" content="Sample Video">
                <meta property="og:image" content="https://img.example/poster.jpg">
                <meta property="og:description" content="A sample description">
                <script>var flashvars_123 = {"mediaDefinitions":[
                    {"height":240,"format":"hls","videoUrl":"https://cdn.example/240.m3u8"},
                    {"height":720,"format":"hls","videoUrl":"https://cdn.example/720.m3u8"},
                    {"height":720,"format":"mp4","videoUrl":"https://www.pornhub.com/video/get_media?s=token"}
                ],"isVertical":"false"};</script>
            </head></html>"""
        )
        val detail = PornhubParser.detail(doc)!!
        assertEquals("Sample Video", detail.title)
        assertEquals("https://img.example/poster.jpg", detail.poster)
        assertEquals("A sample description", detail.plot)
        assertEquals(listOf(240, 720, 720), detail.media.map { it.quality })
        assertEquals(listOf("hls", "hls", "mp4"), detail.media.map { it.format })
    }
}
