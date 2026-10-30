package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** A grid card of the home, category and search pages. */
data class FkCard(val url: String, val title: String, val poster: String?, val year: Int?, val series: Boolean, val anime: Boolean)

/** One release of a movie or episode: its file name, size and redirector links (HubCloud first). */
data class FkFile(
    @JsonProperty("name") val name: String,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("urls") val urls: List<String>,
)

/** An episode of one quality block: `S02` and `Episode-01` become season 2, episode 1. */
data class FkEpisode(val season: Int, val episode: Int, val file: FkFile)

/** Episode or movie `data`: every release of it, resolved in parallel by `loadLinks`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FkLink(@JsonProperty("files") val files: List<FkFile> = emptyList())
