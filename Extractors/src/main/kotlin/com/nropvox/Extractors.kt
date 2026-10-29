package com.nropvox

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.net.URI

private const val FIREFOX_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"
private val firefoxHeaders = mapOf("User-Agent" to FIREFOX_USER_AGENT)
private val m3u8OrMasterRegex = Regex("""[:=]\s*"([^"\s]+(\.m3u8|master\.txt)[^"\s]*)""")

private fun String.fileCode(): String = substringBefore('?').trimEnd('/').substringAfterLast('/')

private suspend fun ExtractorApi.emitPackedM3u8(
    source: String,
    text: String,
    referer: String,
    keep: (String) -> Boolean,
    callback: (ExtractorLink) -> Unit,
) {
    m3u8OrMasterRegex.findAll(getAndUnpack(text)).map { match ->
        URI(referer).resolve(match.groupValues[1]).toString()
    }.distinct().forEach { streamUrl ->
        if (!keep(streamUrl)) return@forEach
        try {
            generateM3u8(source, streamUrl, referer).forEach(callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(source, "Playlist request failed: ${e.message}")
        }
    }
}

open class Vidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.fileCode()
        if (id.isBlank()) return

        val res = app.post("$mainUrl/api/stream", json = mapOf("filecode" to id))
            .parsedSafe<Result>() ?: return

        generateM3u8(name, res.url, mainUrl)
            .forEach(callback)
    }

    data class Result(
        @JsonProperty("streaming_url") val url: String
    )
}

class RubyVidHub : ExtractorApi() {
    override val name = "RubyVidHub"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.fileCode()
        if (id.isBlank()) return

        emitPackedM3u8(
            source = name,
            text = app.post("$mainUrl/dl", data = mapOf("file_code" to id, "op" to "embed")).text,
            referer = mainUrl,
            keep = { true },
            callback = callback,
        )
    }
}

open class DoodStream : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://myvidplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl =
            url.replace("doply.net", "myvidplay.com").replace("vide0.net", "myvidplay.com")
        Log.d("STB_Dood", "url = $url")
        val response = app.get(
            embedUrl,
            referer = mainUrl,
            headers = firefoxHeaders
        ).text

        val md5Regex = Regex("/pass_md5/([^/]*)/([^/']*)")
        val md5Match = md5Regex.find(response)
        val md5Path = md5Match?.value.toString()
        val expiry = md5Match?.groupValues?.getOrNull(1) ?: ""
        val token = md5Match?.groupValues?.getOrNull(2) ?: ""
        val md5Url = mainUrl + md5Path

        val md5Response = app.get(
            md5Url,
            referer = embedUrl,
            headers = firefoxHeaders
        ).text

        val baseLink = md5Response.trim()
        val directLink = if (token.isNotEmpty() && expiry.isNotEmpty()) {
            "$baseLink?token=$token&expiry=${expiry}000"
        } else {
            baseLink
        }

        callback(
            newExtractorLink(
                source = this.name, name = this.name, url = directLink, type = INFER_TYPE
            ) {
                this.referer = "https://myvidplay.com"
                this.quality = Qualities.Unknown.value
                this.headers = firefoxHeaders
            })
    }
}

class DoodDoply : DoodStream() {
    override var mainUrl = "https://doply.net"
    override var name = "DoodDoply"
}

class DoodVideo : DoodStream() {
    override var mainUrl = "https://vide0.net"
    override var name = "DoodVideo"
}

class Ds2Play : DoodStream() {
    override var mainUrl = "https://ds2play.com"
}

class D000d : DoodStream() {
    override var mainUrl = "https://d000d.com"
}

class Streamhihi : StreamWishExtractor() {
    override var name = "Streamhihi"
    override var mainUrl = "https://streamhihi.com"
}

class Javsw : StreamWishExtractor() {
    override var mainUrl = "https://javsw.me"
    override var name = "Javsw"
}

class VidhideVIP : VidHidePro() {
    override var mainUrl = "https://vidhidevip.com"
    override var name = "VidhideVIP"
}

class Javlion : VidHidePro() {
    override var mainUrl = "https://javlion.xyz"
    override var name = "Javlion"
}

class VidHidePro1 : VidHidePro() {
    override var mainUrl = "https://filelions.live"
}

class VidHidePro2 : VidHidePro() {
    override var mainUrl = "https://filelions.online"
}

class VidHidePro3 : VidHidePro() {
    override var mainUrl = "https://filelions.to"
}

class VidHidePro4 : VidHidePro() {
    override var mainUrl = "https://kinoger.be"
}

class VidHidePro6 : VidHidePro() {
    override var mainUrl = "https://vidhidepre.com"
}

class VidHidePro7 : VidHidePro() {
    override var mainUrl = "https://vidhidehub.com"
}

class Dhcplay : VidHidePro() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Dhtpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://dhtpre.com"
}

class Peytonepre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://peytonepre.com"
}

class Movearnpre : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://movearnpre.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        emitPackedM3u8(
            source = name,
            text = app.get(url).text,
            referer = "$mainUrl/",
            keep = { !it.contains("?") },
            callback = callback,
        )
    }
}

class JavVids : VidHidePro() {
    override var name = "JavVids"
    override var mainUrl = "https://jav-vids.xyz"
}

class Dintezuvio : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://dintezuvio.com"
}

class Hanerix : ExtractorApi() {
    override val name = "HGLink"
    override val mainUrl = "https://hanerix.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        emitPackedM3u8(
            source = name,
            text = app.get(url).text,
            referer = "$mainUrl/",
            keep = { it.contains("?") },
            callback = callback,
        )
    }
}

class HgLink : ExtractorApi() {
    override val name = "HGLink"
    override val mainUrl = "https://hglink.to"
    override val requiresReferer = false

    private val redirectUrl = "https://hanerix.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.fileCode()
        if (id.isBlank()) return

        val newUrl = "$redirectUrl/e/$id"

        loadExtractor(newUrl, referer, subtitleCallback, callback)
    }
}

class RyderJet : VidHidePro() {
    override var name = "RyderJet"
    override var mainUrl = "https://ryderjet.com"
}

class MyCloudZ : VidHidePro() {
    override var mainUrl = "https://mycloudz.cc"
    override var name = "MyCloudZ"
}

/** Players that name their stream in `var urlPlay = '…';`. */
open class Turboplayers : ExtractorApi() {
    override val mainUrl = "https://turboplayers.xyz"
    override val name = "TurboPlayer"
    override val requiresReferer = false

    private val urlRegex = Regex("""var urlPlay = '(.*)';""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val text = app.get(url).text

        urlRegex.find(text)?.groupValues?.get(1)?.let {
            if (it.contains("m3u8")) {
                generateM3u8(name, it, mainUrl).forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, it)
                )
            }
        }
    }
}

class LulusStream : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = url.substringAfterLast("/")
        val post = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to filecode,
                "auto" to "1",
                "referer" to (referer ?: "")
            )
        ).document
        post.selectFirst("script:containsData(vplayer)")?.data()?.let { script ->
            Regex("file:\"(.*)\"").find(script)?.groupValues?.get(1)?.let { link ->
                callback(newExtractorLink(name, name, link) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                })
            }
        }
    }
}

class Javclan : ExtractorApi() {
    override val name = "Javclan"
    override val mainUrl = "https://javclan.com"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val res = app.get(url, referer = referer)
        val script = res.document.selectFirst("script:containsData(sources)")?.data().toString()
        Regex("file:\"(.*?)\"").find(script)?.groupValues?.get(1)?.let { link ->
            return listOf(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = referer ?: ""
            })
        }
        return null
    }
}

class Javggvideo : Turboplayers() {
    override val name = "Javgg Video"
    override val mainUrl = "https://javggvideo.xyz"
}

class Swhoi : Filesim() {
    override var mainUrl = "https://swhoi.com"
    override var name = "Streamwish"
}

class MixDropis : MixDrop() {
    override var mainUrl = "https://mixdrop.is"
}

class Javmoon : Filesim() {
    override var mainUrl = "https://javmoon.me"
    override var name = "FileMoon"
}


class StbP2P : VidStack() {
    override var mainUrl = "https://stb.strp2p.com"
    override var name = "STBP2P"
}

class Playerupnone : VidStack() {
    override var mainUrl = "https://player.upn.one"
    override var name = "UPNP2P"
}

open class F4Stream : ExtractorApi() {
    override val mainUrl = "https://f4stream.com"
    override val name = "F4Stream"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer)
        val apiPath = page.document.selectFirst("[data-player][data-api]")
            ?.attr("data-api")?.takeIf { it.isNotBlank() } ?: return
        val apiUrl = URI(page.url).resolve(apiPath).toString()
        val separator = if (apiUrl.contains('?')) "&" else "?"
        // The site's native HLS mode supplies a signed link suitable for external players.
        val response = app.get(
            "$apiUrl${separator}ab=0&n=1",
            referer = page.url,
            headers = mapOf("Accept" to "application/json")
        ).parsedSafe<PlayResponse>() ?: return
        val streamPath = response.url?.takeIf { it.isNotBlank() } ?: return
        val streamUrl = URI(page.url).resolve(streamPath).toString()
        val streamType = if (response.type == "hls") ExtractorLinkType.M3U8 else INFER_TYPE
        callback(newExtractorLink(name, name, streamUrl, streamType) {
            this.referer = page.url
            this.quality = Qualities.Unknown.value
        })
    }

    data class PlayResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}

class F4Scdn : F4Stream() {
    override val mainUrl = "https://f4scdn.com"
}

open class Turtleviplay : ExtractorApi() {
    override var name = "Turtleviplay"
    override var mainUrl = "https://turtleviplay.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val m3u8 = res.selectFirst("#video_player")?.attr("data-hash") ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to "https://turtleviplay.xyz",
                    "Accept" to "*/*",
                )
            }
        )
    }
}

class Turboviplay : Turtleviplay() {
    override var name = "Turboviplay"
    override var mainUrl = "https://turboviplay.com"
}

class Emturbovid : ExtractorApi() {
    override val name = "Emturbovid"
    override val mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val videoUrl = doc.selectFirst("#video_player")?.attr("data-hash") ?: return

        generateM3u8(name, videoUrl, url).forEach(callback)
    }
}

class Reely : ExtractorApi() {
    override val name = "Reely"
    override val mainUrl = "https://embed.reely.live"
    override val requiresReferer = true

    private val reelxiaProxy = "https://reelxia-proxy.istarvin.workers.dev"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfter("v=").substringBefore("&")

        generateM3u8(name, "$reelxiaProxy/$videoId/2", mainUrl).forEach(callback)
        subtitleCallback(newSubtitleFile("English", "$reelxiaProxy/$videoId/subtitle/en"))
    }
}

class HLSProxy(
    private val sharedPref: SharedPreferences? = null
) : ExtractorApi() {
    override val name = "HLSProxy"
    override val mainUrl = "http://hls-proxy"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val proxy = sharedPref?.getString(HLS_PROXY_URL_PREF_KEY, HLS_PROXY_DEFAULT_URL)
            ?.takeIf { it.isNotBlank() }
            ?: HLS_PROXY_DEFAULT_URL

        val urlEncoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(
                url,
                "utf-8"
            )
        }

        generateM3u8(
            source = name,
            streamUrl = "$proxy/proxy?url=$urlEncoded",
            referer = referer ?: mainUrl
        ).forEach(
            callback
        )
    }
}

/**
 * SupJav's FST server. Of the packed player's links only the relative `/stream/…/master.m3u8` (hls4) answers;
 * its segments are TikTok CDN "PNG" files, so the master goes straight to the player instead of generateM3u8.
 */
class Fc2Stream : ExtractorApi() {
    override val name = "FC2Stream"
    override val mainUrl = "https://fc2stream.tv"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer)
        val streamUrl = m3u8OrMasterRegex.findAll(getAndUnpack(page.text))
            .map { URI(page.url).resolve(it.groupValues[1]).toString() }
            .firstOrNull { it.contains("/stream/") && it.substringBefore('?').endsWith(".m3u8") } ?: return
        callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
            this.referer = "$mainUrl/"
            this.quality = Qualities.Unknown.value
        })
    }
}

/** SupJav's VAS server: a Vidara player. Without device/codecs hints the API answers with the h265 rendition. */
class Vvaassjjav : Vidara() {
    override val mainUrl = "https://vvaassjjav.xyz"
}

/** SupJav's EVS server (EarnVids). Its signed hls2 master plays; the hls3 master.txt host blocks. */
class Evsishere : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://evsishere.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        emitPackedM3u8(
            source = name,
            text = app.get(url, referer = referer).text,
            referer = "$mainUrl/",
            keep = { it.substringBefore('?').endsWith(".m3u8") },
            callback = callback,
        )
    }
}

/** SupJav's LUC server: a LuluStream player whose packed jwplayer setup holds a signed master. */
class Luminscdn : ExtractorApi() {
    override val name = "LuluStream"
    override val mainUrl = "https://luminscdn.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        emitPackedM3u8(
            source = name,
            text = app.get(url, referer = referer).text,
            referer = "$mainUrl/",
            keep = { it.substringBefore('?').endsWith(".m3u8") },
            callback = callback,
        )
    }
}

class AsnWish : ExtractorApi() {
    override val name = "AsnWish"
    override val mainUrl = "https://asnwish.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        emitPackedM3u8(
            source = name,
            text = app.get(url, referer = referer).text,
            referer = mainUrl,
            keep = { !it.contains("?") },
            callback = callback,
        )
    }
}
