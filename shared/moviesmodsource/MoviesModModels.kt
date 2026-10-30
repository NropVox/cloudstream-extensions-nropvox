package com.nropvox

/** A post card of the home, category and search pages. */
data class MmCard(val url: String, val title: String, val poster: String?)

data class MmButton(val label: String, val url: String)

/** A download heading (`720p x264 [890MB]`, `Season 2 … 1080p`) and the buttons under it. */
data class MmGroup(val heading: String, val buttons: List<MmButton>)

data class MmDetails(
    val title: String?,
    val year: Int?,
    val poster: String?,
    val plot: String?,
    val genres: List<String>,
    val rating: Double?,
    val actors: List<String>,
    val imdbId: String?,
)

data class MmForm(val action: String, val fields: Map<String, String>)

/** A quality of a movie or season: [url] is its modpro links page (movie) or episode list page (series). */
data class MmSource(val heading: String, val url: String)

/** Movie: its quality pages. Episode: every quality page of its season and the [episode] number to pick from each. */
data class MmLink(val sources: List<MmSource>, val episode: Int? = null)
