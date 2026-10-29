package com.nropvox

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class StreamCornerDashTest {
    private val kid = "0b6acaedc38cc9b68644ad71e7abfd8f"
    private val base = "https://lpnba.akamaized.net/vod-pz/p/hls-wvpr/NBA_g0022600129det1000467nykV/index.m3u8"
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val selection = StreamCornerDash.Selection(base.replace("index", "v4-121"), base.replace("index", "v4-223"),
        960, 540, "avc1.4D401F", "mp4a.40.5", "29.970", 1828204)

    @Test fun selectsHighestVideoAndItsAudioGroup() {
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="low",URI="low-audio.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="high",URI="high-audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=400x224,CODECS="avc1.4D400C,mp4a.40.5",AUDIO="low"
low-video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.5",FRAME-RATE=59.940,AUDIO="high"
high-video.m3u8?one=1&two=2
"""
        val selected = StreamCornerDash.select(master, base)!!
        assertEquals(1080, selected.height)
        assertTrue(selected.audioUrl.endsWith("/high-audio.m3u8"))
        assertTrue(selected.videoUrl.endsWith("?one=1&two=2"))
    }

    @Test fun convertsCapturedDiscontinuitiesAndEscapesSegmentQueries() {
        val video = StreamCornerDash.media(text("nba-video.m3u8"), base, kid)!!
        val audio = StreamCornerDash.media(text("nba-audio.m3u8"), base, kid)!!
        assertEquals(2, video.periods.size)
        assertEquals(2, video.periods.first().segments.size)
        val mpd = StreamCornerDash.mpd(selection, video, audio, kid, 90000, 90000)!!
        assertTrue(mpd.contains("start=\"PT0.8842S\""))
        assertTrue(mpd.contains("test=1&amp;two=2"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(mpd.byteInputStream())
        assertEquals(2, document.getElementsByTagNameNS("urn:mpeg:dash:schema:mpd:2011", "Period").length)
        assertEquals(8, document.getElementsByTagNameNS("urn:mpeg:dash:schema:mpd:2011", "SegmentURL").length)
    }

    @Test fun refusesLiveRangesUnknownKeysAndWrongKid() {
        val playlist = text("nba-video.m3u8")
        assertNull(StreamCornerDash.media(playlist.replace("#EXT-X-ENDLIST", ""), base, kid))
        assertNull(StreamCornerDash.media(playlist.replace("#EXT-X-MAP:", "#EXT-X-BYTERANGE:100\n#EXT-X-MAP:"), base, kid))
        assertNull(StreamCornerDash.media(playlist.replace("SAMPLE-AES-CTR", "SAMPLE-AES"), base, kid))
        assertNull(StreamCornerDash.media(playlist, base, "00000000000000000000000000000000"))
        assertNull(StreamCornerDash.media(playlist.replace("-t-", "-unknown-"), base, kid))
    }

    @Test fun retainsInitializationChangesAtDiscontinuities() {
        val playlist = text("nba-video.m3u8")
        val map = playlist.lastIndexOf("#EXT-X-MAP:")
        val changed = playlist.substring(0, map) + playlist.substring(map).replace("v4_121-init.m4s", "period2-init.m4s")
        val track = StreamCornerDash.media(changed, base, kid)!!
        assertEquals(2, track.initializations.size)
        assertTrue(track.periods[1].initialization.endsWith("period2-init.m4s"))
        val mpd = StreamCornerDash.mpd(selection, track, StreamCornerDash.media(text("nba-audio.m3u8"), base, kid)!!,
            kid, 90000, 90000)!!
        assertTrue(mpd.contains("period2-init.m4s"))
    }

    @Test fun commonPsshCarriesOneCanonicalKid() {
        val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(StreamCornerDash.commonPssh(kid)))
        assertEquals(52, bytes.int)
        assertEquals(0x70737368, bytes.int)
        assertEquals(0x01000000, bytes.int)
        assertEquals(UUID.fromString("1077efec-c0b2-4d02-ace3-3c1e52e2fb4b"), UUID(bytes.long, bytes.long))
        assertEquals(1, bytes.int)
        val id = ByteArray(16).also { bytes.get(it) }
        assertEquals(kid, id.joinToString("") { "%02x".format(it.toInt() and 255) })
        assertEquals(0, bytes.int)
    }
}
