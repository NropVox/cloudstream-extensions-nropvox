package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

/*
 * Watch pages of the JAV sites. Their own providers and JavStream (which finds the same title on each site by its
 * code) both resolve links through these, so each site's player logic lives in one place.
 */

class JavtifulPage : ExtractorApi() {
    override val name = "Javtiful"
    override val mainUrl = "https://javtiful.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url).document
        val configRaw = res.selectFirst("script#watch-config")?.data()
            ?.takeIf { it.isNotBlank() } ?: return
        val config = runCatching {
            mapper.readValue(configRaw, WatchConfig::class.java)
        }.getOrNull() ?: return
        val playback = config.playback ?: return

        runAllAsync(
            {
                config.videoTitle?.substringBefore(" ")?.let { code ->
                    SubtitleCat().getUrl(code, null, subtitleCallback, callback)
                }
            },
            {
                requestSources(playback, url).forEach { source ->
                    val sourceUrl = fixUrl(source.src)
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = sourceUrl
                        ) {
                            this.quality = source.size ?: Qualities.Unknown.value
                            this.referer = "$mainUrl/"
                            this.type = if (
                                source.type?.contains("mpegurl", ignoreCase = true) == true ||
                                sourceUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                            ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                }
            }
        )
    }

    // The watch page only carries a signed ticket; the player POSTs it to get the media URLs.
    // A stale ticket is answered with 409 and a renewTicket to retry once.
    private suspend fun requestSources(playback: Playback, pageUrl: String): List<PlayerSource> {
        var ticket = playback.ticket
        repeat(2) {
            val response = app.post(
                fixUrl(playback.endpoint),
                data = mapOf("ticket" to ticket),
                referer = pageUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
            )
            val body = runCatching {
                mapper.readValue(response.text, PlaybackResponse::class.java)
            }.getOrNull() ?: return emptyList()
            if (response.code == 409 && !body.renewTicket.isNullOrBlank()) {
                ticket = body.renewTicket
                return@repeat
            }
            if (!body.success) return emptyList()
            return body.sources.orEmpty().filter { it.src.isNotBlank() }
        }
        return emptyList()
    }

    data class WatchConfig(
        val playback: Playback? = null,
        val videoTitle: String? = null
    )

    data class Playback(
        val endpoint: String,
        val ticket: String
    )

    data class PlaybackResponse(
        val success: Boolean = false,
        val sources: List<PlayerSource>? = null,
        val renewTicket: String? = null
    )

    data class PlayerSource(
        val src: String,
        val type: String? = null,
        val size: Int? = null
    )
}

class SexTBPage : ExtractorApi() {
    override val name = "SexTB"
    override val mainUrl = "https://sextb.net"
    override val requiresReferer = false

    private val cloudflareInterceptor = CloudflareKiller()

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, headers = commonHeaders, interceptor = cloudflareInterceptor)
        val code = res.document.select(".film-info-title a").attr("href")
            .substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast("/")

        coroutineScope {
            // Each player starts as soon as it is known instead of waiting for the whole token chain below.
            fun start(task: suspend () -> Unit) = launch {
                try {
                    task()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(name, "${e.message}")
                }
            }
            start { SubtitleCat().getUrl(code, null, subtitleCallback, callback) }

            val filmId = Regex("""var filmId\s*=\s*(\d+)""").find(res.text)?.groupValues?.get(1)
            var currentPt = Regex("""__pt\s*=\s*['"](.*?)['"]""").find(res.text)?.groupValues?.get(1)
            var currentPk = Regex("""__pk\s*=\s*['"](.*?)['"]""").find(res.text)?.groupValues?.get(1)
            val token = res.document.selectFirst("meta[name=_token]")?.attr("value")
            val socket = res.document.selectFirst("meta[name=_socket]")?.attr("value")
            val playerUrls = mutableSetOf<String>()
            val cookies = res.cookies.toMutableMap()
            res.document.selectFirst("#sextb-player iframe[src]")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                ?.takeIf { !it.contains("upgrade") }?.let { iframeUrl ->
                    playerUrls.add(iframeUrl)
                    start { loadExtractor(iframeUrl, url, subtitleCallback, callback) }
                }

            if (filmId != null && currentPt != null && currentPk != null && token != null && socket != null) {
                // The site rotates the session player token and XOR key after every request, so these stay sequential.
                for (ep in res.document.select(".episode-list button.btn-player:not(.vip)")) {
                    try {
                        val response = app.post(
                            "$mainUrl/ajax/player",
                            // The player endpoint rejects requests without the watch page's session cookie (E_SID).
                            cookies = cookies,
                            headers = commonHeaders + mapOf(
                                "Referer" to url,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Authorization" to "Basic ${base64Encode("$token:$socket".toByteArray(Charsets.UTF_8))}",
                                "Origin" to mainUrl
                            ),
                            data = mapOf(
                                "episode" to ep.attr("data-id"),
                                "filmId" to ep.attr("data-source").ifEmpty { filmId },
                                "pt" to currentPt.orEmpty()
                            )
                        ).also { cookies += it.cookies }.parsedSafe<PlayerResponse>() ?: continue
                        val decryptionKey = currentPk.orEmpty()
                        currentPt = response.nextPt ?: currentPt
                        currentPk = response.nextPk ?: currentPk
                        val encrypted = response.playerEnc ?: continue
                        val player = Jsoup.parse(decryptPlayer(encrypted, decryptionKey))
                        val iframeUrl = player.selectFirst("iframe[src]")?.attr("src")
                            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: continue
                        if (iframeUrl.contains("upgrade") || !playerUrls.add(iframeUrl)) continue
                        start { loadExtractor(iframeUrl, url, subtitleCallback, callback) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(name, "${e.message}")
                    }
                }
            }
        }
    }

    private fun decryptPlayer(encoded: String, key: String): String {
        return try {
            val decoded = base64Decode(encoded)
            val result = StringBuilder()
            for (i in decoded.indices) {
                result.append((decoded[i].code xor key[i % key.length].code).toChar())
            }
            result.toString()
        } catch (e: Exception) {
            Log.d(name, "${e.message}")
            ""
        }
    }

    data class PlayerResponse(
        @JsonProperty("player_enc") val playerEnc: String? = null,
        @JsonProperty("next_pt") val nextPt: String? = null,
        @JsonProperty("next_pk") val nextPk: String? = null
    )
}

class MissAVPage : ExtractorApi() {
    override val name = "MissAV"
    override val mainUrl = "https://missav.live"
    override val requiresReferer = false

    private val cloudflareInterceptor = CloudflareKiller()
    private val playlistIdPattern = Regex("/([a-f0-9\\-]{36})/")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, interceptor = cloudflareInterceptor)
        val playlistId = playlistIdPattern.find(getAndUnpack(res.text))?.groupValues?.get(1) ?: return

        runAllAsync(
            {
                res.document.selectFirst("h1.text-base")?.text()?.trim()?.substringBefore(" ")?.let { code ->
                    SubtitleCat().getUrl(code, null, subtitleCallback, callback)
                }
            },
            {
                generateM3u8(
                    source = name,
                    streamUrl = "https://surrit.com/$playlistId/playlist.m3u8",
                    referer = mainUrl,
                    headers = mapOf("Referer" to mainUrl)
                ).forEach(callback)
            }
        )
    }
}
