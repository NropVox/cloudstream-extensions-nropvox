package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses saved from kisskh.ovh on 2026-10-29 (src/test/resources); kkeys were accepted by the live API. */
class KissKHParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val main = "https://kisskh.ovh"

    @Test fun hashLeavesInt32Range() {
        val plain = "|185459||mg3c3b04ba|2.8.10|${KissKHParser.VIDEO_GUID}|4830201|kisskh|kisskh|kisskh|kisskh|kisskh|kisskh|00|"
        assertEquals(13462550642L, KissKHParser.jsHash(plain))
    }

    @Test fun kkeyMatchesSite() {
        assertEquals(
            "8B929460CC02E9DEE11F00064AAF5F6790D4EEBBE323AA57A2E4A0A705F0FEF0D9608A53802F92683A1BAB076D9C7FCAD194AEE4C332B93C1E47916583BD90BFA6CA916D816F2E662B45F01C95EEF6D48450B29BD4476D9DA704C9E26D14DD0D569F88E3F9E2F8665F2DE9803755D85EBF139BE6040BD0EAC06E86062F948E8F",
            KissKHParser.kkey(185459, KissKHParser.VIDEO_GUID)
        )
        assertEquals(
            "E2F91316CE2A5C30EBF0112DAC54D714F45993C06E28066992C41D7B54568DC3FAE8B47DA3FBB81AB2110BF0DA8EB0BEC80A67CAFB23403F291C86E3AEF431079A449D117B02361DC48CEC0E14BF1A180B6CAED6CD2E4D11634C575769720967E8A92A489DF6C0C7BC3DA6D2EF384EC701E32690C24DB5854F60CEA12B55BE2B",
            KissKHParser.kkey(185459, KissKHParser.SUB_GUID)
        )
        assertTrue(KissKHParser.kkey(1, KissKHParser.VIDEO_GUID).startsWith("23DC3EEF3D9B5DF88849AF476B008D4A"))
    }

    @Test fun urlsRoundTrip() {
        val drama = KissKHParser.dramaUrl(main, "The Lament of Autumn (2026)", 13907)
        assertEquals("$main/Drama/The-Lament-of-Autumn--2026-?id=13907", drama)
        assertEquals(13907, KissKHParser.dramaId(drama))
        val episode = KissKHParser.episodeUrl(main, "Revenged Love", 10826, "1", 185459)
        assertEquals("$main/Drama/Revenged-Love/Episode-1?id=10826&ep=185459", episode)
        assertEquals(185459, KissKHParser.episodeId(episode))
        assertEquals(10826, KissKHParser.dramaId(episode))
        assertNull(KissKHParser.episodeId(drama))
    }

    @Test fun numbersAndYears() {
        assertEquals("24", KissKHParser.episodeNumber(24.0))
        assertEquals("12.5", KissKHParser.episodeNumber(12.5))
        assertEquals(2026, KissKHParser.yearOf("The Lament of Autumn (2026)"))
        assertNull(KissKHParser.yearOf("Revenged Love"))
    }

    @Test fun listPaging() {
        val page = mapper.readValue<KissPage>(text("list-p1.json"))
        assertEquals(10, page.data.size)
        assertEquals(13000, page.data.first().id)
        assertTrue(KissKHParser.hasNext(page))
        assertFalse(KissKHParser.hasNext(page.copy(page = 1318)))
    }

    @Test fun dramaDetails() {
        val series = mapper.readValue<KissDrama>(text("drama-10826.json"))
        assertEquals("TVSeries", series.type)
        assertEquals(24, series.episodes.size)
        assertEquals(24.0, series.episodes.first().number!!, 0.0)
        assertEquals(185459, series.episodes.last().id)
        val movie = mapper.readValue<KissDrama>(text("drama-movie-6637.json"))
        assertEquals("Movie", movie.type)
        assertEquals(listOf(113259), movie.episodes.map { it.id })
    }

    @Test fun videoResponses() {
        val hls = mapper.readValue<KissVideo>(text("episode-185459.json"))
        assertEquals(1, hls.type)
        assertEquals("https://hls.cdnvideo11.shop/hls07/10826/Ep1_index.m3u8", hls.video)
        val fresh = mapper.readValue<KissVideo>(text("episode-225438.json"))
        assertTrue(fresh.video!!.startsWith("https://tmp.cdnvideo11.shop/tmp/One-Piece-Ep1180/index.m3u8"))
    }

    @Test fun subtitleExtensions() {
        val subs = mapper.readValue<List<KissSubtitle>>(text("sub-185459.json"))
        assertEquals("English", subs.first().label)
        assertEquals("txt1", KissKHParser.subtitleExtension(subs.first().src!!))
        assertTrue(subs.all { KissKHParser.isEncryptedSubtitle(it.src!!) })
        assertEquals("srt", KissKHParser.subtitleExtension("https://sub.cdnvideo11.shop/Dangerous-Drugs-of-Sex-Ep1.en.srt"))
        assertEquals("txt", KissKHParser.subtitleExtension("https://sub.x/a.en.srt.txt#t"))
    }

    @Test fun decryptsTxt1Cues() {
        val lines = KissKHParser.decryptSubtitle(text("sub-en.txt1"), "txt1").lines()
        assertEquals(listOf("1", "00:00:17,152 --> 00:00:19,208", "<i>♫ Set off ♫</i>", ""), lines.take(4))
        assertEquals("<i>♫ With the latte I just brewed ♫</i>", lines[6])
        assertFalse(lines.any { it.endsWith("=") })
    }

    @Test fun plainCuesPassThrough() {
        val srt = text("sub-plain.srt")
        assertEquals(srt.lines(), KissKHParser.decryptSubtitle(srt, "txt").lines())
    }
}
