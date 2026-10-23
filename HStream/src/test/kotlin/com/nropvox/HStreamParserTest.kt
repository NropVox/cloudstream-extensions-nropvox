package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snippets trimmed from hstream.moe pages saved on 2026-10-23. */
class HStreamParserTest {
    private val base = "https://hstream.moe"

    private fun card(episode: String, img: String, alt: String) = """
        <div wire:key="episode-1"><div class="group w-full p-1">
          <a href="https://hstream.moe/hentai/$episode" class="block">
            <div class="relative overflow-hidden">
              <img src="$img" alt="$alt" loading="lazy" width="400">
              <div class="ml-auto rounded-full">4k | FHD 48fps</div>
              <h3 class=" text-sm font-semibold">$alt</h3>
            </div>
          </a>
        </div></div>"""

    @Test fun cardsCollapseEpisodesIntoSeries() {
        val doc = Jsoup.parse(
            card("ane-wa-yanmama-junyuu-chuu-1", "/images/hentai/ane-wa-yanmama-junyuu-chuu/cover-ep-1.webp", "Ane wa Yanmama Junyuu-chuu - 1") +
                card("ane-wa-yanmama-junyuu-chuu-2", "/images/hentai/ane-wa-yanmama-junyuu-chuu/cover-ep-2.webp", "Ane wa Yanmama Junyuu-chuu - 2") +
                // Default thumbnail view and a series slug that ends in its own season tag.
                card("bloods-inraku-no-ketsuzoku-s2-2", "/images/hentai/bloods-inraku-no-ketsuzoku-s2/gallery-ep-2-0-thumbnail.webp", "Bloods Inraku no Ketsuzoku S2 - 2") +
                """<a href="https://hstream.moe/hentai/fetikano"><img src="/images/x.webp" alt="no episode suffix"></a>""" +
                """<button wire:click="nextPage">Next</button>"""
        )
        val cards = HStreamParser.cards(doc, base)
        assertEquals(listOf("ane-wa-yanmama-junyuu-chuu", "bloods-inraku-no-ketsuzoku-s2"), cards.map { it.slug })
        assertEquals("Ane wa Yanmama Junyuu-chuu", cards[0].title)
        assertEquals("https://hstream.moe/images/hentai/ane-wa-yanmama-junyuu-chuu/cover-ep-1.webp", cards[0].poster)
        assertEquals("Bloods Inraku no Ketsuzoku S2", cards[1].title)
        assertEquals("https://hstream.moe/images/hentai/bloods-inraku-no-ketsuzoku-s2/cover-ep-2.webp", cards[1].poster)
        assertTrue(HStreamParser.hasNextPage(doc))
        assertFalse(HStreamParser.hasNextPage(Jsoup.parse("<p>No results (╥﹏╥)</p>")))
    }

    @Test fun homeTabsAreFoundById() {
        val doc = Jsoup.parse(
            """<div x-show="tab === 'tabs-trending'" id="tabs-trending" role="tabpanel"><div class="grid">""" +
                card("fetikano-1", "/images/hentai/fetikano/cover-ep-1.webp", "Fetikano! - 1") +
                """</div></div><div x-show="tab === 'tabs-most-likes'" id="tabs-most-likes">""" +
                card("furachi-2", "/images/hentai/furachi/gallery-ep-2-0-thumbnail.webp", "Furachi - 2") + "</div>"
        )
        val trending = HStreamParser.cards(HStreamParser.homeTab(doc, "tabs-trending")!!, base)
        assertEquals(listOf("fetikano"), trending.map { it.slug })
        assertEquals("Fetikano!", trending.single().title)
        assertEquals(listOf("furachi"), HStreamParser.cards(HStreamParser.homeTab(doc, "tabs-most-likes")!!, base).map { it.slug })
    }

    @Test fun seriesPageListsOnlyItsOwnEpisodes() {
        val doc = Jsoup.parse(
            """
            <img alt="Ane wa Yanmama Junyuu-chuu" src="/images/hentai/ane-wa-yanmama-junyuu-chuu/cover-ep-1.webp">
            <div class="min-w-0">
              <h1 class="wrap-break-word"><span class="bg-linear-to-r">Ane wa Yanmama Junyuu-chuu</span></h1>
              <p class="mt-2 text-sm">義姉はヤンママ授乳中</p>
              <div class="mt-4 flex">
                <div class="inline-flex"><i class="fa-solid fa-upload text-xs"></i> 2022-10-04</div>
                <div class="inline-flex"><i class="fa-regular fa-calendar text-xs"></i> 2020-06-05</div>
                <a href="https://hstream.moe/search?order=recently-uploaded&amp;studios%5B0%5D=t-rex">T-Rex</a>
              </div>
            </div>
            <div class="mt-8"><h2 class="mb-3">Description</h2><p class="leading-relaxed">This story about a married woman.</p></div>
            <div class="mt-8"><h2 class="mb-4">Genres</h2><div class="flex">
              <a href="https://hstream.moe/search?order=recently-uploaded&amp;tags%5B0%5D=48fps">48Fps</a>
              <a href="https://hstream.moe/search?order=recently-uploaded&amp;tags%5B0%5D=4k">4K</a>
            </div></div>
            <div class="p-5 md:p-7"><a class="text-lg font-bold">Episodes (2)</a><div class="grid">
              <a href="https://hstream.moe/hentai/ane-wa-yanmama-junyuu-chuu-2"><img src="/images/hentai/ane-wa-yanmama-junyuu-chuu/gallery-ep-2-0-thumbnail.webp"></a>
              <a href="https://hstream.moe/hentai/ane-wa-yanmama-junyuu-chuu-1"><img src="/images/hentai/ane-wa-yanmama-junyuu-chuu/gallery-ep-1-0-thumbnail.webp"></a>
            </div></div>
            <div><a href="https://hstream.moe/hentai/ane-wa-yanmama-junyuu-chuu-sequel-1"><img src="/images/hentai/x/cover-ep-1.webp"></a></div>
            """
        )
        assertNull(HStreamParser.episodeId(doc))
        val series = HStreamParser.series(doc, "ane-wa-yanmama-junyuu-chuu", base)!!
        assertEquals("Ane wa Yanmama Junyuu-chuu", series.title)
        assertEquals("義姉はヤンママ授乳中", series.altTitle)
        assertEquals("https://hstream.moe/images/hentai/ane-wa-yanmama-junyuu-chuu/cover-ep-1.webp", series.cover)
        assertEquals("This story about a married woman.", series.plot)
        assertEquals(2020, series.year)
        assertEquals("T-Rex", series.studio)
        assertEquals(listOf("48Fps", "4K"), series.tags)
        assertEquals(listOf(1, 2), series.episodes.map { it.number })
        assertEquals("https://hstream.moe/hentai/ane-wa-yanmama-junyuu-chuu-1", series.episodes[0].url)
        assertEquals("https://hstream.moe/images/hentai/ane-wa-yanmama-junyuu-chuu/gallery-ep-1-0-thumbnail.webp", series.episodes[0].thumbnail)
    }

    @Test fun episodePageNamesItsIdAndSeries() {
        val doc = Jsoup.parse(
            """<h1 class="wrap-break-word"><a href="https://hstream.moe/hentai/fetikano" class="bg-linear-to-r">Fetikano! - 1</a></h1>
               <input id="e_id" type="hidden" value="1617" />"""
        )
        assertEquals("1617", HStreamParser.episodeId(doc))
        assertEquals("https://hstream.moe/hentai/fetikano", HStreamParser.seriesUrl(doc, base))
    }

    @Test fun slugs() {
        assertEquals("fetikano-1", HStreamParser.slug("https://hstream.moe/hentai/fetikano-1?t=30"))
        assertEquals("fetikano", HStreamParser.seriesOfEpisode("fetikano-1"))
        assertEquals(12, HStreamParser.episodeNumber("bloods-inraku-no-ketsuzoku-s2-12"))
        assertNull(HStreamParser.seriesOfEpisode("fetikano"))
    }
}
