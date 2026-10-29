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
