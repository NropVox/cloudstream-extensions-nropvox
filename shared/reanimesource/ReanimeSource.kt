package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * reanime.to's flixcloud player, keyed by AniList id: `/api/flix/{anilist}/{ep}` lists its embeds, whose page carries
 * an encrypted master URL; the masked playlists are served unmasked over loopback by [ReanimeLocalServer]. Shared by
 * the Reanime and OmniStream plugins.
 */
object ReanimeSource {
    const val SITE = "https://reanime.to"

    /** Emits a link per server for each of [langs] (`sub`, `dub`); `dub` only when the site lists a dub server. */
    suspend fun load(
        anilistId: Int,
        malId: Int,
        episode: Int,
        langs: List<String>,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val flix = tryParseJson<RaFlix>(app.get(SITE + ReanimeParser.flixPath(anilistId, malId, episode)).text)
        val servers = flix?.servers.orEmpty().filter { !it.dataLink.isNullOrBlank() }
        // Sub and dub share an embed; what differs per server is only where it is hosted.
        val embeds = servers.distinctBy { it.serverName to it.dataLink }
        if (embeds.isEmpty()) return false
        val hasDub = servers.any { it.dataType == "dub" }

        val found = AtomicBoolean(false)
        val subtitlesSent = AtomicBoolean(false)
        langs.filter { it != "dub" || hasDub }.amap { lang ->
            val dub = lang == "dub"
            embeds.amap { server ->
                runCatching {
                    val embedUrl = server.dataLink!!
                    val html = app.get(embedUrl, referer = "$SITE/").text
                    val embed = ReanimeParser.parseEmbed(html)
                    val tokenJson = app.get("https://flixcloud.cc/api/m3u8/${embed.token}", referer = embedUrl).text
                    val decoded = ReanimeParser.decodeEmbed(embed, tokenJson)
                    val masterBody = app.get(decoded.masterUrl, referer = ReanimeParser.FLIX_REFERER).text
                    val master = ReanimeParser.unmaskPlaylist(masterBody, decoded.pk)
                    if (!master.startsWith("#EXTM3U")) throw IllegalStateException("Master playlist did not unmask")

                    val session = ReanimeLocalServer.session(decoded.pk)
                    val playlist = ReanimeParser.rewriteMaster(master, decoded.masterUrl, if (dub) "eng" else "jpn", session::proxy)
                    val label = "$name ${server.serverName ?: "Server"} ${if (dub) "DUB" else "SUB"}"
                    callback(newExtractorLink(label, label, ReanimeLocalServer.publish(session, playlist), ExtractorLinkType.M3U8) {
                        referer = ReanimeParser.FLIX_REFERER
                        headers = mapOf("Referer" to ReanimeParser.FLIX_REFERER)
                        quality = ReanimeParser.topHeight(master) ?: 0
                    })
                    found.set(true)

                    if (subtitlesSent.compareAndSet(false, true)) {
                        for (subtitle in embed.subtitles) subtitleCallback(newSubtitleFile(subtitle.language, subtitle.url))
                    }
                }.onFailure { Log.w(name, "${server.serverName} for AniList $anilistId ep $episode ($lang): $it") }
            }
        }
        return found.get()
    }
}
