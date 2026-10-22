package com.nropvox

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** `GET /ts-search.php` envelope: a Typesense proxy over every post (15 hits per page). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VgSearch(
    val found: Int? = null,
    val hits: List<VgHit>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VgHit(val document: VgDocument? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VgDocument(
    val permalink: String? = null,
    @JsonProperty("post_title") val title: String? = null,
    @JsonProperty("post_thumbnail") val poster: String? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    val category: List<String>? = null,
)

/** V-Drive `GET /api/file/<id>`: which mirror providers hold the file (`ready`, `pending`, `failed`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VdFile(val providers: List<VdProvider>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VdProvider(val provider: String? = null, val status: String? = null)

/** V-Drive `GET /download-link/<id>/skydrop`: `{"ready":false,"retry_after_ms":2500}` until the bridge has the file. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VdLink(
    @JsonProperty("download_url") val url: String? = null,
    @JsonProperty("retry_after_ms") val retryAfterMs: Long? = null,
)

/** Pixeldrain `GET /api/file/<id>/info`; taken-down files answer 451 on download. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PdInfo(@JsonProperty("can_download") val canDownload: Boolean? = null)

/** One grid card (home and category pages). */
data class VgCard(val url: String, val title: String, val poster: String?, val tag: String?, val rating: Double?)

/** A button under a quality heading of a detail page. [url] is a nexdrive page. */
data class VgButton(val label: String, val url: String)

/** A quality heading of a detail page with the buttons below it. */
data class VgGroup(val heading: String, val buttons: List<VgButton>) {
    /** `Season 4 - Part 02` is a different episode range than `Season 4 - Part 01`; both are season 4. */
    val part: Pair<Int, Int>? get() = VegamoviesParser.part(heading)
    val season: Int? get() = part?.first
}

/** One host link on a nexdrive page. [episode] is null on pages that are not split by episode. */
data class VgEntry(val episode: Int?, val label: String, val url: String)

/** A nexdrive page to read at play time, with the heading it was listed under. */
data class VgPage(val heading: String, val label: String, val url: String)

/** Episode (or movie, when [episode] is null) payload stored in the Cloudstream `data` string. */
data class VgLink(val pages: List<VgPage>, val episode: Int? = null)
