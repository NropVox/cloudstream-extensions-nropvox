package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

/**
 * 7movies.ac playback, keyed by TMDB id: a playback grant from the site, then the Vidrift player's boot and
 * per-provider source APIs. Shared by the SevenMovies and OmniStream plugins; [load]'s name labels the links.
 */
internal object SevenMoviesSource {
    const val SITE = "https://7movies.ac"
    private const val PLAYER = "https://embed.vidrift.net"
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    suspend fun load(
        link: SevenLink,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (link.type !in setOf("movie", "tv") || link.id <= 0 ||
            (link.type == "tv" && (link.season == null || link.episode == null))) return false
        val path = if (link.type == "tv") "tv/${link.id}/${link.season}/${link.episode}" else "movie/${link.id}"
        val watchUrl = "$SITE/${link.type}/${link.id}/watch"
        // Never persist grants or signed URLs in episode data: obtain them when playback starts.
        val grant = safely(name, "playback grant") {
            app.post("$SITE/api/playback-token", headers = browserHeaders + mapOf(
                "Origin" to SITE, "Referer" to watchUrl, "Content-Type" to "application/json"
            ), json = SevenGrantRequest(link.id, link.type, link.season, link.episode), timeout = 30L)
                .parsedSafe<SevenGrant>()
        }?.token?.takeIf { it.isNotBlank() } ?: return false
        val bootResponse = safely(name, "player boot") {
            app.get("$PLAYER/api/boot/$path?token=${encode(grant)}&shell=1", headers = browserHeaders + mapOf(
                "x-embed-parent" to SITE, "Referer" to watchUrl
            ), timeout = 30L)
        } ?: return false
        val boot = bootResponse.parsedSafe<SevenBoot>() ?: return false
        val meta = boot.meta ?: return false
        if (!meta.unreleased.isNullOrBlank()) return false
        val token = meta.token?.takeIf { it.isNotBlank() } ?: grant
        val cookie = bootResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val playerHeaders = browserHeaders + mapOf("Referer" to "$PLAYER/") +
            if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val subtitles = Collections.synchronizedSet(HashSet<String>())

        fun mediaHeaders(url: String): Map<String, String> =
            if (URI(url).host == URI(PLAYER).host) playerHeaders else browserHeaders + mapOf("Referer" to "$PLAYER/")

        suspend fun emitSubtitle(track: SevenSubtitle) {
            val url = absolute(track.url) ?: return
            if (subtitles.add(url)) subtitleCallback(newSubtitleFile(track.label ?: track.lang ?: "Unknown", url) {
                headers = mediaHeaders(url)
            })
        }

        suspend fun emit(stream: SevenStream, provider: String, quality: Int? = null) {
            val url = absolute(stream.url?.takeIf { it.isNotBlank() } ?: stream.proxyUrl) ?: return
            if (emitted.add(url)) {
                val label = stream.name ?: provider.replaceFirstChar { it.uppercase() }
                callback(newExtractorLink(name, "$name - $label", url,
                    if (stream.type == "mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                    referer = "$PLAYER/"
                    this.quality = stream.rungs.orEmpty().mapNotNull { it.height }.maxOrNull()
                        ?: quality ?: Qualities.Unknown.value
                    headers = mediaHeaders(url)
                })
                stream.subtitleListUrl?.let { list ->
                    absolute(list)?.let { subtitleUrl ->
                        get<SevenTracks>(name, subtitleUrl, playerHeaders)?.tracks.orEmpty().forEach { emitSubtitle(it) }
                    }
                }
            }
        }

        boot.shell?.subtitles.orEmpty().forEach { emitSubtitle(it) }
        listOf("evion" to meta.evionUrl, "vexon" to meta.vexonUrl, "selfhost" to meta.selfhostUrl)
            .forEach { (provider, url) ->
                if (!url.isNullOrBlank()) emit(SevenStream(url = url,
                    type = if (provider == "selfhost") meta.selfhostKind else "hls"), provider)
            }
        meta.orionStreams.orEmpty().forEach { emit(it, "Orion") }
        val providers = linkedSetOf("orion", "vela", "atlas", "titan", "lyra")
        // Anime flags are either false or an array of available sub/dub providers.
        meta.anime?.takeIf { it.isArray }?.forEach { providers.add(it.asText()) }
        if (meta.anime?.isBoolean == true && meta.anime.asBoolean()) providers.addAll(listOf("anime", "animedub"))
        providers.filter { it.matches(Regex("[a-z0-9-]{1,24}")) }.amap { provider ->
            safely(name, provider) {
                val source = get<SevenSources>(name, "$PLAYER/api/source/$path?token=${encode(token)}" +
                    "&provider=$provider&parent=${encode(meta.parent ?: URI(SITE).host)}&nofallback=1", playerHeaders)
                    ?: return@safely
                source.subtitles.orEmpty().forEach { emitSubtitle(it) }
                source.streams.orEmpty().forEach { emit(it, source.provider ?: provider, source.maxRes) }
            }
        }
        return emitted.isNotEmpty()
    }

    private suspend inline fun <reified T : Any> get(name: String, url: String, headers: Map<String, String>): T? =
        safely(name, "request") { app.get(url, headers = headers, timeout = 30L).parsedSafe<T>() }

    private suspend fun <T> safely(name: String, operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Do not log exception messages: HTTP errors may contain playback tokens or signed URLs.
        Log.d(name, "$operation failed (${e.javaClass.simpleName})")
        null
    }

    private fun absolute(url: String?): String? = url?.takeIf { it.isNotBlank() }?.let {
        runCatching { URI("$PLAYER/").resolve(it).takeIf { u -> u.scheme in setOf("http", "https") }?.toString() }.getOrNull()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
