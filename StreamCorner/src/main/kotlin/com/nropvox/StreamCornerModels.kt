package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One entry of a `provider-list` reply, or the whole `provider-detail` reply. Providers fill different subsets:
 * events carry `stream_id` + `event_name`, NBA games `game_id` + teams + `live`/`vods`. Number-ish fields are
 * strings because the same key is an int on one provider and a string or null on another.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SCItem(
    @JsonProperty("stream_id") val streamId: String? = null,
    @JsonProperty("game_id") val gameId: String? = null,
    @JsonProperty("event_name") val eventName: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("category_logo") val categoryLogo: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("home_team") val homeTeam: String? = null,
    @JsonProperty("away_team") val awayTeam: String? = null,
    @JsonProperty("home_team_logo") val homeTeamLogo: String? = null,
    @JsonProperty("home_logo") val homeLogo: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("hero_thumbnail") val heroThumbnail: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("timestamp") val timestamp: String? = null,
    @JsonProperty("start_time") val startTime: String? = null,
    @JsonProperty("end_time") val endTime: String? = null,
    @JsonProperty("time_utc") val timeUtc: String? = null,
    @JsonProperty("time_et") val timeEt: String? = null,
    /** NBA only: "Final", "Q3 5:12", or a tip-off time. */
    @JsonProperty("time") val time: String? = null,
    @JsonProperty("home_score") val homeScore: String? = null,
    @JsonProperty("away_score") val awayScore: String? = null,
    @JsonProperty("streams") val streams: List<SCStream> = emptyList(),
    @JsonProperty("live") val live: List<SCStream> = emptyList(),
    @JsonProperty("vods") val vods: List<SCStream> = emptyList(),
)

/** A player choice. Only `embed_url` has been seen; the site falls back to the other three. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SCStream(
    @JsonProperty("source_name") val sourceName: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
    @JsonProperty("manifest_url") val manifestUrl: String? = null,
    @JsonProperty("url") val url: String? = null,
)

/** What the site's data module knows at runtime: the worker script and the data hosts it rotates through. */
data class SCBootstrap(val workerPath: String, val hosts: List<String>)
