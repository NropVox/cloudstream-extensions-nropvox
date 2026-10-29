package com.nropvox

import com.nropvox.StreamCornerParser.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Worker replies and pages saved from streamcorner.st on 2026-10-29 (src/test/resources). */
class StreamCornerParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** The resources hold the reply's `data`; the worker wraps it like this. */
    private fun reply(name: String) = """{"requestId":7,"data":${text(name)}}"""

    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun homepageNamesPreloadBeforeEntry() {
        assertEquals(listOf("/assets/Bk__IvcK.js", "/assets/D-qTnGKO.js"), StreamCornerParser.modulePaths(text("home.html")))
    }

    // Verbatim from the minified Bk__IvcK.js.
    @Test fun dataModuleNamesWorkerAndHosts() {
        val js = """throw new Error("ECTX")}finally{a&&a.remove()}}const cc=new URL("BCJZAtsV.js",import.meta.url).href;let le=null;""" +
            """Commanders:{code:"WAS"}},qo=["data.gigav.workers.dev","data.yedmzoa.workers.dev","data.ngagzipx.workers.dev"],gr="""
        val boot = StreamCornerParser.bootstrap(js)!!
        assertEquals("/assets/BCJZAtsV.js", boot.workerPath)
        assertEquals(listOf("data.gigav.workers.dev", "data.yedmzoa.workers.dev", "data.ngagzipx.workers.dev"), boot.hosts)
        assertNull(StreamCornerParser.bootstrap("""import{a}from"./x.js";new URL(e,"https://streamcorner.local")"""))
    }

    @Test fun messagesMatchTheSite() {
        val beta = StreamCornerParser.provider("beta")!!
        assertEquals(
            """{"operation":"provider-list","requestUrls":["https://data.gigav.workers.dev/corner?p=beta"],"providerId":"beta","providerName":"BETA"}""",
            StreamCornerParser.message("provider-list", beta, listOf("data.gigav.workers.dev"))
        )
        assertEquals(
            """{"operation":"provider-detail","requestUrls":["https://h/corner?p=001&id=9ba96212e6a33e73eeb879a3afa5d1c2"],"providerId":"001","providerName":"#001"}""",
            StreamCornerParser.message("provider-detail", StreamCornerParser.provider("001")!!, listOf("h"), "9ba96212e6a33e73eeb879a3afa5d1c2")
        )
    }

    @Test fun workerErrorsThrow() {
        val error = runCatching { StreamCornerParser.list("""{"requestId":9,"error":"Failed to fetch SlingTV: 403"}""") }.exceptionOrNull()
        assertEquals("Failed to fetch SlingTV: 403", error?.message)
    }

    @Test fun eventList() {
        val items = StreamCornerParser.list(reply("list-beta.json"))
        assertEquals(195, items.size)
        val first = items.first()
        assertEquals("GRNT2b48b9a1bd7a94161b21", StreamCornerParser.id(first))
        assertEquals("College Football: Baylor at UCF", StreamCornerParser.title(first))
        assertEquals(1793403000_000L, StreamCornerParser.startMs(first))
        assertTrue(StreamCornerParser.poster(first)!!.startsWith("https://wsrv.nl/?url="))
    }

    @Test fun nbaGamesUseTeams() {
        val games = StreamCornerParser.list(reply("list-nba.json"))
        assertEquals(15, games.size)
        val game = games.first()
        assertEquals("0022600129", StreamCornerParser.id(game))
        assertEquals("Detroit Pistons @ New York Knicks", StreamCornerParser.title(game))
        assertEquals(Status.ENDED, StreamCornerParser.status(game, 1793237000_000L))
        assertEquals("Final\n97 – 111", StreamCornerParser.plot(game, 1793237000_000L, utc))
    }

    @Test fun statusFollowsTheSchedule() {
        val event = StreamCornerParser.list(reply("list-beta.json")).first() // 23:30–03:00 UTC
        assertEquals(Status.UPCOMING, StreamCornerParser.status(event, 1793237000_000L))
        assertEquals(Status.LIVE, StreamCornerParser.status(event, 1793403000_000L - 30_000))
        assertEquals(Status.LIVE, StreamCornerParser.status(event, 1793415600_000L))
        assertEquals(Status.ENDED, StreamCornerParser.status(event, 1793415600_000L + 1))
        // 24/7 shows have no times, only "LIVE".
        val show = StreamCornerParser.list(reply("list-admin.json")).first { it.streamId == "family-guy" }
        assertEquals(Status.LIVE, StreamCornerParser.status(show, 1793237000_000L))
        // Without an end time an event counts as live for 3.5 h.
        val noEnd = SCItem(streamId = "x", eventName = "x", timestamp = "1793232000")
        assertEquals(Status.LIVE, StreamCornerParser.status(noEnd, 1793232000_000L + 12_600_000))
        assertEquals(Status.ENDED, StreamCornerParser.status(noEnd, 1793232000_000L + 12_600_001))
    }

    @Test fun textTimesParse() {
        assertEquals(1793403000_000L, StreamCornerParser.startMs(SCItem(timeEt = "10/30/26 07:30PM ET")))
        assertEquals(1793319300_000L, StreamCornerParser.startMs(SCItem(timeUtc = "2026-10-30T00:15:00+00:00")))
        assertEquals(1793226600_000L, StreamCornerParser.startMs(SCItem(timeUtc = "2026-10-28 22:30:00")))
        assertNull(StreamCornerParser.startMs(SCItem(timeEt = "LIVE")))
    }

    @Test fun sortingPutsLiveFirstAndDropsEnded() {
        val now = 1793403000_000L + 60_000
        val live = SCItem(streamId = "live", startTime = "1793403000", endTime = "1793415600")
        val later = SCItem(streamId = "later", startTime = "1793500000")
        val soon = SCItem(streamId = "soon", startTime = "1793450000")
        val over = SCItem(streamId = "over", startTime = "1793300000", endTime = "1793310000")
        val ids = { items: List<SCItem> -> items.map { it.streamId } }
        assertEquals(listOf("live", "soon", "later"), ids(StreamCornerParser.sorted(listOf(later, over, soon, live), now, keepEnded = false)))
        assertEquals(listOf("live", "soon", "later", "over"), ids(StreamCornerParser.sorted(listOf(later, over, soon, live), now, keepEnded = true)))
    }

    @Test fun upcomingPlotShowsLocalStart() {
        val event = StreamCornerParser.list(reply("list-beta.json")).first()
        assertEquals("NCAA Football · AMERICAN FOOTBALL\nStarts Fri 30 Oct, 23:30", StreamCornerParser.plot(event, 1793237000_000L, utc))
    }

    @Test fun detailSources() {
        val mlb = StreamCornerParser.detail(reply("detail-mlb.json"))
        val sources = StreamCornerParser.sources(mlb)
        assertEquals(
            listOf("FOX", "FOX FHD", "FOX FHD HDR", "FOX 4K UHD", "FOX DEPORTES", "TNT SPORTS 1", "FOX (EN)"),
            sources.map { it.first }
        )
        assertEquals("https://taifood-blog.asia/embed/mlb/2026-10-28/mil-tb", sources.last().second)
        // NBA replays come as vods.
        val nba = StreamCornerParser.detail(reply("detail-nba.json"))
        assertEquals(9, StreamCornerParser.sources(nba).size)
        assertTrue(StreamCornerParser.sources(nba).all { it.second.startsWith("https://amazon.com.getsugatensho.sbs/nba?p=") })
    }

    @Test fun qualityFromLabels() {
        assertEquals(1080, StreamCornerParser.quality("FOX FHD HDR"))
        assertEquals(2160, StreamCornerParser.quality("FOX 4K UHD"))
        assertEquals(720, StreamCornerParser.quality("ESPN HD"))
        assertNull(StreamCornerParser.quality("FOX"))
        assertNull(StreamCornerParser.quality("HD11"))
    }

    @Test fun pageUrlsRoundTrip() {
        val main = "https://streamcorner.st"
        for ((provider, id) in listOf("nba" to "0022600129", "extra003" to "8fc3f7a58a82ecd493fdac1d6fd5898e", "beta" to "GRNT2b48b9a1bd7a94161b21", "channels" to "cnn", "001" to "48d59")) {
            assertEquals(provider to id, StreamCornerParser.pageRef(StreamCornerParser.pageUrl(main, provider, id)))
        }
        assertEquals("$main/nba/live/0022600129", StreamCornerParser.pageUrl(main, "nba", "0022600129"))
        assertEquals("$main/stream/extra/extra003/abc", StreamCornerParser.pageUrl(main, "extra003", "abc"))
        assertEquals("nba" to "0022600129", StreamCornerParser.pageRef("$main/nba/vod/0022600129?player=shaka"))
        assertNull(StreamCornerParser.pageRef("$main/channels"))
    }

    @Test fun manifestUrls() {
        assertTrue(StreamCornerParser.isManifest("https://turnerlive.warnermediacdn.com/hls/live/586495/cnngo/cnn_slate/VIDEO_0_3564000.m3u8"))
        assertTrue(StreamCornerParser.isManifest("https://netanyahu.indianservers.st/secure/x/1/2/tampabayrays/index.m3u8?token=1"))
        assertFalse(StreamCornerParser.isManifest("https://cdn.jsdelivr.net/npm/@oplayer/hls@latest/dist/index.min.js"))
        assertFalse(StreamCornerParser.isManifest("https://x/video_36957.ts"))
    }

    @Test fun dashManifests() {
        assertTrue(StreamCornerParser.isDash("https://otte.cache.aiv-cdn.net/iad-nitro/live/clients/dash/enc/rhf2dwosdt/out/v1/ee550d2a68d846c797e6ce4de2e8b76d/cenc.mpd"))
        assertFalse(StreamCornerParser.isDash("https://otte.cache.aiv-cdn.net/x/cenc_video_7_0_init.mp4?m=1781704769"))
    }

    // lpnba.akamaized.net/live-pz/p/hls-wvpr/… from the NBA embeds; the phone's player threw UnsupportedDrmException.
    @Test fun drmPlaylists() {
        assertTrue(StreamCornerParser.isDrm(text("nba-wvpr-master.m3u8")))
        assertFalse(StreamCornerParser.isDrm(text("nba-wvpr-master-nokeys.m3u8")))
        assertTrue(StreamCornerParser.isDrm("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:x\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:4,\na.m4s"))
        assertFalse(StreamCornerParser.isDrm(text("cnn-variant.m3u8")))
        assertFalse(StreamCornerParser.isDrm("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts"))
    }

    @Test fun masterFirstVariant() {
        val master = "https://lpnba.akamaized.net/live-pz/p/hls-wvpr/g0022600143lal1000461lac/index.m3u8?vpmute=0"
        val variant = StreamCornerParser.firstVariant(text("nba-wvpr-master-nokeys.m3u8"), master)!!
        assertTrue(variant, variant.startsWith("https://lpnba.akamaized.net/live-pz/p/hls-wvpr/g0022600143lal1000461lac/v4-121.m3u8?vpmute=0&"))
        assertEquals("https://h/a/b/low.m3u8", StreamCornerParser.firstVariant("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n\n/a/b/low.m3u8\n", "https://h/x/index.m3u8"))
        assertNull(StreamCornerParser.firstVariant(text("cnn-variant.m3u8"), "https://h/x.m3u8"))
    }

    @Test fun harnessPages() {
        assertTrue(StreamCornerParser.bridgeHtml("/assets/BCJZAtsV.js").contains("""new Worker("/assets/BCJZAtsV.js", {type: 'module'"""))
        assertTrue(StreamCornerParser.playerHtml("https://e/x?p=a&b=\"c").contains("""src="https://e/x?p=a&amp;b=&quot;c""""))
    }
}
