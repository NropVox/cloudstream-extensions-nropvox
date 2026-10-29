package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty

/** Movie `data`: the server tokens read in `load`, so `loadLinks` needn't pass the Cloudflare check again. */
data class SupJavLinks(
    @JsonProperty("url") val url: String = "",
    @JsonProperty("servers") val servers: List<SupJavLinkServer> = emptyList(),
)

data class SupJavLinkServer(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("link") val link: String = "",
)
