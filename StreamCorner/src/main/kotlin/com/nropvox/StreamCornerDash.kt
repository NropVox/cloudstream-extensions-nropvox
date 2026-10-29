package com.nropvox

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID

/** NBA's VOD fMP4 HLS can describe the same fragments as DASH for Cloudstream's DRM player. */
internal object StreamCornerDash {
    data class Selection(
        val videoUrl: String, val audioUrl: String, val width: Int, val height: Int,
        val videoCodec: String, val audioCodec: String, val frameRate: String?, val bandwidth: Long,
    )
    data class Segment(val url: String, val timestamp: Long, val duration: BigDecimal)
    data class Period(val initialization: String, val segments: List<Segment>) {
        val duration: BigDecimal get() = segments.fold(BigDecimal.ZERO) { n, s -> n + s.duration }
    }
    data class Track(val periods: List<Period>) {
        val initialization: String get() = periods.first().initialization
        val initializations: List<String> get() = periods.map { it.initialization }.distinct()
    }

    private val attribute = Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))""")
    private val time = Regex("""-t-(\d+)\.m4s(?:$|\?)""")
    private val hex = Regex("""^[0-9a-f]{32}$""")
    private const val WIDEVINE = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY = "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"
    private fun attributes(line: String) = attribute.findAll(line.substringAfter(':')).associate {
        it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3])
    }
    private fun absolute(base: String, path: String): String = URI(base).resolve(path).toString().also {
        require(URI(it).scheme in listOf("http", "https"))
    }

    fun select(master: String, masterUrl: String): Selection? = runCatching {
        val lines = master.lineSequence().map(String::trim).toList()
        val choices = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val a = attributes(line)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: return@mapIndexedNotNull null
            val resolution = a["RESOLUTION"]?.split('x') ?: return@mapIndexedNotNull null
            val codecs = a["CODECS"]?.split(',') ?: return@mapIndexedNotNull null
            val video = codecs.firstOrNull { it.startsWith("avc1.") || it.startsWith("hvc1.") || it.startsWith("hev1.") }
                ?: return@mapIndexedNotNull null
            val audio = codecs.firstOrNull { it.startsWith("mp4a.") } ?: return@mapIndexedNotNull null
            val audioRow = lines.firstOrNull {
                it.startsWith("#EXT-X-MEDIA:") && attributes(it).let { m -> m["TYPE"] == "AUDIO" && m["GROUP-ID"] == a["AUDIO"] }
            } ?: return@mapIndexedNotNull null
            Selection(absolute(masterUrl, uri), absolute(masterUrl, attributes(audioRow)["URI"] ?: return@mapIndexedNotNull null),
                resolution[0].toInt(), resolution[1].toInt(), video, audio, a["FRAME-RATE"], a["BANDWIDTH"]!!.toLong())
        }
        choices.maxByOrNull { it.bandwidth } ?: error("No separate fMP4 video/audio pair")
    }.getOrNull()

    /** Only the observed single-key NBA VOD layout: no ranges, gaps, key rotation, or init changes without discontinuities. */
    fun media(playlist: String, playlistUrl: String, kid: String): Track? = runCatching {
        val expectedKid = kid.replace("-", "").lowercase().also { require(hex.matches(it)) }
        val lines = playlist.lineSequence().map(String::trim).toList()
        require(lines.firstOrNull() == "#EXTM3U" && "#EXT-X-ENDLIST" in lines)
        val periods = mutableListOf<Period>()
        var segments = mutableListOf<Segment>()
        var initialization: String? = null
        var duration: BigDecimal? = null
        var encrypted = false
        for (line in lines) when {
            line == "#EXT-X-DISCONTINUITY" -> {
                require(segments.isNotEmpty() && duration == null)
                periods += Period(initialization ?: error("Missing initialization"), segments)
                segments = mutableListOf()
                encrypted = false
            }
            line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-GAP") -> error("Unsupported HLS layout")
            line.startsWith("#EXT-X-KEY:") -> {
                val a = attributes(line)
                require(a["METHOD"] == "SAMPLE-AES-CTR")
                val id = a["KEYID"]?.removePrefix("0x")?.lowercase() ?: error("Missing key ID")
                when (a["KEYFORMAT"]) {
                    WIDEVINE -> { require(id == expectedKid); encrypted = true }
                    PLAYREADY -> require(playReadyKid(id) == expectedKid)
                    else -> error("Unsupported key system")
                }
            }
            line.startsWith("#EXT-X-MAP:") -> {
                val a = attributes(line)
                require(a["BYTERANGE"] == null)
                val uri = absolute(playlistUrl, a["URI"] ?: error("Missing initialization"))
                require(segments.isEmpty() || initialization == uri)
                initialization = uri
            }
            line.startsWith("#EXTINF:") -> {
                require(duration == null)
                duration = line.substringAfter(':').substringBefore(',').toBigDecimal().also { require(it > BigDecimal.ZERO) }
            }
            line.isNotEmpty() && !line.startsWith('#') -> {
                require(encrypted && initialization != null)
                val uri = absolute(playlistUrl, line)
                val timestamp = time.find(uri)?.groupValues?.get(1)?.toLong() ?: error("Not NBA timestamped fMP4")
                require(segments.lastOrNull()?.let { timestamp > it.timestamp } != false)
                segments += Segment(uri, timestamp, duration ?: error("Missing segment duration"))
                duration = null
            }
        }
        require(segments.isNotEmpty() && duration == null)
        periods += Period(initialization ?: error("Missing initialization"), segments)
        Track(periods)
    }.getOrNull()

    private fun playReadyKid(id: String): String {
        require(hex.matches(id))
        fun reverse(from: Int, to: Int) = id.substring(from, to).chunked(2).reversed().joinToString("")
        return reverse(0, 8) + reverse(8, 12) + reverse(12, 16) + id.substring(16)
    }

    /** Read the first track's mdhd timescale from its initialization MP4; no Android dependencies. */
    fun timescale(initialization: ByteArray): Int? = runCatching {
        fun find(start: Int, end: Int): Int? {
            var offset = start
            while (offset + 8 <= end) {
                val size = ByteBuffer.wrap(initialization, offset, 4).int
                require(size >= 8 && size <= end - offset)
                val type = String(initialization, offset + 4, 4, Charsets.US_ASCII)
                if (type == "mdhd") {
                    val position = offset + if (initialization[offset + 8].toInt() == 1) 28 else 20
                    require(position + 4 <= offset + size)
                    return ByteBuffer.wrap(initialization, position, 4).int.also { require(it > 0) }
                }
                if (type in listOf("moov", "trak", "mdia")) find(offset + 8, offset + size)?.let { return it }
                offset += size
            }
            return null
        }
        find(0, initialization.size)
    }.getOrNull()

    /** Caller verifies all unique initializations share each track's timescale; -t- timestamps use it. */
    fun mpd(selection: Selection, video: Track, audio: Track, kid: String, videoTimescale: Int, audioTimescale: Int): String? = runCatching {
        require(videoTimescale > 0 && audioTimescale > 0 && video.periods.size == audio.periods.size)
        video.periods.zip(audio.periods).forEach { (v, a) ->
            require(v.segments.size == a.segments.size && (v.duration - a.duration).abs() <= BigDecimal("0.01"))
        }
        val duration = video.periods.fold(BigDecimal.ZERO) { n, p -> n + p.duration }
        val protection = protection(kid)
        buildString {
            append("""<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static" minBufferTime="PT2S" mediaPresentationDuration="PT${duration.toPlainString()}S" profiles="urn:mpeg:dash:profile:isoff-main:2011">""")
            var start = BigDecimal.ZERO
            video.periods.zip(audio.periods).forEachIndexed { i, (v, a) ->
                append("""<Period id="$i" start="PT${start.toPlainString()}S" duration="PT${v.duration.toPlainString()}S">""")
                adaptation("video", v, v.initialization, videoTimescale, selection.videoCodec, selection.bandwidth,
                    "width=\"${selection.width}\" height=\"${selection.height}\"" + (selection.frameRate?.toBigDecimalOrNull()?.stripTrailingZeros()?.let { fps ->
                        val denominator = java.math.BigInteger.TEN.pow(fps.scale().coerceAtLeast(0))
                        val numerator = fps.multiply(denominator.toBigDecimal()).toBigIntegerExact()
                        " frameRate=\"$numerator/$denominator\""
                    } ?: ""), protection)
                adaptation("audio", a, a.initialization, audioTimescale, selection.audioCodec, 128000, "", protection)
                append("</Period>")
                start += v.duration
            }
            append("</MPD>")
        }
    }.getOrNull()

    private fun StringBuilder.adaptation(kind: String, period: Period, initialization: String, timescale: Int,
        codec: String, bandwidth: Long, extra: String, protection: String) {
        append("""<AdaptationSet contentType="$kind" mimeType="$kind/mp4">$protection<Representation id="$kind" codecs="${xml(codec)}" bandwidth="$bandwidth" $extra><SegmentList timescale="$timescale" presentationTimeOffset="${period.segments.first().timestamp}"><Initialization sourceURL="${xml(initialization)}"/><SegmentTimeline>""")
        period.segments.forEachIndexed { i, segment ->
            val d = period.segments.getOrNull(i + 1)?.let { it.timestamp - segment.timestamp }
                ?: segment.duration.multiply(timescale.toBigDecimal()).setScale(0, RoundingMode.HALF_UP).longValueExact()
            require(d > 0)
            append("""<S t="${segment.timestamp}" d="$d"/>""")
        }
        append("</SegmentTimeline>")
        period.segments.forEach { append("""<SegmentURL media="${xml(it.url)}"/>""") }
        append("</SegmentList></Representation></AdaptationSet>")
    }

    fun commonPssh(kid: String): String {
        val id = kid.replace("-", "").lowercase().also { require(hex.matches(it)) }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val system = UUID.fromString("1077efec-c0b2-4d02-ace3-3c1e52e2fb4b")
        val bytes = ByteBuffer.allocate(52).putInt(52).put("pssh".toByteArray(Charsets.US_ASCII)).putInt(0x01000000)
            .putLong(system.mostSignificantBits).putLong(system.leastSignificantBits).putInt(1).put(id).putInt(0).array()
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString {
            for (i in bytes.indices step 3) {
                val word = ((bytes[i].toInt() and 255) shl 16) or
                    ((bytes.getOrNull(i + 1)?.toInt()?.and(255) ?: 0) shl 8) or
                    (bytes.getOrNull(i + 2)?.toInt()?.and(255) ?: 0)
                append(alphabet[(word ushr 18) and 63]); append(alphabet[(word ushr 12) and 63])
                append(if (i + 1 < bytes.size) alphabet[(word ushr 6) and 63] else '=')
                append(if (i + 2 < bytes.size) alphabet[word and 63] else '=')
            }
        }
    }
    fun protection(kid: String): String {
        val id = kid.replace("-", "").lowercase().also { require(hex.matches(it)) }
        val uuid = "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
        return """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="$uuid"/><ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e"><cenc:pssh>${commonPssh(id)}</cenc:pssh></ContentProtection>"""
    }
    private fun xml(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")
}
