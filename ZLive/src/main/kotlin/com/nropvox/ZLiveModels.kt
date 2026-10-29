package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** One entry of `GET iptv.zlive.st/channels.json`. `sport` is "" for about a quarter of them. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZChannel(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("tagline") val tagline: String? = null,
    @JsonProperty("region") val region: String? = null,
    @JsonProperty("flag") val flag: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("sport") val sport: String? = null,
    @JsonProperty("sources") val sources: List<ZSource> = emptyList(),
)

/** `key` goes to `/resolve`; `label` names the upstream (TimStreams, Primary, IPTV, trt-cdn). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZSource(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("label") val label: String? = null,
)

/** One entry of the sealed `POST iptv.zlive.st/streams`: today's live events. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZEvent(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("startTime") val startTime: String? = null,
    @JsonProperty("source") val source: ZSource? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ZTicket(@JsonProperty("w") val w: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ZResolved(@JsonProperty("location") val location: String? = null)

/** `GET iptv.zlive.st/epg/<id>.json`; 404 for channels without a guide. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ZEpg(@JsonProperty("channel") val channel: ZEpgChannel? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ZEpgChannel(
    @JsonProperty("now") val now: ZProgramme? = null,
    @JsonProperty("next") val next: ZProgramme? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ZProgramme(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("desc") val desc: String? = null,
    @JsonProperty("start") val start: String? = null,
    @JsonProperty("stop") val stop: String? = null,
)

/** The body every POST to iptv.zlive.st carries (see [ZLiveParser.seal]). */
data class ZSealed(
    @JsonProperty("a") val a: String,
    @JsonProperty("b") val b: String,
    @JsonProperty("c") val c: String,
    @JsonProperty("d") val d: String,
    @JsonProperty("e") val e: String,
)
