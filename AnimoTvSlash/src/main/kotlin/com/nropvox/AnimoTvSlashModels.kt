package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty

/** Payload of `player.animotv.ru/#<base64>` (ANIMO-P). Only the fields the plugin reads. */
data class AnimoPayload(
    @JsonProperty("master") val master: String? = null,
    @JsonProperty("alt") val alt: List<String>? = null,
    @JsonProperty("subs") val subs: List<AnimoSub>? = null,
)

data class AnimoSub(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("lang") val lang: String? = null,
)

/** Payload of the `/plyr-player/`, `/jw-player/` (`url`) and `/vidstack-player/` (`url_<height>`) wrappers. */
data class WrapperPayload(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("url_480") val url480: String? = null,
    @JsonProperty("url_720") val url720: String? = null,
    @JsonProperty("url_1080") val url1080: String? = null,
)

/** Entry of `window.AAP_EPS` on a series page: real episode title and a still image. */
data class AapEpisode(
    @JsonProperty("t") val title: String? = null,
    @JsonProperty("i") val image: String? = null,
)
