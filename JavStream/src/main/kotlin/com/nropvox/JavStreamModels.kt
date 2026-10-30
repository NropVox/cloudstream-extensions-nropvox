package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty

/** Movie `data`: the SexTB page the title was opened from and its code, which the other sites are searched by. */
data class JavStreamLinks(
    @JsonProperty("url") val url: String = "",
    @JsonProperty("code") val code: String = "",
)

/** JavHD's `/ajax` answer for one server: the player HTML (its own jwplayer, or a host iframe). */
data class JavStreamHdPlayer(
    @JsonProperty("player") val player: String? = null,
)
