package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Movie or episode `data` of KatDrama; movies use season 1, episode 1 like the site. */
data class KatLink(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("s") val season: Int,
    @JsonProperty("e") val episode: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("year") val year: Int? = null,
)

/**
 * k-drama.in's resolvers, keyed by TMDB id: `2.php` (devcorp HLS), `vid2.php` (KissKH / Castle HLS, series only)
 * and `yoy5.php` (asianctv → vidbasic mirror embeds). Shared by the KatDrama and OmniStream plugins.
 */
object KatDramaSource {
    const val SITE = "https://k-drama.in"
    private const val TIMEOUT_S = 30L
    private const val MIRROR_BUDGET_MS = 15_000L

    suspend fun load(
        link: KatLink,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = AtomicBoolean(false)
        val emit: (ExtractorLink) -> Unit = { found.set(true); callback(it) }
        runAllAsync(
            { devcorp(link, name, subtitleCallback, emit) },
            { if (link.type == "tv") kisskh(link, name, subtitleCallback, emit) },
            // The player waits for loadLinks to return; dead mirror hosts would hold it for 30 s or more.
            { withTimeoutOrNull(MIRROR_BUDGET_MS) { vidbasic(link, name, subtitleCallback, emit) } },
        )
        return found.get()
    }

    /** Server 2: one HLS master (480p/720p, fMP4 segments named `.png`) and up to ~38 VTT tracks. */
    private suspend fun devcorp(link: KatLink, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val resolved = KatDramaParser.server2(app.get("$SITE/2.php/${link.id}/${link.season}/${link.episode}", timeout = TIMEOUT_S).document)
            ?: return
        if (!KatDramaParser.titleMatches(link.title, link.year, resolved.title, resolved.year)) {
            Log.i(name, "Server 2 matched \"${resolved.title}\" (${resolved.year}), not \"${link.title}\"; skipped")
            return
        }
        resolved.urls.forEach { url ->
            callback(newExtractorLink(name, "$name Devcorp", url, ExtractorLinkType.M3U8) { quality = Qualities.Unknown.value })
        }
        resolved.subtitles.forEach { (label, url) -> subtitleCallback(newSubtitleFile(label, url)) }
    }

    /** Server 6: KissKH (and Castle) playlists for series; titles come from the TMDB id, so no name check. */
    private suspend fun kisskh(link: KatLink, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get("$SITE/vid2.php/${link.id}/${link.season}/${link.episode}", timeout = TIMEOUT_S).text
        val (sources, subtitles) = KatDramaParser.server6(html)
        sources.forEach { source ->
            // Castle segments are TS named `.jpg`, so the playlist goes to the player as-is.
            callback(newExtractorLink(name, "$name ${source.provider}", source.url, ExtractorLinkType.M3U8) {
                quality = source.quality?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
            })
        }
        subtitles.forEach { (label, url) -> subtitleCallback(newSubtitleFile(label, url)) }
    }

    /** Server 7: asianctv episode → vidbasic page → dood / hglink / mixdrop / streamtape mirrors (often dead or slow). */
    private suspend fun vidbasic(link: KatLink, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val json = app.get("$SITE/yoy5.php?id=${link.id}&s=${link.season}&e=${link.episode}&ajax=1", timeout = TIMEOUT_S).text
        val resolved = KatDramaParser.server7(json) ?: return
        if (!KatDramaParser.titleMatches(link.title, link.year, resolved.title, resolved.year) ||
            (resolved.episode != null && resolved.episode != link.episode)) {
            Log.i(name, "Server 7 matched \"${resolved.title}\" (${resolved.year}) ep ${resolved.episode}, not \"${link.title}\"; skipped")
            return
        }
        resolved.urls.amap { page ->
            val mirrors = try {
                KatDramaParser.mirrors(app.get(page, referer = "$SITE/", timeout = TIMEOUT_S).text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(name, "vidbasic $page failed: ${e.message}")
                emptyList()
            }
            mirrors.amap { mirror ->
                try {
                    loadExtractor(mirror, page, subtitleCallback, callback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(name, "Mirror $mirror failed: ${e.message}")
                }
            }
        }
    }
}
