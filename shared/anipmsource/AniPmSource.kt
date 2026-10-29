package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * ani.pm playback for settlar- or AniList-keyed titles: playback-bootstrap → settlar session → embed.settlar.io
 * session → plain HLS on media.settlar.io. Shared by the AniPm and OmniStream plugins.
 */
object AniPmSource {
    const val SITE = "https://ani.pm"
    private const val API = "$SITE/api"
    private const val EMBED_HOST = "https://embed.settlar.io"

    // Every ani.pm and settlar host, covers and segments included, answers 403 to non-browser user agents.
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    suspend fun load(
        link: ApLinkData,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bootstrap = fetch<ApBootstrap>(AniPmParser.bootstrapPath(link))
        val selection = bootstrap?.settlarSelection ?: run {
            Log.w(name, "No playback for ${link.source}/${link.id} ep ${link.ep}")
            return false
        }
        // The session must be asked for the audio the bootstrap settled on, not the one requested.
        val channel = bootstrap.effectiveLanguage ?: link.lang
        val query = listOf(
            "selection" to selection,
            "provider" to "anipm",
            "ep" to link.ep.toString(),
            "channel" to channel,
            "telemetry" to "0",
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val embedUrl = fetch<ApSettlarSession>("anime/settlar/session?$query")?.embedUrl ?: return false
        val token = AniPmParser.embedToken(embedUrl) ?: return false
        val session = tryParseJson<ApEmbedSession>(
            app.get("$EMBED_HOST/api/embed/session?t=$token", headers = browserHeaders).text
        )
        val source = session?.source?.takeIf { session.kind == "hls" } ?: run {
            Log.w(name, "Embed session gave no HLS for ${link.source}/${link.id} ep ${link.ep}")
            return false
        }

        // The master lists every resolution; the player offers them as qualities.
        callback(
            newExtractorLink(name, "$name ${AniPmParser.linkLabel(link.lang, channel)}", source, ExtractorLinkType.M3U8) {
                quality = Qualities.Unknown.value
                headers = browserHeaders
            }
        )
        session.subtitles.orEmpty().forEach { track ->
            val url = track.url ?: return@forEach
            subtitleCallback(newSubtitleFile(track.label ?: track.srclang ?: "Unknown", url) {
                headers = browserHeaders
            })
        }
        return true
    }

    private suspend inline fun <reified T> fetch(path: String): T? =
        tryParseJson<T>(app.get("$API/$path", headers = browserHeaders).text)
}
