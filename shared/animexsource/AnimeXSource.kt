package com.nropvox

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * AnimeX's `pp` REST API for episode streams, keyed by the site's slug, and the GraphQL lookup from an AniList id to
 * that slug. Shared by the AnimeX and OmniStream plugins.
 */
object AnimeXSource {
    const val GRAPHQL = "https://graphql.animex.one/graphql"
    private const val PP_API = "https://pp.animex.one/rest/api"
    private val FALLBACK_PROVIDERS = listOf("yuki")
    private const val SLUG_QUERY = "query(\$anilistId: Int) { anime(anilistId: \$anilistId) { id } }"

    val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    // GraphQL allows 30 requests a minute; a title's slug never changes.
    private val slugs = ConcurrentHashMap<Int, String>()

    /** The slug `pp` wants for an AniList entry, or null if AnimeX doesn't have it. */
    suspend fun slug(anilistId: Int): String? {
        slugs[anilistId]?.let { return it }
        val body = mapOf("query" to SLUG_QUERY, "variables" to mapOf("anilistId" to anilistId)).toJson()
            .toRequestBody("application/json".toMediaType())
        val response = app.post(GRAPHQL, headers = browserHeaders, requestBody = body)
        val slug = tryParseJson<SlugResponse>(response.text)?.data?.anime?.id ?: return null
        slugs[anilistId] = slug
        return slug
    }

    suspend fun load(
        slug: String,
        episode: Int,
        lang: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeQuery = "id=${encode(slug)}&epNum=$episode"
        val servers = fetch<Servers>("$PP_API/servers?$episodeQuery")
        val providers = (if (lang == "dub") servers?.dubProviders else servers?.subProviders)
            .orEmpty().mapNotNull { it.id }.ifEmpty { FALLBACK_PROVIDERS }

        val seenSubtitles = mutableSetOf<String>()
        val subtitleLabels = mutableMapOf<String, Int>()
        var found = false

        // Providers fail independently (nero has been returning 403 upstream), so try them all.
        providers.amap { provider ->
            val sources = fetch<Sources>("$PP_API/sources?$episodeQuery&type=$lang&providerId=${encode(provider)}")
                ?: return@amap
            // Each provider's media hosts check for the Referer of the embed it was scraped from.
            val mediaHeaders = browserHeaders + sources.headers.orEmpty()
            val label = "$name ${provider.replaceFirstChar { it.uppercase() }} ${lang.uppercase()}"

            for (source in sources.sources.orEmpty()) {
                val url = source.url ?: continue
                val isHls = source.type?.contains("mpegurl", ignoreCase = true) == true || url.contains(".m3u8")
                // Segments sit behind fake .jpg/.html/.js names, so let the player probe the playlist.
                callback(newExtractorLink(label, label, url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = sources.headers?.get("Referer") ?: ""
                    quality = source.quality?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                    headers = mediaHeaders
                })
                found = true
            }

            for (track in sources.tracks.orEmpty()) {
                val url = track.url ?: continue
                // Storyboard and thumbnail VTTs share the list with captions.
                if (track.kind != null && track.kind != "captions" && track.kind != "subtitles") continue
                val trackLabel = synchronized(seenSubtitles) {
                    if (!seenSubtitles.add(url)) return@synchronized null
                    val base = track.label?.takeIf { it.isNotBlank() } ?: track.lang?.takeIf { it.isNotBlank() } ?: "Unknown"
                    val count = (subtitleLabels[base] ?: 0) + 1
                    subtitleLabels[base] = count
                    if (count == 1) base else "$base $count"
                } ?: continue
                subtitleCallback(newSubtitleFile(trackLabel, url) { headers = mediaHeaders })
            }
        }
        return found
    }

    private suspend inline fun <reified T> fetch(url: String): T? =
        tryParseJson<T>(app.get(url, headers = browserHeaders).text)

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    data class SlugAnime(val id: String? = null)

    data class SlugData(val anime: SlugAnime? = null)

    data class SlugResponse(val data: SlugData? = null)

    data class Provider(val id: String? = null)

    data class Servers(val subProviders: List<Provider>? = null, val dubProviders: List<Provider>? = null)

    data class Source(val url: String? = null, val quality: String? = null, val type: String? = null)

    data class Track(
        val url: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val kind: String? = null,
    )

    data class Sources(
        val sources: List<Source>? = null,
        val tracks: List<Track>? = null,
        val headers: Map<String, String>? = null,
    )
}
