package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbPage(
    @JsonProperty("results") val results: List<TmdbItem>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbItem(
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
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbDetails(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("tagline") val tagline: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("origin_country") val originCountry: List<String>? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
    @JsonProperty("seasons") val seasons: List<TmdbSeasonSummary>? = null,
    @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null,
    @JsonProperty("recommendations") val recommendations: TmdbPage? = null,
    @JsonProperty("videos") val videos: TmdbVideos? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbGenre(@JsonProperty("id") val id: Int? = null, @JsonProperty("name") val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideos(@JsonProperty("results") val results: List<TmdbVideo>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideo(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeasonSummary(@JsonProperty("season_number") val seasonNumber: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeason(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)
