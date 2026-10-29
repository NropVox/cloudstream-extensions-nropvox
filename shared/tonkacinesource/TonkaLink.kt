package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty

/** Movie or episode `data` of TonkaCine; what every player is keyed by. */
internal data class TonkaLink(
    @JsonProperty("id") val id: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)
