package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload shapes follow Fetcharr's docs/fetcharr-android-intents.md and contract/intent/ fixtures. */
class FetcharrPayloadTest {
    private val ua = "CS-UA"
    private val mapper = ObjectMapper()

    private fun json(payload: FetcharrPayload): JsonNode = mapper.readTree(FetcharrPayloadBuilder.toJson(payload))

    private fun link(
        source: String = "Vidhost",
        name: String = "Vidhost",
        quality: Int = 1080,
        url: String = "https://cdn.example/v/master.m3u8?token=a%2Fb&sig=XYZ",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
    ) = LinkInfo(source, name, quality, url, referer, headers)

    private fun episode(season: Int? = 1, episode: Int = 2, name: String? = "Episode Two", year: Int? = 2026) =
        MediaMeta("Example Show", name, season, episode, isMovie = false, year = year)

    @Test fun movieSingle() {
        val meta = MediaMeta("Ünïcode Movie 東京", "Ünïcode Movie 東京", null, 0, isMovie = true, year = 2026)
        val root = json(FetcharrPayloadBuilder.single(meta, link(referer = "https://provider.example/"), emptyList(), ua))
        val single = root["single"]
        assertEquals("Ünïcode Movie 東京", single["title"].asText())
        assertEquals(2026, single["year"].asInt())
        assertFalse(single.has("showName") || single.has("season") || single.has("episode") || single.has("subtitles"))
        assertFalse(root.has("batch") || root.has("category"))
        assertEquals("https://provider.example/", root["headers"]["Referer"].asText())
    }

    @Test fun signedUrlIsPreservedExactly() {
        val url = "https://cdn.example/v/master.m3u8?token=a%2Fb&sig=XYZ"
        val root = json(FetcharrPayloadBuilder.single(episode(), link(url = url), emptyList(), ua))
        assertEquals(url, root["single"]["url"].asText())
    }

    @Test fun episodeSingle() {
        val single = json(FetcharrPayloadBuilder.single(episode(), link(), emptyList(), ua))["single"]
        assertEquals("Example Show", single["showName"].asText())
        assertEquals(1, single["season"].asInt())
        assertEquals(2, single["episode"].asInt())
        assertEquals("Episode Two", single["title"].asText())
    }

    @Test fun seasonAndEpisodeZeroAreKept() {
        val single = json(FetcharrPayloadBuilder.single(episode(season = 0, episode = 0), link(), emptyList(), ua))["single"]
        assertTrue(single["season"].isInt)
        assertEquals(0, single["season"].asInt())
        assertEquals(0, single["episode"].asInt())
    }

    @Test fun unknownSeasonFallsBackSoFetcharrFilesAnEpisode() {
        val single = json(
            FetcharrPayloadBuilder.single(episode(season = null, episode = 7, name = null, year = null), link(), emptyList(), ua)
        )["single"]
        assertEquals(FetcharrPayloadBuilder.FALLBACK_SEASON, single["season"].asInt())
        assertEquals(7, single["episode"].asInt())
        assertFalse(single.has("year"))
        assertFalse(single.has("title"))
    }

    @Test fun knownEpisodeWithoutNameOmitsTitle() {
        val single = json(FetcharrPayloadBuilder.single(episode(name = null), link(), emptyList(), ua))["single"]
        assertFalse(single.has("title"))
    }

    @Test fun headersFollowPlayerPrecedence() {
        val headers = FetcharrPayloadBuilder.effectiveHeaders(
            link(referer = "https://ignored.example/", headers = mapOf("referer" to "https://own.example/", "user-agent" to "Own")),
            ua,
        )
        assertEquals(mapOf("referer" to "https://own.example/", "user-agent" to "Own"), headers)

        val defaults = FetcharrPayloadBuilder.effectiveHeaders(link(referer = "https://r.example/", headers = mapOf("Cookie" to "c=1")), ua)
        assertEquals(mapOf("Cookie" to "c=1", "Referer" to "https://r.example/", "User-Agent" to ua), defaults)
    }

    @Test fun subtitlesKeepOnlyEnglishAndDropUnrepresentable() {
        val videoHeaders = mapOf("Referer" to "https://r.example/")
        val subs = FetcharrPayloadBuilder.subtitles(
            listOf(
                SubtitleInfo("https://s.example/en.vtt", emptyMap(), "en-US", isUrl = true),
                SubtitleInfo("https://s.example/en.vtt", emptyMap(), "en", isUrl = true),
                SubtitleInfo("https://s.example/x.vtt", emptyMap(), null, isUrl = true),
                SubtitleInfo("https://s.example/fil.vtt", mapOf("referer" to "https://r.example/"), "fil", isUrl = true),
                SubtitleInfo("https://s.example/auth.vtt", mapOf("Authorization" to "x"), "es", isUrl = true),
                SubtitleInfo("file:///sdcard/sub.srt", emptyMap(), "en", isUrl = false),
                SubtitleInfo("content://sub", emptyMap(), "en", isUrl = true),
            ),
            videoHeaders,
        )
        assertEquals(
            listOf(
                FetcharrSubtitle("https://s.example/en.vtt", "en"),
            ),
            subs,
        )
    }

    @Test fun subtitleLangIsEnglish() {
        val root = json(
            FetcharrPayloadBuilder.single(
                episode(), link(), listOf(SubtitleInfo("https://s.example/e.vtt", emptyMap(), "en-GB", true)), ua
            )
        )
        val sub = root["single"]["subtitles"][0]
        assertEquals("https://s.example/e.vtt", sub["url"].asText())
        assertEquals("en", sub["lang"].asText())
    }

    @Test fun batchHasPerEpisodeHeadersSortedAndDeduplicated() {
        val root = json(
            FetcharrPayloadBuilder.batch(
                "Example Show",
                2,
                2026,
                listOf(
                    SeasonEntry(3, "Three", link(url = "https://cdn.example/3.mp4", referer = "https://r3.example/"), emptyList()),
                    SeasonEntry(1, null, link(url = "https://cdn.example/1.mp4"), emptyList()),
                    SeasonEntry(3, "Dup", link(url = "https://cdn.example/3b.mp4"), emptyList()),
                ),
                ua,
            )
        )
        assertFalse(root.has("single") || root.has("headers") || root.has("category"))
        val batch = root["batch"]
        assertEquals(2, batch["season"].asInt())
        assertEquals(2026, batch["year"].asInt())
        val eps = batch["episodes"]
        assertEquals(listOf(1, 3), eps.map { it["episode"].asInt() })
        assertFalse(eps[0].has("title"))
        assertEquals("https://cdn.example/3.mp4", eps[1]["url"].asText())
        assertEquals("https://r3.example/", eps[1]["headers"]["Referer"].asText())
        assertEquals(ua, eps[0]["headers"]["User-Agent"].asText())
    }

    @Test fun batchWithoutSeasonUsesFallback() {
        val batch = json(
            FetcharrPayloadBuilder.batch("Show", null, null, listOf(SeasonEntry(1, null, link(), emptyList())), ua)
        )["batch"]
        assertEquals(FetcharrPayloadBuilder.FALLBACK_SEASON, batch["season"].asInt())
        assertFalse(batch.has("year"))
    }

    @Test fun pickMatchingPrefersSameSourceThenClosestQuality() {
        val template = link(source = "A", name = "A HLS", quality = 720)
        val candidates = listOf(
            link(source = "B", name = "B", quality = 720),
            link(source = "A", name = "A MP4", quality = 720),
            link(source = "A", name = "A HLS", quality = 1080),
            link(source = "A", name = "A HLS", quality = 480),
        )
        // Same source and label win; 480 is closer to 720 (240 away) than 1080 (360 away).
        assertEquals(candidates[3], FetcharrPayloadBuilder.pickMatching(template, candidates) { it })
        val tie = listOf(link(source = "A", name = "A HLS", quality = 600), link(source = "A", name = "A HLS", quality = 840))
        assertEquals(840, FetcharrPayloadBuilder.pickMatching(template, tie) { it }!!.quality)
        assertEquals("B", FetcharrPayloadBuilder.pickMatching(template, listOf(link(source = "B", name = "B"))) { it }!!.source)
        assertNull(FetcharrPayloadBuilder.pickMatching(template, emptyList<LinkInfo>()) { it })
    }

    @Test fun iso6391() {
        assertEquals("pt", FetcharrPayloadBuilder.iso6391("pt-BR"))
        assertEquals("en", FetcharrPayloadBuilder.iso6391("EN"))
        assertNull(FetcharrPayloadBuilder.iso6391("fil"))
        assertNull(FetcharrPayloadBuilder.iso6391(null))
    }

    @Test fun yearFromLabel() {
        assertEquals(2019, FetcharrPayloadBuilder.yearFrom("2019 - 2023"))
        assertNull(FetcharrPayloadBuilder.yearFrom("Ongoing"))
        assertNull(FetcharrPayloadBuilder.yearFrom(null))
    }

    @Test fun httpOnly() {
        assertTrue(FetcharrPayloadBuilder.isHttpUrl("HTTPS://x"))
        assertFalse(FetcharrPayloadBuilder.isHttpUrl("magnet:?xt=1"))
    }
}
