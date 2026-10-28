package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pages saved from 4khdhub.one and its redirector on 2026-10-28 (src/test/resources). */
class FourKHDHubParserTest {
    private val base = "https://4khdhub.one"

    private fun page(name: String): Document = Jsoup.parse(text(name), base)
    private fun text(name: String): String = javaClass.classLoader!!.getResource(name)!!.readText()
    private val decode: (String) -> String = { String(Base64.getDecoder().decode(it)) }

    @Test fun homeCardsCarryTypeYearAndPoster() {
        val cards = FourKHDHubParser.cards(page("home.html"), base)
        assertEquals(18, cards.size)
        val verity = cards.single { it.title == "Verity" }
        assertEquals("https://4khdhub.one/verity-movie-8427/", verity.url)
        assertEquals(2026, verity.year)
        assertFalse(verity.series)
        assertEquals("https://image.tmdb.org/t/p/w500/zPHfsaiMZT1HNz1ZS55iyLKZkFG.jpg", verity.poster)
        val punisher = cards.single { it.title == "Marvel's The Punisher" }
        assertTrue(punisher.series)
        assertEquals(2017, punisher.year)
        assertTrue(FourKHDHubParser.hasNext(page("home.html")))
    }

    @Test fun searchPageHasCards() {
        val cards = FourKHDHubParser.cards(page("search-batman.html"), base)
        assertEquals(18, cards.size)
        assertEquals("Batman", cards.first().title)
    }

    @Test fun movieDetails() {
        val doc = page("movie-verity.html")
        assertEquals("Verity", FourKHDHubParser.title(doc))
        assertEquals(2026, FourKHDHubParser.year(doc))
        assertTrue(FourKHDHubParser.plot(doc)!!.startsWith("Lowen Ashleigh, hired to ghostwrite"))
        assertEquals("https://image.tmdb.org/t/p/w780/zPHfsaiMZT1HNz1ZS55iyLKZkFG.jpg", FourKHDHubParser.poster(doc))
        assertEquals(listOf("Mystery", "Thriller"), FourKHDHubParser.genres(doc))
        assertEquals("https://www.youtube.com/watch?v=xdPMKhjMSFs", FourKHDHubParser.trailer(doc))
        assertEquals(1283515, FourKHDHubParser.tmdbId(text("movie-verity.html")))
        val stars = FourKHDHubParser.actors(FourKHDHubParser.metadata(doc)["Stars"])
        assertEquals("Anne Hathaway" to "Verity Crawford", stars.first())
        assertEquals(5, stars.size)
        assertFalse(FourKHDHubParser.isAnime(doc))
    }

    @Test fun movieFilesListHubCloudFirst() {
        val files = FourKHDHubParser.movieFiles(page("movie-verity.html"))
        assertEquals(4, files.size)
        val first = files.first()
        assertTrue(first.name.startsWith("Verity (2026) 2160p HDR10+ DV AMZN WEB-DL HEVC"))
        assertEquals("14.36 GB", first.size)
        assertEquals(2, first.urls.size)
        assertTrue(first.urls[0].endsWith("Q1dxTzFnbjhrQm5kMG5lL01tQzcvdEhwZ0JtZVdjNUEvbXdPRHcyWGVHWHU2a09ueHp6a0NMUHF2L29SQURzcDA3QkJ0U25oVVV1SmhtcUtFSHlnb1E9PQ=="))
        assertEquals(2160, FourKHDHubParser.quality(first.name))
        assertEquals("2160p HDR10+ DV WEB-DL HEVC x265 Atmos", FourKHDHubParser.tags(first.name))
    }

    @Test fun seriesEpisodesMergeAcrossQualityBlocks() {
        val doc = page("series-wanda.html")
        assertEquals("Where's Wanda?", FourKHDHubParser.title(doc))
        val episodes = FourKHDHubParser.episodes(doc)
        // S02: 5 quality blocks × 2 episodes; S01: 5 × 8.
        assertEquals(50, episodes.size)
        val keys = episodes.map { it.season to it.episode }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals((1..8).map { 1 to it } + listOf(2 to 1, 2 to 2), keys)
        // Blocks list S02 newest first.
        assertEquals(2 to 2, episodes.first().let { it.season to it.episode })
        assertTrue(episodes.first().file.name.contains("S02E02"))
        assertEquals(2, episodes.first().file.urls.size)
        assertTrue(FourKHDHubParser.movieFiles(doc).isNotEmpty()) // season packs, ignored for series
    }

    @Test fun redirectorDecodesToHubCloud() {
        assertEquals(
            "https://hubcloud.ist/drive/0lymmyaxdyc15gy",
            FourKHDHubParser.redirectTarget(text("redirect-greenmotors.html"), decode)
        )
        assertNull(FourKHDHubParser.redirectTarget("<html>no payload</html>", decode))
        assertEquals("Uryyb", FourKHDHubParser.rot13("Hello"))
    }

    @Test fun hubdrivePointsToSameHubCloudDrive() {
        assertEquals("https://hubcloud.ist/drive/0lymmyaxdyc15gy", FourKHDHubParser.hubdriveTarget(page("hubdrive-file.html")))
    }

    @Test fun seriesUrls() {
        assertTrue(FourKHDHubParser.isSeries("https://4khdhub.one/marvels-the-punisher-series-2288/"))
        assertFalse(FourKHDHubParser.isSeries("https://4khdhub.one/verity-movie-8427/"))
        assertFalse(FourKHDHubParser.isSeries("https://4khdhub.one/series-of-unfortunate-movie-12/"))
    }
}
