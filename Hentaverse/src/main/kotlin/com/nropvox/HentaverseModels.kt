package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** `GET /series`: `pagination` sits next to `data`, and its `total` is capped at 200. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSeriesList(val data: HvSeriesItems? = null, val pagination: HvPagination? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSeriesItems(val items: List<HvSeries>? = null)

/** `GET /videos`, same envelope as [HvSeriesList]. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HvVideoList(val data: HvVideoItems? = null, val pagination: HvPagination? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvVideoItems(val items: List<HvVideo>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvPagination(val totalPages: Int? = null, val hasMore: Boolean? = null)

/** `GET /search/series`: here `pagination` is inside `data`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSearch(val data: HvSearchData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSearchData(val series: List<HvSeries>? = null, val pagination: HvPagination? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSeriesDetail(val data: HvSeries? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvVideoDetail(val data: HvVideo? = null)

/** `GET /series/related/{seriesId}`; the slug answers 404. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HvRelated(val data: HvRelatedData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvRelatedData(val relatedSeries: List<HvSeries>? = null)

/** List card and `GET /series/{slug}`; [videos], [studios] and [categories] only come with the detail. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSeries(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val image: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val videoCount: Int? = null,
    val studios: List<HvNamed>? = null,
    val categories: List<HvNamed>? = null,
    val videos: List<HvVideo>? = null,
)

/** Video card and `GET /videos/{slug}`; [mp4Path], [videoType], [subtitles] and [series] only come with the detail. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HvVideo(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val description: String? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val videoPath: String? = null,
    val mp4Path: String? = null,
    val videoType: String? = null,
    val createdAt: String? = null,
    val subtitles: List<HvSubtitle>? = null,
    val series: HvSeries? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvSubtitle(val language: String? = null, val filePath: String? = null, val isAIGenerated: Boolean? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HvNamed(val name: String? = null)
