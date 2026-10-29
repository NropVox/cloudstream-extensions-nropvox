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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URI

/** Movie or episode `data` of Cno.to, keyed by TMDB id. */
data class CnoLink(
    val type: String,
    val id: Int,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** Path segment used by the subtitle and skip endpoints. */
    fun apiKey() = if (type == "movie") "movie/$id" else "tv/$id/$season/$episode"
}

/**
 * Cno.to's sources, keyed by TMDB id: first-party HLS for titles in its own library, third-party embeds for the
 * rest. Shared by the CnoTo and OmniStream plugins; [load]'s name labels the links and log lines.
 */
object CnoToSource {
    const val SITE = "https://cno.to"
    private const val API = "$SITE/api"
    private const val MAX_SUBTITLES_PER_LANGUAGE = 3
    private const val SUBTITLE_RETRY_DELAY = 3_000L

    // media.settlar.io (anime servers 18/19) rejects non-browser user agents with 403.
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    suspend fun load(
        link: CnoLink,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val path = if (link.type == "movie") "movie/${link.id}" else "tv/${link.id}/episode/${link.season}/${link.episode}"
        val sources = fetch<CnoSources>(path)?.sources

        var found = false
        for (source in sources.orEmpty()) {
            val url = source.url ?: continue
            when (source.kind) {
                "hls" -> {
                    // Sealed playlists need a Turnstile pass and key unwrapping; Cno.to has this switched off.
                    if (source.sealed == true) {
                        Log.w(name, "Skipping sealed source ${source.id}")
                        continue
                    }
                    val label = listOfNotNull(name, source.label, source.note).joinToString(" ")
                    // The master playlist lists every resolution; the player offers them as qualities.
                    callback(newExtractorLink(name, label, absolute(url), ExtractorLinkType.M3U8) {
                        referer = "$SITE/"
                        quality = Qualities.Unknown.value
                        headers = browserHeaders
                    })
                    found = true
                }
                // cinesrc.st / vidfast.vc pages; only playable if an installed extractor handles them.
                "embed" -> if (loadExtractor(url, "$SITE/", subtitleCallback, callback)) found = true
            }
        }

        if (found) loadSubtitles(link.apiKey(), subtitleCallback)
        return found
    }

    private suspend fun loadSubtitles(key: String, subtitleCallback: (SubtitleFile) -> Unit) {
        var list = fetch<CnoSubtitleList>("subs/list/$key")
        // Cno.to gathers subtitles on demand for titles nobody has opened yet.
        if (list?.files.isNullOrEmpty() && list?.pending != null) {
            delay(SUBTITLE_RETRY_DELAY)
            list = fetch<CnoSubtitleList>("subs/list/$key")
        }

        // Popular titles list hundreds of files; keep the best-ranked few per language.
        list?.files.orEmpty()
            .filter { it.id != null }
            .sortedByDescending { it.rank ?: 0.0 }
            .groupBy { it.label ?: it.lang ?: "Unknown" }
            .forEach { (label, files) ->
                files.take(MAX_SUBTITLES_PER_LANGUAGE).forEachIndexed { index, file ->
                    subtitleCallback(
                        newSubtitleFile(
                            if (index == 0) label else "$label ${index + 1}",
                            "$API/subs/file/$key/${file.id}.vtt"
                        ) {
                            headers = browserHeaders
                        }
                    )
                }
            }
    }

    private suspend inline fun <reified T> fetch(path: String): T? =
        tryParseJson<T>(app.get("$API/$path", headers = browserHeaders).text)

    /** Sources can be site-relative; this is what `fixUrl` did against the plugin's mainUrl. */
    private fun absolute(url: String): String = URI("$SITE/").resolve(url).toString()

    /** `api/movie/{id}` and `api/tv/{id}/episode/{s}/{e}` both carry the playable sources. */
    data class CnoSources(val sources: List<CnoStream>? = null)

    data class CnoStream(
        val id: String? = null,
        val kind: String? = null,
        val url: String? = null,
        val label: String? = null,
        val note: String? = null,
        val sealed: Boolean? = null,
    )

    data class CnoSubtitleList(val files: List<CnoSubtitleInfo>? = null, val pending: Any? = null)

    data class CnoSubtitleInfo(
        val id: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val rank: Double? = null,
    )
}
