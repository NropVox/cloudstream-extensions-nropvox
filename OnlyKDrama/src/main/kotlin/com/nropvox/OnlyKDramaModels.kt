package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** A card of the archive and search pages. */
data class OkCard(val url: String, val title: String, val poster: String?, val year: Int?, val movie: Boolean)

/** One file-host link of the post body: the file name it was labelled with, its size and the host page. */
data class OkFile(
    @JsonProperty("name") val name: String,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("url") val url: String,
)

/** A link with the episode it belongs to; both are null for movie files. */
data class OkEntry(val season: Int?, val episode: Int?, val file: OkFile)

/** Episode or movie `data`: every resolvable link of it, resolved in parallel by `loadLinks`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OkLinks(@JsonProperty("files") val files: List<OkFile> = emptyList())
