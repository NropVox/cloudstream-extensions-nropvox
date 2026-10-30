package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * kisskh's JSON API calls for a title and its streams: the site's HLS (no headers needed) and its subtitles, whose
 * endpoints need a `kkey` computed from the episode id. Shared by the KissKH and OmniStream plugins.
 */
object KissKHSource {
    const val SITE = "https://kisskh.ovh"
    const val API = "$SITE/api/DramaList"

    /** The search endpoint returns every match at once. */
    suspend fun search(query: String): List<KissCard> =
        app.get("$API/Search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&type=0").parsedSafe<Array<KissCard>>()?.toList().orEmpty()

    suspend fun drama(id: Int): KissDrama? = app.get("$API/Drama/$id?isq=false").parsedSafe<KissDrama>()

    suspend fun loadEpisode(
        episodeId: Int,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        runAllAsync(
            { if (loadVideo(episodeId, name, subtitleCallback, callback)) found = true },
            { loadSubtitles(episodeId, subtitleCallback) },
        )
        return found
    }

    private suspend fun loadVideo(
        episodeId: Int,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val kkey = KissKHParser.kkey(episodeId, KissKHParser.VIDEO_GUID)
        val video = app.get("$API/Episode/$episodeId.png?err=false&ts=null&time=null&kkey=$kkey")
            .parsedSafe<KissVideo>()
        val url = video?.video?.trim()?.let { if (it.startsWith("//")) "https:$it" else it }
        if (url.isNullOrEmpty()) {
            Log.w(name, "episode $episodeId: no video (thirdParty=${video?.thirdParty})")
            return false
        }
        if (video.type == 2) return loadExtractor(url, "$SITE/", subtitleCallback, callback)
        // Fresh episodes have `.PNG` segments (MPEG-TS inside), so the playlist goes to the player as-is.
        val isHls = video.type == 1 || ".m3u8" in url
        callback(
            newExtractorLink(name, name, url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                quality = Qualities.Unknown.value
            }
        )
        return true
    }

    /** `.srt` tracks are plain; `.txt`/`.txt1` ones have encrypted cues and are served decrypted over loopback. */
    private suspend fun loadSubtitles(episodeId: Int, subtitleCallback: (SubtitleFile) -> Unit) {
        val kkey = KissKHParser.kkey(episodeId, KissKHParser.SUB_GUID)
        val subtitles = app.get("$SITE/api/Sub/$episodeId?kkey=$kkey").parsedSafe<Array<KissSubtitle>>().orEmpty()
        for (subtitle in subtitles) {
            val src = subtitle.src?.trim()?.takeIf { it.startsWith("https://") } ?: continue
            val url = if (KissKHParser.isEncryptedSubtitle(src)) KissKHLocalServer.subtitleUrl(src) else src
            subtitleCallback(newSubtitleFile(subtitle.label ?: subtitle.land ?: "Unknown", url))
        }
    }
}
