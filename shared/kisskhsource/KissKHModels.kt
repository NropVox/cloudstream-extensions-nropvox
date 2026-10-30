package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** A card from any `DramaList` list or search; `thumbnail` is an absolute (often landscape TMDB) URL. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KissCard(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("episodesCount") val episodesCount: Int? = null,
    @JsonProperty("label") val label: String? = null,
)

/** `DramaList/List`: the only paged endpoint, 10 per page, `page` is 1-based. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KissPage(
    @JsonProperty("data") val data: List<KissCard> = emptyList(),
    @JsonProperty("page") val page: Int = 1,
    @JsonProperty("pageSize") val pageSize: Int = 0,
    @JsonProperty("totalCount") val totalCount: Int = 0,
)

/** `DramaList/Drama/<id>`; `episodes` is sorted by `number`, newest first. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KissDrama(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("trailer") val trailer: String? = null,
    @JsonProperty("episodes") val episodes: List<KissEpisode> = emptyList(),
)

/** `sub` is the number of subtitle tracks; `number` is a float such as `24.0`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KissEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("number") val number: Double? = null,
    @JsonProperty("sub") val sub: Int? = null,
)

/** `DramaList/Episode/<id>.png`: `Type` 1 is HLS, 2 an iframe page; an error object has a non-null `id`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KissVideo(
    @JsonProperty("Video") val video: String? = null,
    @JsonProperty("ThirdParty") val thirdParty: String? = null,
    @JsonProperty("Type") val type: Int? = null,
)

/** One track from `Sub/<episodeId>`; how its cues are encrypted depends on the `src` extension. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KissSubtitle(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("land") val land: String? = null,
)
