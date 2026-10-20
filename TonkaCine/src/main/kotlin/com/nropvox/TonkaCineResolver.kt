package com.nropvox

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal object TonkaCineResolver {
    suspend fun resolve(
        link: TonkaLink,
        site: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = Session(link, site, subtitleCallback, callback).resolve()

    private class Session(
        val link: TonkaLink,
        val site: String,
        val subtitleCallback: (SubtitleFile) -> Unit,
        val callback: (ExtractorLink) -> Unit,
    ) {
        val path = if (link.type == "tv") "tv/${link.id}/${link.season}/${link.episode}" else "movie/${link.id}"
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val subtitles = Collections.synchronizedSet(HashSet<String>())
        val found = AtomicBoolean(false)
        val browserHeaders = mapOf("User-Agent" to USER_AGENT)

        suspend fun resolve(): Boolean {
            val jobs: List<Pair<String, suspend () -> Unit>> = listOf(
                "Vidrock" to { vidrock() },
                "Vidnest Ophim" to { vidnest("ophim") },
                "Vidnest FlixHQ" to { vidnest("flixhq") },
                "Vidnest YFlix" to { vidnest("yflix") },
                "Vidlove" to { vidlove() },
                "Subtitles" to { subtitles() },
                "Other players" to { extractorFallbacks() },
            )
            jobs.chunked(3).forEach { batch ->
                batch.amap { (label, job) -> safely(label) { job() } }
            }
            return found.get()
        }

        suspend fun vidrock() {
            val origin = "https://vidrock.to"
            val headers = browserHeaders + mapOf("Referer" to "$origin/", "Origin" to origin)
            val sources = json("$origin/api/$path", headers)
            sources.fields().forEach { (server, source) ->
                safely("Vidrock $server") {
                    val encrypted = source.text("url") ?: return@safely
                    val url = TonkaCineDecoder.vidrock(encrypted)
                    // Some Vidrock servers return a JSON list of quality URLs rather than a playlist.
                    val type = source.text("type")
                    if (!url.contains(".m3u8", true) && type != "mp4") {
                        val response = app.get(url, headers = headers, timeout = 15L)
                        if (response.headers["content-type"].orEmpty().contains("json", true)) {
                            val qualities = mapper.readTree(response.text)
                            if (qualities.isArray) {
                                qualities.forEach { quality ->
                                    emit(quality.text("url"), "Vidrock $server", type, headers,
                                        quality.get("resolution")?.asInt()?.takeIf { it > 0 })
                                }
                                return@safely
                            }
                        }
                    }
                    emit(url, "Vidrock $server", type, headers)
                }
            }
        }

        suspend fun vidnest(provider: String) {
            val nonce = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) },
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val headers = browserHeaders + mapOf("Referer" to "https://vidnest.fun/", "Origin" to "https://vidnest.fun")
            val envelope = json("https://new.vidnest.fun/$provider/$path?_n=$nonce", headers)
            val result = if (envelope.get("v")?.asInt() == 2) {
                mapper.readTree(TonkaCineDecoder.vidnest(envelope.text("d") ?: return,
                    envelope.text("n") ?: return))
            } else envelope
            val mediaHeaders = browserHeaders + result.get("headers").asHeaders()
            val label = "Vidnest ${provider.replaceFirstChar { it.uppercase() }}"
            emit(result.text("url"), label, result.text("type"), mediaHeaders)
            result.get("streams")?.takeIf { it.isArray }?.forEach { stream ->
                emit(stream.text("url"), label + (stream.text("language")?.let { " - $it" } ?: ""),
                    stream.text("type"), mediaHeaders + stream.get("headers").asHeaders(),
                    quality(stream.text("quality")))
            }
            tracks(result.get("subtitles"), mediaHeaders)
        }

        suspend fun vidlove() {
            val query = if (link.type == "tv") "id=${link.id}&season=${link.season}&episode=${link.episode}" else "id=${link.id}"
            val origin = "https://player.vidlove.cc"
            val headers = browserHeaders + mapOf("Referer" to "$origin/", "Origin" to origin)
            // The busy-tier API can return source:null. It must never count as a working link.
            listOf("moviebox2", "moviebox").forEach { server ->
                safely("Vidlove $server") {
                    val result = json("https://api.vidlove.cc/${link.type}?$query&mode=json&sources=$server&vh=1", headers)
                    val source = result.get("source")
                    emit(source?.text("url"), "Vidlove $server", null, headers)
                    source?.get("qualities")?.takeIf { it.isArray }?.forEach { item ->
                        emit(item.text("url"), "Vidlove $server", null, headers, quality(item.text("quality")))
                    }
                    tracks(result.get("subtitles"), headers)
                }
            }
        }

        suspend fun subtitles() {
            val headers = browserHeaders + mapOf("Referer" to "https://player.vidlove.cc/")
            tracks(json("https://api.vidlove.cc/subtitles/$path", headers), headers)
        }

        suspend fun extractorFallbacks() {
            // These hosts need a matching extractor installed in Cloudstream. Never emit an HTML embed as video.
            val tv = link.type == "tv"
            val embeds = listOf(
                "https://vidup.to/$path?autoPlay=true&autoNext=true",
                "https://vidcore.io/$path?autoPlay=true&server=4",
                if (tv) "https://cinesrc.st/embed/tv/${link.id}?s=${link.season}&e=${link.episode}&autoplay=true"
                    else "https://cinesrc.st/embed/movie/${link.id}?autoplay=true",
                "https://vidsrc.sh/embed/$path",
            )
            embeds.forEach { url ->
                safely("External extractor") {
                    loadExtractor(url, "$site/", subtitleCallback) { stream ->
                        if (emitted.add(stream.url)) {
                            found.set(true)
                            callback(stream)
                        }
                    }
                }
            }
        }

        suspend fun emit(url: String?, label: String, type: String?, headers: Map<String, String>, height: Int? = null) {
            if (!isHttp(url) || !emitted.add(url!!)) return
            callback(newExtractorLink("TonkaCine", "TonkaCine - $label", url,
                if (type == "mp4" || URI(url).path.endsWith(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty()
                this.headers = headers
                quality = height ?: Qualities.Unknown.value
            })
            found.set(true)
        }

        suspend fun tracks(node: JsonNode?, headers: Map<String, String>) {
            node?.takeIf { it.isArray }?.forEach { track ->
                val url = track.text("file") ?: track.text("url") ?: return@forEach
                if (isHttp(url) && subtitles.add(url)) {
                    val label = track.text("label") ?: track.text("display") ?: track.text("language") ?: track.text("lang") ?: "Unknown"
                    subtitleCallback(newSubtitleFile(label, url) { this.headers = headers })
                }
            }
        }

        suspend fun json(url: String, headers: Map<String, String>): JsonNode =
            mapper.readTree(app.get(url, headers = headers, timeout = 20L).text)

        suspend fun safely(label: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TonkaCine", "$label: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun JsonNode.text(key: String): String? = get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode?.asHeaders(): Map<String, String> =
        this?.takeIf { it.isObject }?.fields()?.asSequence()?.filter { it.value.isTextual }
            ?.associate { it.key to it.value.asText() }.orEmpty()
    private fun quality(value: String?): Int? = value?.let { Regex("\\d{3,4}").find(it)?.value?.toIntOrNull() }
    private fun isHttp(url: String?): Boolean = try {
        val uri = URI(url ?: "")
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) { false }
}
