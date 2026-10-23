package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaTitle(val english: String? = null, val romaji: String? = null, val native: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaCover(val medium: String? = null, val large: String? = null, val extra_large: String? = null)

/** A card in the home rows and in search results. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RaCard(
    val anime_id: String? = null,
    val anilist_id: Int? = null,
    val title: RaTitle? = null,
    val cover_image: RaCover? = null,
    val format: String? = null,
    val genres: List<String>? = null,
    val season_year: Int? = null,
    val subbed: Int? = null,
    val dubbed: Int? = null,
    val average_score: Int? = null,
)

/** `GET /api/v1/home`: every row at once, plus the cursor that continues each pageable one. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RaHome(
    val latest_aired: List<RaCard>? = null,
    val latest_aired_cursor: String? = null,
    val new_on_site: List<RaCard>? = null,
    val new_on_site_cursor: String? = null,
    val trending: List<RaCard>? = null,
    val upcoming: List<RaCard>? = null,
    val upcoming_cursor: String? = null,
)

/** `GET /api/v1/home/{row}?cursor=…` */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RaCursorPage(val data: List<RaCard>? = null, val has_more: Boolean? = null, val next_cursor: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaSearch(val results: List<RaCard>? = null, val total: Int? = null, val offset: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaDate(val year: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaTrailer(val id: String? = null, val site: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaStudio(val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaNextAiring(val episode: Int? = null, val airing_at: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaDetails(
    val anime_id: String? = null,
    val anilist_id: Int? = null,
    val mal_id: Int? = null,
    val kitsu_id: Int? = null,
    val title: RaTitle? = null,
    val synonyms: List<String>? = null,
    val cover_image: RaCover? = null,
    val banner_image: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val season_year: Int? = null,
    val start_date: RaDate? = null,
    val duration: Int? = null,
    val average_score: Int? = null,
    val is_adult: Boolean? = null,
    val episodes_total: Int? = null,
    val trailer: RaTrailer? = null,
    val studios: List<RaStudio>? = null,
    val subbed: Int? = null,
    val dubbed: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaEpisode(
    val episode_number: Int? = null,
    val title: String? = null,
    val aired: String? = null,
    val thumbnail: String? = null,
    val subbed: Boolean? = null,
    val dubbed: Boolean? = null,
    val playable: Boolean? = null,
    val is_filler: Boolean? = null,
    val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaEpisodes(val data: List<RaEpisode>? = null, val total: Int? = null)

/** `GET /api/flix/{anilist}/{ep}`; HD-1 and HD-2 each list the same embed for sub and dub. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RaFlixServer(val serverName: String? = null, val dataLink: String? = null, val dataType: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RaFlix(val success: Boolean? = null, val servers: List<RaFlixServer>? = null)
