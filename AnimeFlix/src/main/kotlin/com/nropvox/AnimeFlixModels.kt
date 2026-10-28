package com.nropvox

/** A post card of the home grid, category and search pages. */
data class AfCard(val url: String, val title: String, val poster: String?, val movie: Boolean)

/** One row of the `?af_suggest=` JSON: title, post URL, image, meta line (`Season 1 · 2007 · SUB`). */
data class AfSuggest(val t: String? = null, val u: String? = null, val i: String? = null, val m: String? = null)

data class AfDetails(
    val title: String?,
    val poster: String?,
    val plot: String?,
    val genres: List<String>,
    val rating: Double?,
    /** The `dl.info` sidebar: `Status` → `Ongoing`, `Released` → `2026`, `Season` → `1-2`, … */
    val info: Map<String, String>,
)

/** A download card: its quality (`720p`), the line under it (`10bit · 180MB per episode`) and its episode list page. */
data class AfQuality(val quality: String, val sub: String, val url: String)

data class AfSeason(val season: Int, val qualities: List<AfQuality>)

/** A link of an episode list page: `Episode 4` (with its number) or, on movies, `Link for – 1036p`. */
data class AfEntry(val label: String, val episode: Int?, val url: String)

/** A quality's episode list page, with the card text that names the link. */
data class AfSource(val label: String, val url: String)

/** Movie: its quality pages. Episode: every quality page of its season and the [episode] number to pick from each. */
data class AfLink(val sources: List<AfSource>, val episode: Int? = null)
