package com.nropvox

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages and responses saved from k-drama.in on 2026-10-29 (recon folder `k-drama.in-recon/raw`). */
class KatDramaParserTest {
    private val base = "https://k-drama.in"

    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun idsComeFromEveryPageShape() {
        assertEquals("tv" to 215720, KatDramaParser.idOf("https://k-drama.in/detail/tv/queen-of-tears-215720"))
        assertEquals("tv" to 94605, KatDramaParser.idOf("https://k-drama.in/detail/tv/arcane-94605?season=2"))
        assertEquals("movie" to 1058424, KatDramaParser.idOf("https://k-drama.in/detail/movie/hope-1058424"))
        assertEquals("tv" to 94605, KatDramaParser.idOf("https://k-drama.in/watch/tv/arcane-94605-episode-15"))
        // Legacy links without a type segment; the slug itself may end in digits.
        assertEquals("tv" to 303745, KatDramaParser.idOf("https://k-drama.in/detail/love-in-disguise-303745"))
        assertEquals("tv" to 254630, KatDramaParser.idOf("https://k-drama.in/detail/tv/12-12-12-254630"))
        assertNull(KatDramaParser.idOf("https://k-drama.in/explore?type=kdrama"))
    }

    @Test fun detailUrlUsesTheSiteSlugRule() {
        assertEquals("https://k-drama.in/detail/tv/love-death-robots-86831",
            KatDramaParser.detailUrl(base, "tv", 86831, "Love, Death & Robots"))
        assertEquals("https://k-drama.in/detail/tv/tv-1", KatDramaParser.detailUrl(base, "tv", 1, "눈물의 여왕"))
    }

    @Test fun exploreCards() {
        val doc = Jsoup.parse(resource("explore.html"))
        val cards = KatDramaParser.cards(doc, base)
        assertEquals(20, cards.size)
        assertEquals("https://k-drama.in/detail/tv/taxi-driver-119769", cards[0].url)
        assertEquals("Taxi Driver", cards[0].title)
        assertTrue(cards[0].poster!!.startsWith("https://image.tmdb.org/t/p/w500/"))
        assertFalse(cards[0].isMovie)
        assertTrue(cards.all { it.year != null })
        assertTrue(KatDramaParser.hasNextPage(doc, 2))
        assertFalse(KatDramaParser.hasNextPage(doc, 3))
    }

    @Test fun pastTheLastPageThereIsNothing() {
        val doc = Jsoup.parse("""<div class="grid"></div><a href="/explore?type=kdrama&sort=popular&page=1">K</a>""")
        assertTrue(KatDramaParser.cards(doc, base).isEmpty())
        assertFalse(KatDramaParser.hasNextPage(doc, 500))
    }

    @Test fun seriesDetails() {
        val details = KatDramaParser.details(Jsoup.parse(resource("detail-tv.html")), season = 1)!!
        assertEquals("Queen of Tears", details.title)
        assertEquals(2024, details.year)
        assertEquals("8.4", details.rating)
        assertEquals("https://image.tmdb.org/t/p/w500/7ZXLZ3KYL3IVvsSHBZaHjcNQzNU.jpg", details.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/wcP3FsRLog4GNEs9PFrDKKQdcof.jpg", details.backdrop)
        assertEquals(listOf("Drama", "Comedy"), details.genres)
        assertTrue(details.plot!!.startsWith("The queen of department stores"))
        assertEquals("Kim Soo-hyun", details.cast[0].name)
        assertEquals("Baek Hyun-woo", details.cast[0].role)
        assertEquals(listOf(1), details.seasons)
        assertEquals((1..16).toList(), details.episodes.map { it.number })
        val first = details.episodes[0]
        assertEquals(1, first.season)
        assertEquals("2024-03-09", first.date)
        assertTrue(first.still!!.startsWith("https://image.tmdb.org/t/p/w500/"))
    }

    @Test fun otherSeasonsAreSeparatePages() {
        val details = KatDramaParser.details(Jsoup.parse(resource("detail-tv-season2.html")), season = 2)!!
        assertEquals("Arcane", details.title)
        assertEquals(listOf(1, 2), details.seasons)
        assertEquals((1..9).toList(), details.episodes.map { it.number })
        assertTrue(details.episodes.all { it.season == 2 })
    }

    @Test fun movieDetails() {
        val details = KatDramaParser.details(Jsoup.parse(resource("detail-movie.html")), season = 1)!!
        assertEquals("Hope", details.title)
        assertEquals(2026, details.year)
        assertEquals(listOf("Science Fiction", "Mystery", "Action"), details.genres)
        assertTrue(details.episodes.isEmpty())
    }

    @Test fun server2IframeCarriesPlaylistAndSubtitles() {
        val resolved = KatDramaParser.server2(Jsoup.parse(resource("server2.html")))!!
        assertEquals("Queen of Tears", resolved.title)
        assertEquals(2024, resolved.year)
        assertEquals(listOf("https://aapanel.devcorp.me/assets/e4004682-c087-5127-ad19-e1a3b57ba1ac.m3u8"), resolved.urls)
        assertEquals("English", resolved.subtitles[0].first)
        assertTrue(resolved.subtitles[0].second.endsWith(".vtt"))
        assertTrue(resolved.subtitles.size > 20)
    }

    @Test fun server2ErrorPageResolvesNothing() {
        assertNull(KatDramaParser.server2(Jsoup.parse("""<div class="error">HAPI returned HTTP 502.</div>""")))
    }

    @Test fun server6SourcesAreUniqueByUrl() {
        val (sources, subtitles) = KatDramaParser.server6(resource("server6.html"))
        assertEquals(listOf("Kisskh", "Castle"), sources.map { it.provider })
        assertEquals("https://hls.cdnvideo11.shop/hls07/8714/Ep1_index.m3u8", sources[0].url)
        assertEquals("1080p", sources[0].quality)
        // Castle is labelled 1080p but the URL is the 720p rendition.
        assertEquals("720p", sources[1].quality)
        assertEquals("English", subtitles[0].first)
        assertEquals("https://sub.cdnvideo11.shop/Queen.of.Tears.2024.Ep1.en.srt", subtitles[0].second)
        assertEquals(5, subtitles.size)
    }

    @Test fun server6EmptyForMovies() {
        val (sources, subtitles) = KatDramaParser.server6("<script>\nconst SOURCES = [];\nconst SUBTITLES = [];\n</script>")
        assertTrue(sources.isEmpty())
        assertTrue(subtitles.isEmpty())
    }

    @Test fun server7AndVidbasicMirrors() {
        val resolved = KatDramaParser.server7(resource("server7.json"))!!
        assertEquals("Queen of Tears", resolved.title)
        assertEquals(2024, resolved.year)
        assertEquals(listOf("https://vidbasic.top/embed/ppawjtdl4j2"), resolved.urls)
        assertNull(KatDramaParser.server7("""{"success":false,"error":"No matching video stream found on Server 2 for: Arcane","title":"Arcane"}"""))
        val mirrors = KatDramaParser.mirrors(resource("vidbasic.html"))
        assertTrue(mirrors.containsAll(listOf(
            "https://dood.wf/e/pm9th37c24ay",
            "https://hglink.to/e/g9fa9n8dmbyu",
            "https://mixdrop.ps/e/gn8p17erfd7v7w",
            "https://watchadsontape.com/e/bZxDpeylMVCP8wb",
        )))
    }

    @Test fun titleMatchRejectsOtherShows() {
        assertTrue(KatDramaParser.titleMatches("Queen of Tears", 2024, "Queen of Tears", 2024))
        assertTrue(KatDramaParser.titleMatches("Hope", 2026, "HOPE", 2026))
        assertTrue(KatDramaParser.titleMatches("Love Between Lines", 2026, "Love between Lines", null))
        // Seen live: Arcane → "EXO Arcade 2"; Turkish remake vs the Korean original.
        assertFalse(KatDramaParser.titleMatches("Arcane", 2021, "EXO Arcade 2", 2021))
        assertFalse(KatDramaParser.titleMatches("Queen of Tears", 2025, "Queen of Tears", 2024 - 1))
        assertFalse(KatDramaParser.titleMatches("Arcane", null, "Arcane: League of Legends", null))
        assertFalse(KatDramaParser.titleMatches("Arcane", null, null, null))
    }
}
