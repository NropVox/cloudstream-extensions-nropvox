package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreePornVideosParserTest {
    private val base = "https://www.freepornvideos.xxx"

    @Test
    fun cardsAndPaginationParseSiteMarkup() {
        val doc = Jsoup.parse(
            """<div class="list-videos"><div class="item">
                <a href="/videos/123/sample-video/" title="Sample Video"><img src="data:image/gif" data-src="https://img.example/123.jpg"></a>
            </div><div class="item"><a href="/videos/123/sample-video/" title="Duplicate"><img src="x"></a></div></div>
            <div class="pagination"><a href="/latest-updates/2/">2</a></div>"""
        )
        val cards = FreePornVideosParser.cards(doc, base)
        assertEquals(1, cards.size)
        assertEquals("Sample Video", cards[0].title)
        assertEquals("$base/videos/123/sample-video/", cards[0].url)
        assertEquals("https://img.example/123.jpg", cards[0].poster)
        assertTrue(FreePornVideosParser.hasNextPage(doc, 1))
        assertFalse(FreePornVideosParser.hasNextPage(doc, 2))
    }

    @Test
    fun detailParsesMetadataAndLabeledSources() {
        val doc = Jsoup.parse(
            """<head><meta property="og:title" content="Sample Video">
                <meta property="og:image" content="https://img.example/poster.jpg">
                <meta property="og:description" content="A full length sample">
            </head><video><source src="https://site.example/2160.mp4" type="video/mp4" label="2160p">
                <source src="https://site.example/720.mp4" type="video/mp4" label="720p"></video>"""
        )
        val detail = FreePornVideosParser.detail(doc)!!
        assertEquals("Sample Video", detail.title)
        assertEquals("https://img.example/poster.jpg", detail.poster)
        assertEquals("A full length sample", detail.plot)
        assertEquals(listOf(2160, 720), detail.media.map { it.quality })
    }
}