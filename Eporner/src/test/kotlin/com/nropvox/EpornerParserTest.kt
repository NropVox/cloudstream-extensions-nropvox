package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpornerParserTest {
    @Test fun videoIdFromBothUrlShapes() {
        assertEquals("oXQ8X6aoX3G", EpornerParser.videoId("https://www.eporner.com/video-oXQ8X6aoX3G/pinay-teen-baddie/"))
        assertEquals("oXQ8X6aoX3G", EpornerParser.videoId("https://www.eporner.com/hd-porn/oXQ8X6aoX3G/Pinay-Teen-Baddie/"))
        assertNull(EpornerParser.videoId("https://www.eporner.com/cat/asian/"))
    }

    @Test fun hashConversionMatchesSite() {
        assertEquals("jkgr4g137jvi27kxqse1rtirh3", EpornerParser.xhrHash("4686ad508d50893a1b5315dee602f497"))
    }

    @Test fun pageHash() {
        assertEquals("4686ad508d50893a1b5315dee602f497",
            EpornerParser.pageHash("EP.video.player.hash = '4686ad508d50893a1b5315dee602f497';"))
    }

    @Test fun sourcesFromXhrSample() {
        val json = javaClass.getResource("/xhr_video.json")!!.readText()
        val s = EpornerParser.sources(json)
        assertEquals(listOf(1440, 1080, 720, 480, 360, 240), s.map { it.quality })
        assertEquals(true, s.first().url.endsWith("-1440p.mp4"))
    }

    @Test fun tagsDropEmpty() {
        assertEquals(listOf("amateur", "homemade", "teens"), EpornerParser.tags("amateur, homemade, , teens, teens"))
    }
}
