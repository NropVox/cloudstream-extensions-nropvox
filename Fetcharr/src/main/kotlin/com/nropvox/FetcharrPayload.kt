package com.nropvox

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper

/*
 * The fetcharr://download payload (Fetcharr docs/fetcharr-android-intents.md) and every decision about what goes
 * into it. Pure Kotlin so it can be unit tested; FetcharrActions.kt maps Cloudstream types onto these.
 */

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FetcharrPayload(
    val headers: Map<String, String>? = null,
    val single: FetcharrSingle? = null,
    val batch: FetcharrBatch? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FetcharrSingle(
    val url: String,
    val title: String? = null,
    val showName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val subtitles: List<FetcharrSubtitle>? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FetcharrBatch(
    val showName: String,
    val season: Int,
    val year: Int? = null,
    val episodes: List<FetcharrEpisode>,
)

/** [headers] is Fetcharr's per-episode extension, merged over the root headers. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FetcharrEpisode(
    val episode: Int,
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<FetcharrSubtitle>? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class FetcharrSubtitle(val url: String, val lang: String? = null)

/** What is known about the clicked movie/episode (from Cloudstream's ResultEpisode and result page). */
data class MediaMeta(
    val showName: String,
    val episodeName: String?,
    val season: Int?,
    val episode: Int,
    val isMovie: Boolean,
    val year: Int?,
)

/** A resolved link, reduced to what selection and the payload need. */
data class LinkInfo(
    val source: String,
    val name: String,
    val quality: Int,
    val url: String,
    val referer: String,
    val headers: Map<String, String>,
)

/** A resolved subtitle: [ietfTag] is Cloudstream's best IETF guess, [isUrl] false for files/embedded tracks. */
data class SubtitleInfo(
    val url: String,
    val headers: Map<String, String>,
    val ietfTag: String?,
    val isUrl: Boolean,
)

/** One episode of a season send, with the link chosen for it. */
data class SeasonEntry(
    val episode: Int,
    val episodeName: String?,
    val link: LinkInfo,
    val subtitles: List<SubtitleInfo>,
)

object FetcharrPayloadBuilder {
    const val PACKAGE = "com.istarvin.fetcharr"
    const val URI = "fetcharr://download"
    const val EXTRA = "payload"

    /**
     * Season for episodes the provider gives none (common for anime): Fetcharr needs one to file an episode,
     * and Jellyfin expects single-season shows under Season 1. Editable in Fetcharr's sheet.
     */
    const val FALLBACK_SEASON = 1

    private val mapper = ObjectMapper()

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /**
     * The headers Cloudstream's player sends for [link] (CS3IPlayer.createVideoSource): the link's own headers,
     * its referer unless a Referer header is already set, and [defaultUserAgent] unless a User-Agent is set.
     */
    fun effectiveHeaders(link: LinkInfo, defaultUserAgent: String): Map<String, String> {
        val headers = LinkedHashMap(link.headers)
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            headers["Referer"] = link.referer
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = defaultUserAgent
        }
        return headers
    }

    /** "en-US" -> "en"; null when the tag has no two-letter primary language. */
    fun iso6391(ietfTag: String?): String? =
        ietfTag?.substringBefore('-')?.trim()?.lowercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }

    /**
     * Subtitles that can travel with a video sent with [videoHeaders]: http(s) URLs whose own headers are all
     * present in that one shared header map (the payload has no per-subtitle headers). English only; subtitles with
     * an unknown language are dropped. Deduplicated by URL.
     */
    fun subtitles(subs: List<SubtitleInfo>, videoHeaders: Map<String, String>): List<FetcharrSubtitle> {
        val lowered = videoHeaders.mapKeys { it.key.lowercase() }
        return subs
            .filter { it.isUrl && isHttpUrl(it.url) && iso6391(it.ietfTag) == "en" }
            .filter { sub -> sub.headers.all { (k, v) -> lowered[k.lowercase()] == v } }
            .distinctBy { it.url }
            .map { FetcharrSubtitle(it.url, "en") }
    }

    fun single(meta: MediaMeta, link: LinkInfo, subs: List<SubtitleInfo>, defaultUserAgent: String): FetcharrPayload {
        val headers = effectiveHeaders(link, defaultUserAgent)
        val subtitles = subtitles(subs, headers)
        val single = if (meta.isMovie) {
            FetcharrSingle(url = link.url, title = meta.showName, year = meta.year, subtitles = subtitles)
        } else {
            FetcharrSingle(
                url = link.url,
                title = meta.episodeName?.takeIf { it.isNotBlank() },
                showName = meta.showName,
                // Without a season Fetcharr treats the item as a movie; see FALLBACK_SEASON.
                season = meta.season ?: FALLBACK_SEASON,
                episode = meta.episode,
                year = meta.year,
                subtitles = subtitles,
            )
        }
        return FetcharrPayload(headers = headers, single = single)
    }

    /** One batch for a season; each episode carries its own headers, since links can differ per episode. */
    fun batch(
        showName: String,
        season: Int?,
        year: Int?,
        entries: List<SeasonEntry>,
        defaultUserAgent: String,
    ): FetcharrPayload {
        val episodes = entries
            .distinctBy { it.episode }
            .sortedBy { it.episode }
            .map { entry ->
                val headers = effectiveHeaders(entry.link, defaultUserAgent)
                FetcharrEpisode(
                    episode = entry.episode,
                    url = entry.link.url,
                    title = entry.episodeName?.takeIf { it.isNotBlank() },
                    headers = headers,
                    subtitles = subtitles(entry.subtitles, headers),
                )
            }
        return FetcharrPayload(
            batch = FetcharrBatch(showName, season ?: FALLBACK_SEASON, year, episodes),
        )
    }

    /**
     * The link of another episode that best matches [template], the source the user picked on the clicked
     * episode: same server and label first, then same server, then anything; within a tier the closest
     * quality, preferring higher. Null when [candidates] is empty.
     */
    fun <T> pickMatching(template: LinkInfo, candidates: List<T>, info: (T) -> LinkInfo): T? =
        candidates.minWithOrNull(
            compareBy<T> {
                val link = info(it)
                when {
                    link.source == template.source && link.name == template.name -> 0
                    link.source == template.source -> 1
                    else -> 2
                }
            }
                .thenBy { kotlin.math.abs(info(it).quality - template.quality) }
                .thenByDescending { info(it).quality }
        )

    /** The year in a page label such as "2026" or "2019 - 2023". */
    fun yearFrom(text: String?): Int? =
        text?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

    fun toJson(payload: FetcharrPayload): String = mapper.writeValueAsString(payload)
}
