package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Document

/**
 * Pure parsing for the file-host interstitials linked from nexdrive pages (Vegamovies, XPrimeHub). Nothing here
 * touches the network or the Cloudstream library. This folder is compiled into each plugin that lists it as a
 * source directory; plugins load in separate class loaders, so the copies never clash.
 */
object NexdriveParser {
    /** File host of a nexdrive link, read from its URL: the alternative sources are bare links with no label. */
    enum class Host { GDIRECT, VCLOUD, HUBCLOUD, VDRIVE, PIXELDRAIN, OTHER }

    private val reurl = Regex("""var reurl = "([^"]+)"""")
    private val doubleBase64 = Regex("""atob\(atob\('([^']+)'\)\)""")
    private val hubcloudUrl = Regex("""var url = '([^']+)'""")
    private val vdriveShare = Regex("""/s/([A-Za-z0-9]+)""")
    private val pixeldrainFile = Regex("""/(?:u|api/file)/([A-Za-z0-9]+)""")
    private val whitespace = Regex("""\s+""")

    // The hosts rotate their TLDs (vcloud.zip, vcloud.beer, ...), so only the subdomain is matched.
    fun host(url: String): Host {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return when {
            host.startsWith("fastdl.") -> Host.GDIRECT
            host.startsWith("vcloud.") -> Host.VCLOUD
            host.startsWith("hubcloud.") -> Host.HUBCLOUD
            "vegadrive." in host -> Host.VDRIVE
            host.startsWith("pixeldrain.") -> Host.PIXELDRAIN
            else -> Host.OTHER
        }
    }

    /** G-Direct: the embed page names the direct file in `var reurl`, behind a `dl.php?link=` ad wrapper. */
    fun directUrl(embedHtml: String): String? = reurl.find(embedHtml)?.groupValues?.get(1)?.let {
        if ("dl.php?link=" in it) it.substringAfter("dl.php?link=") else it
    }

    /** V-Cloud landing page: the download button's target hides in `atob(atob('…'))`. */
    fun vcloudNext(landingHtml: String, decode: (String) -> String): String? =
        doubleBase64.find(landingHtml)?.groupValues?.get(1)?.let { decode(decode(it)) }

    /**
     * HubCloud drive page: the `#download` button (also in `var url`) opens the same server list as V-Cloud.
     * `/video/<id>` pages have neither; their "Generate Direct Download Link" button goes to `gamerxyt.com/hubcloud.php`.
     */
    fun hubcloudNext(doc: Document): String? =
        doc.selectFirst("a#download")?.attr("href")?.takeIf { it.startsWith("http") }
            ?: hubcloudUrl.find(doc.html())?.groupValues?.get(1)
            ?: doc.selectFirst("a[href*=hubcloud.php]")?.attr("href")?.takeIf { it.startsWith("http") }

    /** V-Drive share id of `https://one.vegadrive.app/s/<id>`. */
    fun vdriveId(url: String): String? = vdriveShare.find(url)?.groupValues?.get(1)

    /** Pixeldrain file id of `/u/<id>` or `/api/file/<id>`. */
    fun pixeldrainId(url: String): String? = pixeldrainFile.find(url)?.groupValues?.get(1)

    /** V-Cloud and HubCloud result page: server name (`FSLv2 Server`) to link. `Server : 1` has an empty href and is skipped. */
    fun mirrors(doc: Document): List<Pair<String, String>> = doc.select("a[href]").mapNotNull { a ->
        val text = a.text().replace("⚡", "").replace(whitespace, " ").trim()
        val href = a.attr("href")
        if (!text.startsWith("Download [") || !href.startsWith("http")) return@mapNotNull null
        text.substringAfter('[').substringBefore(']').trim() to href
    }
}

/** V-Drive `GET /api/file/<id>`: which mirror providers hold the file (`ready`, `pending`, `failed`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VdFile(val providers: List<VdProvider>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VdProvider(val provider: String? = null, val status: String? = null)

/** V-Drive `GET /download-link/<id>/skydrop`: `{"ready":false,"retry_after_ms":2500}` until the bridge has the file. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VdLink(
    @JsonProperty("download_url") val url: String? = null,
    @JsonProperty("retry_after_ms") val retryAfterMs: Long? = null,
)

/** Pixeldrain `GET /api/file/<id>/info`; taken-down files answer 451 on download. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PdInfo(@JsonProperty("can_download") val canDownload: Boolean? = null)
