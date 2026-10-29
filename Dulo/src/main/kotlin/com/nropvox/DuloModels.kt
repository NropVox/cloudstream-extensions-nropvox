package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Movie or episode `data`: what ZCDN, the resolver and the subtitle search are keyed by. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DuloLink(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("imdbId") val imdbId: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)

/** ZCDN `/cdn` answer: `{"ready":true,"url":…,"rb":…}` or `{"ready":false,"status":"building"}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZcdnAnswer(
    @JsonProperty("ready") val ready: Boolean = false,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("status") val status: String? = null,
)

/** `/api/streams/resolve`: polled while `pending` is true. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResolveAnswer(
    @JsonProperty("found") val found: Boolean = false,
    @JsonProperty("pending") val pending: Boolean = false,
    @JsonProperty("sources") val sources: List<ResolveSource> = emptyList(),
)

/** `label` is `<Server>[ · <quality>]`; every URL is a zstream-proxy URL that needs dulo's Referer. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResolveSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("captions") val captions: List<ResolveCaption> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResolveCaption(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("display") val display: String? = null,
    @JsonProperty("language") val language: String? = null,
)

/** One track of `/api/subtitles/search`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DuloSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("display") val display: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("isHearingImpaired") val hearingImpaired: Boolean = false,
)

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
