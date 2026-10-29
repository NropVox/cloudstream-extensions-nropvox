package com.nropvox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniStreamParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** TMDB episode lists saved as `[{season, episode, air_date}]` from `tv/{id}?append_to_response=season/N`. */
    private fun show(name: String): List<OmniStreamParser.AirEpisode> =
        jacksonObjectMapper().readValue<List<Map<String, Any?>>>(text(name)).map {
            OmniStreamParser.AirEpisode(it["season"] as Int, it["episode"] as Int, it["air_date"] as String?)
        }

    private val jjk = show("tmdb-episodes-95479-jujutsu-kaisen.json")
    private val jjkAni = OmniStreamParser.candidates(text("anilist-jujutsu-kaisen-series.json"))
    private val jjkNames = listOf("Jujutsu Kaisen", "呪術廻戦")

    private fun target(id: Int, episode: Int) = OmniStreamParser.AniTarget(id, episode)

    @Test fun oneTmdbSeasonSplitsIntoAniListCours() {
        assertEquals(target(113415, 1), OmniStreamParser.pickSeries(jjk, 1, 1, jjkAni, jjkNames))
        assertEquals(target(113415, 24), OmniStreamParser.pickSeries(jjk, 1, 24, jjkAni, jjkNames))
        assertEquals(target(145064, 1), OmniStreamParser.pickSeries(jjk, 1, 25, jjkAni, jjkNames))
        assertEquals(target(145064, 23), OmniStreamParser.pickSeries(jjk, 1, 47, jjkAni, jjkNames))
    }

    @Test fun doublePremiereCountsBothEpisodes() {
        // JJK season 3 premiered with TMDB episodes 48 and 49 on the same day.
        assertEquals(target(172463, 1), OmniStreamParser.pickSeries(jjk, 1, 48, jjkAni, jjkNames))
        assertEquals(target(172463, 2), OmniStreamParser.pickSeries(jjk, 1, 49, jjkAni, jjkNames))
    }

    @Test fun splitSeasonsAndFinalParts() {
        val aot = show("tmdb-episodes-1429-attack-on-titan.json")
        val ani = OmniStreamParser.candidates(text("anilist-attack-on-titan-series.json"))
        val names = listOf("Attack on Titan", "進撃の巨人")
        assertEquals(target(16498, 25), OmniStreamParser.pickSeries(aot, 1, 25, ani, names))
        // Junior High (TV, 2015) is a spin-off that aired between seasons 1 and 2; it must never be picked.
        assertEquals(target(20958, 1), OmniStreamParser.pickSeries(aot, 2, 1, ani, names))
        assertEquals(target(99147, 12), OmniStreamParser.pickSeries(aot, 3, 12, ani, names))
        assertEquals(target(104578, 1), OmniStreamParser.pickSeries(aot, 3, 13, ani, names))
        assertEquals(target(110277, 16), OmniStreamParser.pickSeries(aot, 4, 16, ani, names))
        assertEquals(target(131681, 1), OmniStreamParser.pickSeries(aot, 4, 17, ani, names))
    }

    @Test fun spinOffMinisAreNotEpisodes() {
        val frieren = show("tmdb-episodes-209867-frieren.json")
        val ani = OmniStreamParser.candidates(text("anilist-frieren-series.json"))
        val names = listOf("Frieren: Beyond Journey's End", "葬送のフリーレン")
        // The chibi ONAs share the native title and start mid-season; the main entries must still win.
        assertEquals(target(154587, 6), OmniStreamParser.pickSeries(frieren, 1, 6, ani, names))
        assertEquals(target(154587, 28), OmniStreamParser.pickSeries(frieren, 1, 28, ani, names))
        assertEquals(target(182255, 1), OmniStreamParser.pickSeries(frieren, 1, 29, ani, names))
    }

    @Test fun noGuessWhenNothingFits() {
        assertNull(OmniStreamParser.pickSeries(jjk, 1, 1, jjkAni, listOf("Some Other Show")))
        assertNull(OmniStreamParser.pickSeries(jjk, 1, 99, jjkAni, jjkNames))
        val unaired = jjk + OmniStreamParser.AirEpisode(1, 60, null)
        assertNull(OmniStreamParser.pickSeries(unaired, 1, 60, jjkAni, jjkNames))
        assertNull(OmniStreamParser.pickSeries(jjk, 1, 1, emptyList(), jjkNames))
    }

    @Test fun moviesMatchByReleaseDate() {
        val ani = OmniStreamParser.candidates(text("anilist-jujutsu-kaisen-0-movie.json"))
        val names = listOf("Jujutsu Kaisen 0", "劇場版 呪術廻戦 0")
        assertEquals(target(131573, 1), OmniStreamParser.pickMovie("2021-12-24", ani, names))
        assertNull(OmniStreamParser.pickMovie("2016-12-24", ani, names))
        assertNull(OmniStreamParser.pickMovie(null, ani, names))
    }

    @Test fun titlesMatchByPrefixIgnoringPunctuation() {
        assertTrue(OmniStreamParser.titleMatches(listOf("Frieren: Beyond Journey’s End Season 2"), listOf("Frieren: Beyond Journey's End")))
        assertTrue(OmniStreamParser.titleMatches(listOf("JUJUTSU KAISEN"), listOf("Jujutsu Kaisen")))
        assertFalse(OmniStreamParser.titleMatches(listOf("Koisuru ONE PIECE"), listOf("One Piece")))
    }

    @Test fun epochDays() {
        assertEquals(0, OmniStreamParser.epochDay("1970-01-01"))
        assertEquals(19723, OmniStreamParser.epochDay("2024-01-01"))
        assertEquals(19782, OmniStreamParser.epochDay("2024-02-29"))
        assertNull(OmniStreamParser.epochDay("2024"))
    }

    @Test fun kinds() {
        assertEquals(OmniStreamParser.KIND_ANIME, OmniStreamParser.kind(listOf(16, 10759), listOf("JP"), "ja"))
        assertEquals(OmniStreamParser.KIND_ANIME, OmniStreamParser.kind(listOf(16), null, "ja"))
        assertEquals(OmniStreamParser.KIND_GENERAL, OmniStreamParser.kind(listOf(16), listOf("US"), "en"))
        assertEquals(OmniStreamParser.KIND_ASIAN, OmniStreamParser.kind(listOf(18), listOf("KR"), "ko"))
        assertEquals(OmniStreamParser.KIND_ASIAN, OmniStreamParser.kind(listOf(18), null, "zh"))
        assertEquals(OmniStreamParser.KIND_GENERAL, OmniStreamParser.kind(listOf(18, 80), listOf("US"), "en"))
        assertEquals(OmniStreamParser.KIND_GENERAL, OmniStreamParser.kind(null, null, null))
    }

    @Test fun titleRefs() {
        assertEquals("tv" to 1396, OmniStreamParser.titleRef("https://www.themoviedb.org/tv/1396"))
        assertEquals("movie" to 550, OmniStreamParser.titleRef("https://www.themoviedb.org/movie/550"))
        assertNull(OmniStreamParser.titleRef("https://www.themoviedb.org/person/287"))
    }

    @Test fun playlistChecks() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\n720/index.m3u8\n"
        assertTrue(OmniStreamParser.isPlaylist("﻿$master"))
        assertFalse(OmniStreamParser.isPlaylist("<!DOCTYPE html><html>"))
        assertEquals("https://cdn.example/a/720/index.m3u8", OmniStreamParser.firstVariant(master, "https://cdn.example/a/master.m3u8"))
        assertNull(OmniStreamParser.firstVariant("#EXTM3U\n#EXTINF:6,\nseg0.ts\n", "https://cdn.example/a/index.m3u8"))
        assertTrue(OmniStreamParser.isDrm("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\""))
        assertTrue(OmniStreamParser.isDrm("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"urn:uuid:edef8ba9\""))
        assertFalse(OmniStreamParser.isDrm("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""))
        assertFalse(OmniStreamParser.isDrm(master))
    }

    @Test fun finishPolicy() {
        assertFalse(OmniStreamParser.enough(3, 3))
        assertFalse(OmniStreamParser.enough(5, 1))
        assertTrue(OmniStreamParser.enough(4, 2))
        assertTrue(OmniStreamParser.enough(8, 1))
    }
}
