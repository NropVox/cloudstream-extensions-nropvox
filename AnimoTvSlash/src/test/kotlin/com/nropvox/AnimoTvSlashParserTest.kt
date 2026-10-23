package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Values taken from pages saved on 2026-10-23 (recon folder samples/). */
class AnimoTvSlashParserTest {
    private val mirrors = javaClass.getResource("/episode-17-mirrors.html")!!.readText()
    private val options = AnimoTvSlashParser.serverOptions(mirrors)

    @Test fun listAndSearchUrlsPageThroughThePath() {
        val h = "https://animotvslash.org"
        assertEquals("$h/anime/?status=Ongoing&type=&order=update", AnimoTvSlashParser.listUrl(h, "status=Ongoing&type=&order=update", 1))
        assertEquals("$h/anime/page/3/?order=popular", AnimoTvSlashParser.listUrl(h, "order=popular", 3))
        assertEquals("$h/?s=naruto", AnimoTvSlashParser.searchUrl(h, "naruto", 1))
        assertEquals("$h/page/2/?s=one%20piece", AnimoTvSlashParser.searchUrl(h, "one%20piece", 2))
    }

    @Test fun seriesUrlsAreToldFromEpisodesAndArchives() {
        assertTrue(AnimoTvSlashParser.isSeriesUrl("https://animotvslash.org/anime/naruto-x-ut/"))
        assertTrue(AnimoTvSlashParser.isSeriesUrl("https://animotvslash.org/anime/naruto-x-ut"))
        assertFalse(AnimoTvSlashParser.isSeriesUrl("https://animotvslash.org/naruto-x-ut-episode-1/"))
        assertFalse(AnimoTvSlashParser.isSeriesUrl("https://animotvslash.org/anime/?status=completed&type=&order=update"))
        assertFalse(AnimoTvSlashParser.isSeriesUrl("https://animotvslash.org/anime/page/2/"))
    }

    @Test fun serverOptionsKeepLabelsAndIframeSources() {
        assertEquals(
            listOf(
                "Sub - ANIMO-P", "Sub - ANIMO-H", "Sub - ANIMO-M", "Sub - ANIMO-D", "Sub - Hydrax", "Sub - Moon", "Sub - VidHide", "Sub - Animo",
                "SoftSub - ANIMO-P", "SoftSub - Moon", "SoftSub - VidHide", "SoftSub - Animo",
            ),
            options.map { "${it.lang} - ${it.server}" }
        )
        assertEquals("https://abyssplayer.com/4MEvJTSl9", options.first { it.server == "Hydrax" }.src)
        assertEquals("https://bysezoxexe.com/e/fyeesiu30snl", options.first { it.lang == "Sub" && it.server == "Moon" }.src)
        assertEquals("https://animotvslash.p2pplay.pro/#h6fmk9", options.first { it.lang == "Sub" && it.server == "Animo" }.src)
    }

    @Test fun animoPPayloadHoldsMasterAltAndSubs() {
        val sub = options.first { it.lang == "Sub" && it.server == "ANIMO-P" }
        val json = AnimoTvSlashParser.playerPayload(sub.src)!!
        assertTrue(json.contains(""""master":"https://cdn.videas.fr/v-medias/s5/hlsv1/85/b5/85b5f5e4-f586-4f5a-9941-d779838f99c1/playlist.m3u8""""))
        assertTrue(json.contains("https://rumble.com/hls-vod/W-pd_FMV9S4/playlist.m3u8"))
        val soft = AnimoTvSlashParser.playerPayload(options.first { it.lang == "SoftSub" && it.server == "ANIMO-P" }.src)!!
        assertTrue(soft.contains("jukishi-17-sub1-eng-ass-source.ass"))
        assertTrue(soft.contains(""""lang":"eng""""))
    }

    @Test fun wrapperPayloadsUseUrlOrQualityKeys() {
        val plyr = AnimoTvSlashParser.playerPayload(options.first { it.server == "ANIMO-H" }.src)!!
        assertTrue(plyr.contains(""""url":"https:\/\/cdn.videas.fr\/v-medias\/s5\/hlsv1\/85\/b5\/85b5f5e4"""))
        val vidstack = AnimoTvSlashParser.playerPayload(options.first { it.server == "ANIMO-D" }.src)!!
        assertTrue(vidstack.contains(""""url_480":"https:\/\/cdn4.videas.fr\/52dc7f965b4c44c58fe08092a7d72ee8"""))
        assertTrue(vidstack.contains(""""url_720":"""""))
    }

    @Test fun thirdPartyEmbedsHaveNoPayload() {
        assertNull(AnimoTvSlashParser.playerPayload("https://bysezoxexe.com/e/fyeesiu30snl"))
    }

    @Test fun base64AcceptsMissingPaddingAndUrlSafeAlphabet() {
        assertEquals("""{"a":"b"}""", AnimoTvSlashParser.decodeBase64("eyJhIjoiYiJ9"))
        assertEquals("hello??>", AnimoTvSlashParser.decodeBase64("aGVsbG8_Pz4"))
        assertNull(AnimoTvSlashParser.decodeBase64(""))
    }

    @Test fun mp4HeightComesFromTheFileName() {
        val base = "https://cdn4.videas.fr/52dc7f965b4c44c58fe08092a7d72ee8/the-exiled-heavy-knight-knows-how-to-game-the-system-episode-17"
        assertEquals(480, AnimoTvSlashParser.mp4Height("$base-mp4-480p.mp4"))
        assertEquals(1080, AnimoTvSlashParser.mp4Height("$base-mp4-source.mp4"))
        assertEquals(480, AnimoTvSlashParser.mp4Height("https://cdn4.videas.fr/97def837168e441d8d814295de380967/jukishi-17-mkv-480p.mp4"))
        assertNull(AnimoTvSlashParser.mp4Height("$base.mp4"))
    }

    @Test fun episodeStillsAreReadFromTheInlineScript() {
        val html = """<script>window.AAP_EPS = {"1":{"t":"Music Video","i":"https:\/\/artworks.thetvdb.com\/banners\/episodes\/79824\/3468041.jpg"}};</script>"""
        assertEquals(
            """{"1":{"t":"Music Video","i":"https:\/\/artworks.thetvdb.com\/banners\/episodes\/79824\/3468041.jpg"}}""",
            AnimoTvSlashParser.aapEpisodesJson(html)
        )
        assertNull(AnimoTvSlashParser.aapEpisodesJson("<html></html>"))
    }

    @Test fun episodeNumbersAndYears() {
        assertEquals(17, AnimoTvSlashParser.episodeNumber("17"))
        assertEquals(12, AnimoTvSlashParser.episodeNumber("12.5"))
        assertNull(AnimoTvSlashParser.episodeNumber("Special"))
        assertEquals(2011, AnimoTvSlashParser.yearOf("2011-01"))
        assertEquals("a&b", AnimoTvSlashParser.unescape("a&#038;b"))
    }

    @Test fun anilistKeyedServersHaveNoSpaceBeforeTheHyphen() {
        val hd = AnimoTvSlashParser.serverOptions(javaClass.getResource("/movie-episode-mirrors.html")!!.readText())
        assertEquals(
            listOf("Sub -HD0", "Dub -HD0", "Sub -HD1", "Sub -HD3", "SoftSub -HD4", "SoftSub -HD2", "Dub -HD1", "Dub -HD3", "Dub -HD4", "Dub -HD2").map { it.replace(" -", " - ") },
            hd.map { "${it.lang} - ${it.server}" }
        )
        assertEquals("https://megaplay.buzz/stream/ani/21220/1/sub", hd.first { it.lang == "SoftSub" && it.server == "HD4" }.src)
    }

    @Test fun watchEmbedGivesAnilistIdEpisodeAndLanguage() {
        val hd = AnimoTvSlashParser.serverOptions(javaClass.getResource("/movie-episode-mirrors.html")!!.readText())
        assertEquals(
            AnimoTvSlashParser.WatchEmbed("21220", "1", "sub"),
            AnimoTvSlashParser.watchEmbed(hd.first { it.lang == "Sub" && it.server == "HD0" }.src)
        )
        assertEquals("dub", AnimoTvSlashParser.watchEmbed(hd.first { it.lang == "Dub" && it.server == "HD0" }.src)?.lang)
        assertNull(AnimoTvSlashParser.watchEmbed("https://megaplay.buzz/stream/ani/21220/1/sub"))
        assertEquals(720, AnimoTvSlashParser.qualityHeight("720p"))
        assertNull(AnimoTvSlashParser.qualityHeight(null))
    }
}
