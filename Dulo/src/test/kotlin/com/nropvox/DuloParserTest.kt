package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses saved from dulo.mov and its Workers on 2026-10-29 (src/test/resources). */
class DuloParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val domain = "iamwatchingyou.site"

    @Test fun configNamesWorkerDomain() {
        assertEquals(domain, DuloParser.workerDomain(text("dulo-config.js")))
        assertNull(DuloParser.workerDomain("window.__CONFIG__ = {}"))
    }

    @Test fun titleUrlsRoundTrip() {
        assertEquals("tv" to 1396, DuloParser.titleRef("https://dulo.mov/title/tv/1396/"))
        assertEquals("movie" to 1233413, DuloParser.titleRef("https://dulo.mov/watch/movie/1233413"))
        assertEquals("tv" to 1396, DuloParser.titleRef(DuloParser.titleUrl("https://dulo.mov", "tv", 1396)))
        assertNull(DuloParser.titleRef("https://dulo.mov/search?q=x"))
    }

    @Test fun zcdnQueryByType() {
        assertEquals(
            "https://zcdn2.$domain/cdn?tmdb=1233413&type=movie",
            DuloParser.zcdnUrl(domain, 2, DuloLink(1233413, "movie"))
        )
        assertEquals(
            "https://zcdn1.$domain/cdn?tmdb=1396&type=tv&season=1&episode=3",
            DuloParser.zcdnUrl(domain, 1, DuloLink(1396, "tv", season = 1, episode = 3))
        )
    }

    @Test fun readyZcdnGivesPlayUrl() {
        val url = DuloParser.zcdnPlayUrl(text("zcdn-movie-1233413.json"), domain)!!
        assertTrue(url.startsWith("https://zcdn1.$domain/play/"))
        assertTrue(url.contains("/~"))
    }

    @Test fun buildingOrForeignZcdnGivesNothing() {
        assertNull(DuloParser.zcdnPlayUrl("""{"ready":false,"status":"building"}""", domain))
        assertNull(DuloParser.zcdnPlayUrl("""{"ready":true,"url":"https://evil.example/play/a/master.m3u8"}""", domain))
        assertNull(DuloParser.zcdnPlayUrl("not json", domain))
    }

    @Test fun masterTopQuality() {
        assertEquals(2160, DuloParser.topHeight(text("zcdn-movie-master.m3u8")))
        assertEquals(1080, DuloParser.topHeight("#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x800\nx"))
        assertNull(DuloParser.topHeight("#EXTM3U\n#EXTINF:6,\nseg"))
    }

    @Test fun resolveQueryEncodesTitle() {
        val query = DuloParser.resolveQuery(DuloLink(127532, "tv", "Solo Leveling", 2024, season = 1, episode = 1))
        assertEquals(
            "tmdbId=127532&type=tv&progressive=true&seasonId=1&episodeId=1&title=Solo+Leveling&year=2024",
            query
        )
        assertEquals(
            "tmdbId=1233413&type=movie&progressive=true",
            DuloParser.resolveQuery(DuloLink(1233413, "movie"))
        )
    }

    @Test fun resolveAnswerSources() {
        val answer = DuloParser.resolveAnswer(text("resolve-tv-127532-1-1.json"))!!
        assertTrue(answer.found)
        assertFalse(answer.pending)
        assertEquals(listOf("Shinobi Dub · 1080p", "Cobalt · 720p"), answer.sources.map { it.label })
        assertTrue(answer.sources.all { it.url!!.startsWith("https://zstream-proxy1.$domain/?payload=") })
        val pending = DuloParser.resolveAnswer("""{"found":false,"pending":true,"sources":[]}""")!!
        assertTrue(pending.pending)
    }

    @Test fun labelsSplitServerAndQuality() {
        assertEquals("Cobalt" to 720, DuloParser.splitLabel("Cobalt · 720p"))
        assertEquals("Shinobi Dub" to 1080, DuloParser.splitLabel("Shinobi Dub · 1080p"))
        assertEquals("Breeze" to null, DuloParser.splitLabel("Breeze"))
        assertEquals("Pickle" to 2160, DuloParser.splitLabel("Pickle · 4K"))
    }

    @Test fun subtitleTracks() {
        val tracks = DuloParser.subtitles(text("subtitles-tv-1396-1-1.json"))
        assertEquals(84, tracks.size)
        assertEquals("Arabic", tracks.first().display)
        assertEquals("https://cache.vdrk.site/v1/vtt/tv/1396/1/1/Arabic.vtt", tracks.first().url)
        assertTrue(tracks.any { it.language == "en" })
        assertTrue(DuloParser.subtitles("[]").isEmpty())
    }

    @Test fun animeIsJapaneseAnimation() {
        assertTrue(DuloParser.isAnime(listOf(16, 10759), listOf("JP")))
        assertFalse(DuloParser.isAnime(listOf(16), listOf("US")))
        assertFalse(DuloParser.isAnime(null, null))
    }
}
