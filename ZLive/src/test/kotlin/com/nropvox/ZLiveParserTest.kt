package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Responses saved from iptv.zlive.st on 2026-10-29 (src/test/resources). */
class ZLiveParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val utc = TimeZone.getTimeZone("UTC")

    // Built with the recon folder's scripts/client.py, whose bodies /resolve accepted, using a fixed iv.
    @Test fun sealMatchesTheSiteScheme() {
        val payload = ZLiveParser.resolvePayload("auto-espn", 1793235749)
        assertEquals("""{"s":"auto-espn","u":1793235749}""", payload)
        val sealed = ZLiveParser.seal(payload, "Bk5Evyjt3eqUPPvXb4X27w", "2026-10-29", ByteArray(12) { it.toByte() })
        assertEquals("OnJujjyQ1+D5BS1boiZm8KafSF9NGj0KXRJuRQo23qQ=", sealed.a)
        assertEquals("AAECAwQFBgcICQoL", sealed.b)
        assertEquals("gU+hzqOFnGuYfCeuXalp3Q==", sealed.c)
        assertEquals(
            """{"a":"OnJujjyQ1+D5BS1boiZm8KafSF9NGj0KXRJuRQo23qQ=","b":"AAECAwQFBgcICQoL","c":"gU+hzqOFnGuYfCeuXalp3Q==","d":"2026-10-29","e":"Bk5Evyjt3eqUPPvXb4X27w"}""",
            ZLiveParser.sealedJson(sealed)
        )
        assertEquals("""{"u":1793235748}""", ZLiveParser.streamsPayload(1793235748))
    }

    @Test fun dayIsUtc() {
        assertEquals("2026-10-29", ZLiveParser.utcDay(1793235748_000))   // 2026-10-29T01:02:28Z
        assertEquals("2026-10-28", ZLiveParser.utcDay(1793231999_000))   // 2026-10-28T23:59:59Z
    }

    @Test fun channelListSkipsBrokenOnes() {
        val channels = ZLiveParser.channels(text("channels.json"))
        assertEquals(252, channels.size)
        assertTrue(channels.none { it.name!!.contains("[broken]") })
        assertEquals("adult-swim-test", channels.first().id)
        assertTrue(channels.all { it.sources.singleOrNull()?.key == it.id })
        val rows = channels.groupingBy(ZLiveParser::row).eachCount()
        assertEquals(mapOf("Sports" to 159, "Other" to 66, "Kids" to 19, "Entertainment" to 5, "F1" to 3), rows)
        assertTrue(ZLiveParser.channels("unauthenticated").isEmpty())
    }

    @Test fun eventsParse() {
        val events = ZLiveParser.events(text("streams.json"))
        assertEquals(36, events.size)
        val first = events.first()
        assertEquals("auto-evt-10-28-26-millbridge-speedway", first.source?.key)
        assertEquals(
            "https://dirtvision.asset.viewlift.com/images/2026/01/16/10-28-26_Millbridge-1768580749672.png?impolicy=resize&w=1920&h=1080",
            ZLiveParser.cleanUrl(first.thumbnail)
        )
        assertEquals("Motorsport · started Wed 18:15", ZLiveParser.eventPlot(first, 1793235748_000, utc))
        assertEquals("Motorsport · starts Wed 18:15", ZLiveParser.eventPlot(first, 0, utc))
        assertTrue(ZLiveParser.events("[]").isEmpty())
    }

    @Test fun pageUrls() {
        assertEquals("auto-abc", ZLiveParser.pageId("https://zlive.st/watch/auto-abc"))
        assertEquals("TSN4", ZLiveParser.pageId("https://zlive.st/embed/TSN4?autoplay=1"))
        assertEquals("auto-abc", ZLiveParser.pageId(ZLiveParser.watchUrl("https://zlive.st", "auto-abc")))
        assertNull(ZLiveParser.pageId("https://zlive.st/schedule"))
    }

    @Test fun resolveAnswers() {
        val tims = ZLiveParser.location(text("resolve-tims.json"))!!
        assertTrue(tims.startsWith("https://tims-001.sytes.net/main/secure/"))
        assertFalse(ZLiveParser.isDecoy(tims))
        assertTrue(ZLiveParser.isDecoy(ZLiveParser.location(text("resolve-decoy.json"))!!))
        assertNull(ZLiveParser.location("""{"error":"bad request"}"""))
    }

    @Test fun labels() {
        assertEquals(1080, ZLiveParser.quality("FHD"))
        assertEquals(720, ZLiveParser.quality("HD"))
        assertEquals(2160, ZLiveParser.quality("4K"))
        assertNull(ZLiveParser.quality(""))
        assertEquals("https://flagcdn.com/w320/gb.png", ZLiveParser.flagPoster("gb"))
        assertNull(ZLiveParser.flagPoster(""))
        assertEquals("Other", ZLiveParser.row(ZChannel(id = "x", name = "x", sport = "")))
    }

    @Test fun epgPlot() {
        val epg = ZLiveParser.epg(text("epg-adult-swim-test.json"))!!
        val plot = ZLiveParser.epgPlot(epg, utc)!!
        assertTrue(plot, plot.startsWith("Now 01:00–01:30: Family Guy\nPeter discovers"))
        assertTrue(plot, plot.contains("\n\nNext 01:30–02:00: Family Guy\nLois becomes"))
        assertNull(ZLiveParser.epg("""{"error":"no epg for this channel"}"""))
    }
}
