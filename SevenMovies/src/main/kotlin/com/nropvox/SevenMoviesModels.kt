package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty

internal data class SevenCard(
    val id: Int? = null, val title: String? = null, val year: String? = null,
    val rating: Double? = null, val mediaType: String? = null, val poster: String? = null,
)
internal data class SevenPage(
    val items: List<SevenCard>? = null, val results: List<SevenCard>? = null, val totalPages: Int? = null,
)
internal data class SevenRows(val rows: List<SevenRow>? = null, val hasMore: Boolean? = null)
internal data class SevenRow(val title: String? = null, val items: List<SevenCard>? = null)
internal data class SevenDetails(
    val id: Int? = null, val title: String? = null, val name: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val poster: String? = null,
    @JsonProperty("backdrop_path") val backdrop: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val rating: Double? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
    val overview: String? = null, val runtime: Int? = null, val status: String? = null,
    val genres: List<SevenNamed>? = null, val seasons: List<SevenSeason>? = null,
    val credits: SevenCredits? = null, val videos: SevenVideos? = null,
    val recommendations: SevenRecommendations? = null,
)
internal data class SevenNamed(val name: String? = null)
internal data class SevenSeason(@JsonProperty("season_number") val number: Int? = null)
internal data class SevenCredits(val cast: List<SevenCast>? = null)
internal data class SevenCast(
    val name: String? = null, val character: String? = null,
    @JsonProperty("profile_path") val profile: String? = null,
)
internal data class SevenVideos(val results: List<SevenVideo>? = null)
internal data class SevenVideo(val key: String? = null, val site: String? = null, val type: String? = null)
internal data class SevenRecommendations(val results: List<SevenDetails>? = null)
internal data class SevenEpisodes(val episodes: List<SevenEpisode>? = null)
internal data class SevenEpisode(
    @JsonProperty("episode_number") val number: Int? = null,
    @JsonProperty("still_path") val still: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    val name: String? = null, val overview: String? = null, val runtime: Int? = null,
)
