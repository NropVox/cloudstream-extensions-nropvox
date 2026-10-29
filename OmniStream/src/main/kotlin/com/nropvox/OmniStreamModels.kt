package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Movie or episode `data`: the union of what every TMDB-keyed source needs, plus what the anime mapping needs.
 * [kind] is [OmniStreamParser.KIND_GENERAL], [OmniStreamParser.KIND_ANIME] or [OmniStreamParser.KIND_ASIAN].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniLink(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("imdbId") val imdbId: String? = null,
    @JsonProperty("totalSeasons") val totalSeasons: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("kind") val kind: String = OmniStreamParser.KIND_GENERAL,
    /** First air date of this episode, or the movie's release date. */
    @JsonProperty("airDate") val airDate: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbPage(
    @JsonProperty("results") val results: List<OmniTmdbItem>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
    @JsonProperty("origin_country") val originCountry: List<String>? = null,
    @JsonProperty("original_language") val originalLanguage: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbDetails(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("original_language") val originalLanguage: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
    @JsonProperty("number_of_seasons") val numberOfSeasons: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("origin_country") val originCountry: List<String>? = null,
    @JsonProperty("genres") val genres: List<OmniTmdbGenre>? = null,
    @JsonProperty("seasons") val seasons: List<OmniTmdbSeasonSummary>? = null,
    @JsonProperty("external_ids") val externalIds: OmniTmdbExternalIds? = null,
    @JsonProperty("credits") val credits: OmniTmdbCredits? = null,
    @JsonProperty("recommendations") val recommendations: OmniTmdbPage? = null,
    @JsonProperty("videos") val videos: OmniTmdbVideos? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbGenre(@JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbCredits(@JsonProperty("cast") val cast: List<OmniTmdbCast>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbVideos(@JsonProperty("results") val results: List<OmniTmdbVideo>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbVideo(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbSeasonSummary(@JsonProperty("season_number") val seasonNumber: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbSeason(@JsonProperty("episodes") val episodes: List<OmniTmdbEpisode>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OmniTmdbEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

/** Body of a `graphql.anilist.co` POST. */
data class AniListQuery(
    @JsonProperty("query") val query: String,
    @JsonProperty("variables") val variables: Map<String, Any>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListResponse(@JsonProperty("data") val data: AniListData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListData(@JsonProperty("Page") val page: AniListPage? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPage(@JsonProperty("media") val media: List<AniListMedia>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("startDate") val startDate: AniListDate? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("synonyms") val synonyms: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListDate(
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("month") val month: Int? = null,
    @JsonProperty("day") val day: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("native") val native: String? = null,
)
