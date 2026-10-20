package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty

internal data class TonkaLink(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)

internal data class TonkaPage(
    @JsonProperty("results") val results: List<TonkaItem>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
)

internal data class TonkaItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

internal data class TonkaDetails(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
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
    @JsonProperty("genres") val genres: List<TonkaGenre>? = null,
    @JsonProperty("seasons") val seasons: List<TonkaSeasonSummary>? = null,
    @JsonProperty("external_ids") val externalIds: TonkaExternalIds? = null,
    @JsonProperty("credits") val credits: TonkaCredits? = null,
    @JsonProperty("recommendations") val recommendations: TonkaPage? = null,
    @JsonProperty("videos") val videos: TonkaVideos? = null,
)

internal data class TonkaGenre(@JsonProperty("name") val name: String? = null)

internal data class TonkaExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

internal data class TonkaCredits(@JsonProperty("cast") val cast: List<TonkaCast>? = null)

internal data class TonkaCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

internal data class TonkaVideos(@JsonProperty("results") val results: List<TonkaVideo>? = null)

internal data class TonkaVideo(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
)

internal data class TonkaSeasonSummary(@JsonProperty("season_number") val seasonNumber: Int? = null)

internal data class TonkaSeason(@JsonProperty("episodes") val episodes: List<TonkaEpisode>? = null)

internal data class TonkaEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)
