package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** WP REST `wp/v2/posts` item. `content` is left out of list requests with `_fields`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class XphPost(
    val id: Int? = null,
    val link: String? = null,
    val title: XphRendered? = null,
    val content: XphRendered? = null,
    @JsonProperty("_embedded") val embedded: XphEmbedded? = null,
) {
    val poster: String? get() = embedded?.media?.firstOrNull()?.sourceUrl?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class XphRendered(val rendered: String? = null)

/** `_embed=wp:featuredmedia`: the post's poster. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class XphEmbedded(@JsonProperty("wp:featuredmedia") val media: List<XphMedia>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class XphMedia(@JsonProperty("source_url") val sourceUrl: String? = null)

/** A download heading of a post with the nexdrive buttons below it. */
data class XphGroup(val heading: String, val buttons: List<XphPage>)

/** A nexdrive page to read at play time. [label] is the button text (`720p [1GB]` or `Download Now`). */
data class XphPage(val heading: String, val label: String, val url: String)

/** Episode (or movie) payload stored in the Cloudstream `data` string. */
data class XphLink(val pages: List<XphPage>)
