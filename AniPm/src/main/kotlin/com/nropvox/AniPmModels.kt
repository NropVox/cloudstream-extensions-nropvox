package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A title card from search, catalog, relations and latest-episodes.
 * For `source == "anilist"` the real id is [anilistId]; [id] is sometimes offset by 1 000 000.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApCard(
    val id: Long? = null,
    val source: String? = null,
    val anilistId: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val type: String? = null,
    val subCount: Int? = null,
    val dubCount: Int? = null,
    val routeId: String? = null,
)

/** `GET /api/anime/catalog` and `/api/anime/latest-episodes`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApPage(val items: List<ApCard>? = null, val hasNextPage: Boolean? = null)

/** `GET /api/anime/series/<id>` (settlar) or `/api/anime/ani/<anilistId>` (anilist). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApDetails(
    val id: Long? = null,
    val source: String? = null,
    val title: String? = null,
    val native: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val heroBackdrop: String? = null,
    val logo: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val rating: String? = null,
    /** Minutes; a number on most titles, "24 min" on some settlar ones. */
    val duration: String? = null,
    val status: String? = null,
    val type: String? = null,
    val genres: List<String>? = null,
    val synopsis: String? = null,
    val malId: String? = null,
    val anilistId: String? = null,
    val providerTitles: List<String>? = null,
    val episodes: List<ApEpisode>? = null,
    val relations: List<ApCard>? = null,
    val nextAiring: ApNextAiring? = null,
    val routeId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApEpisode(
    val number: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val sub: Boolean? = null,
    val dub: Boolean? = null,
    val runtime: String? = null,
    val rating: String? = null,
    val aired: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApNextAiring(val episode: Int? = null, val airingAt: Long? = null)

/** `GET /api/anime/playback-bootstrap/<source>/<id>?ep=&lang=`; 404 when nothing plays. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApBootstrap(val effectiveLanguage: String? = null, val settlarSelection: String? = null)

/** `GET /api/anime/settlar/session`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApSettlarSession(val embedUrl: String? = null)

/** `GET https://embed.settlar.io/api/embed/session?t=`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ApEmbedSession(
    val kind: String? = null,
    val source: String? = null,
    val audioLang: String? = null,
    val subtitles: List<ApSubtitle>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApSubtitle(val url: String? = null, val label: String? = null, val srclang: String? = null)

/** What an episode stores for [AniPm.loadLinks]. */
data class ApLinkData(val source: String, val id: String, val ep: Int, val lang: String)
