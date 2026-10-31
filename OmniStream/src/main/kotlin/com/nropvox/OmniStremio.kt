package com.nropvox

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Stremio addons keyed by IMDb id (`/stream/{movie|series}/{id}.json`, `/subtitles/...`): Vadapav serves direct
 * files, OpenSubtitles' v3 addon subtitles. Titles without an IMDb id are skipped.
 */
internal object OmniStremio {
    const val VADAPAV = "https://stremio.vadapav.mov"
    const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io"

    /** OpenSubtitles lists up to a hundred files per title; a few per language is plenty to pick from. */
    private const val SUBTITLES_PER_LANGUAGE = 3

    private val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")

    private suspend fun fetch(base: String, resource: String, link: OmniLink): String? {
        val imdbId = link.imdbId?.takeIf { it.startsWith("tt") } ?: return null
        val type = if (link.type == "tv") "series" else "movie"
        val id = OmniStreamParser.stremioId(imdbId, link.season, link.episode)
        return app.get("$base/$resource/$type/$id.json", headers = headers, timeout = 15L).takeIf { it.isSuccessful }?.text
    }

    suspend fun streams(base: String, link: OmniLink, name: String, callback: (ExtractorLink) -> Unit) {
        val json = fetch(base, "stream", link) ?: return
        OmniStreamParser.stremioStreams(json).forEach { stream ->
            val type = if (stream.url.substringBefore('?').endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(newExtractorLink(name, "$name ${stream.label}", stream.url, type) {
                quality = stream.height ?: Qualities.Unknown.value
                headers = stream.headers
            })
        }
    }

    suspend fun subtitles(base: String, link: OmniLink, subtitleCallback: (SubtitleFile) -> Unit) {
        val json = fetch(base, "subtitles", link) ?: return
        OmniStreamParser.stremioSubtitles(json, SUBTITLES_PER_LANGUAGE).forEach { subtitle ->
            subtitleCallback(newSubtitleFile(language(subtitle.lang), subtitle.url))
        }
    }

    private fun language(code: String): String = OmniStreamParser.openSubtitlesLanguage(code)
        ?: runCatching { SubtitleHelper.fromThreeLettersToLanguage(code) }.getOrNull()
        ?: code
}
