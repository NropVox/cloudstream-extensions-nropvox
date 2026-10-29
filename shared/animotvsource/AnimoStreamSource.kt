package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The AniList-keyed stream API behind animotvslash's `HD0` player: `animotvslash.ru/api/stream/sources/<server>`
 * hands back ready-to-play HLS/MP4 from several providers. Shared by the AnimoTvSlash and OmniStream plugins.
 */
object AnimoStreamSource {
    /** `stream.animotvslash.ru` and the API both answer 403 unless the Referer is the player's own site. */
    const val REFERER = "https://animotvslash.ru/"
    private const val API = "https://animotvslash.ru/api/stream"
    private const val SERVER_TIMEOUT_MS = 25_000L

    /** Providers that returned plain HLS/MP4 in recon; the others (kaa, xanime, mmani) failed. */
    val SERVERS = listOf("koto", "animo-ss", "miku", "aniwaves", "beep")

    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)
    private val digits = Regex("""\d{3,4}""")

    /** Every server for one episode in parallel; [lang] is `sub` or `dub`. */
    suspend fun load(
        anilistId: Int,
        episode: Int,
        lang: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val subtitles = Collections.synchronizedSet(HashSet<String>())
        val found = AtomicBoolean(false)
        val langLabel = if (lang == "dub") "Dub" else "Sub"
        SERVERS.amap { server ->
            withTimeoutOrNull(SERVER_TIMEOUT_MS) {
                try {
                    if (emitServer(name, langLabel, anilistId.toString(), episode.toString(), lang, server,
                            emitted, subtitles, subtitleCallback, callback)) found.set(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "Stream $server $anilistId/$episode: $e")
                }
            }
        }
        return found.get()
    }

    /** One provider; false when it has nothing for this episode. Links are named `<name> <langLabel> <server>`. */
    suspend fun emitServer(
        name: String,
        langLabel: String,
        anilistId: String,
        episode: String,
        lang: String,
        server: String,
        emitted: MutableSet<String>,
        subtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "$API/sources/$server?anilistId=$anilistId&episode=$episode&lang=$lang"
        val res = app.get(url, headers = browserHeaders + mapOf("Referer" to REFERER)).text
        val source = tryParseJson<StreamSources>(res) ?: return false
        val label = "$name $langLabel ${source.server ?: server}"
        var emittedAny = false
        if (source.type == "mp4") {
            val files = source.qualities.orEmpty().mapNotNull { q -> q.url?.let { it to height(q.quality) } }
                .ifEmpty { listOfNotNull(source.url?.let { it to null }) }
            for ((file, height) in files) {
                if (emit(name, "$label${height?.let { " ${it}p" }.orEmpty()}", file, ExtractorLinkType.VIDEO, height, emitted, callback)) emittedAny = true
            }
        } else {
            val file = source.url ?: return false
            emittedAny = emit(name, label, file, ExtractorLinkType.M3U8, null, emitted, callback)
        }
        source.subtitles.orEmpty().forEach { s ->
            val subUrl = s.url ?: return@forEach
            if (subtitles.add(subUrl)) {
                subtitleCallback(newSubtitleFile(s.label ?: "Subtitles", subUrl) { headers = mapOf("Referer" to REFERER) })
            }
        }
        return emittedAny
    }

    private suspend fun emit(
        name: String,
        label: String,
        url: String,
        type: ExtractorLinkType,
        height: Int?,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!emitted.add(url)) return false
        // The master carries separate audio renditions, so it is handed to the player whole rather than split by resolution.
        callback(newExtractorLink(name, label, url, type) {
            quality = height ?: Qualities.Unknown.value
            referer = REFERER
        })
        return true
    }

    /** `720p` to 720. */
    private fun height(label: String?): Int? = label?.let { digits.find(it)?.value?.toIntOrNull() }
}

/** Response of `animotvslash.ru/api/stream/sources/<server>`: one resolved stream (media URLs go through `stream.animotvslash.ru`). */
data class StreamSources(
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("qualities") val qualities: List<StreamQuality>? = null,
    @JsonProperty("subtitles") val subtitles: List<StreamSubtitle>? = null,
)

data class StreamQuality(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class StreamSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
)
