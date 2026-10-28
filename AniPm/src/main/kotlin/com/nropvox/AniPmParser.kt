package com.nropvox

import java.net.URLDecoder

/** Which ani.pm backend a title comes from, and its id there. */
data class ApRef(val source: String, val id: String)

object AniPmParser {
    const val SETTLAR = "settlar"
    const val ANILIST = "anilist"

    private val sourceParam = Regex("""[?&]source=(settlar|anilist)(?:&|$)""")
    private val idParam = Regex("""[?&]id=(\d+)(?:&|$)""")
    private val routeId = Regex("""/anime/[^/?#]*?-(\d+)(?:[/?#]|$)""")
    private val tokenParam = Regex("""[?&]t=([^&#]+)""")
    private val number = Regex("""\d+""")

    /** Card ids for anilist titles are sometimes the AniList id plus this offset. */
    private const val ANILIST_ID_OFFSET = 1_000_000L

    fun ref(card: ApCard): ApRef? = when (card.source) {
        SETTLAR -> card.id?.let { ApRef(SETTLAR, it.toString()) }
        ANILIST -> (card.anilistId?.takeIf { it.isNotBlank() }
            ?: card.id?.let { if (it > ANILIST_ID_OFFSET) it - ANILIST_ID_OFFSET else it }?.toString())
            ?.let { ApRef(ANILIST, it) }
        else -> null
    }

    /** Page URL handed to the app; the site's own `/anime/<routeId>` plus where to load it from. */
    fun titleUrl(mainUrl: String, routeId: String?, ref: ApRef): String {
        val slug = routeId?.takeIf { it.isNotBlank() } ?: ref.id
        return "$mainUrl/anime/$slug?source=${ref.source}&id=${ref.id}"
    }

    /**
     * Reads a ref back from [titleUrl]. A plain site URL has no source; its trailing number is
     * returned with an empty source so the caller can try both backends.
     */
    fun parseRef(url: String): ApRef? {
        val id = idParam.find(url)?.groupValues?.get(1)
        val source = sourceParam.find(url)?.groupValues?.get(1)
        if (id != null && source != null) return ApRef(source, id)
        return routeId.find(url)?.groupValues?.get(1)?.let { ApRef("", it) }
    }

    fun detailsPath(ref: ApRef) =
        if (ref.source == ANILIST) "anime/ani/${ref.id}" else "anime/series/${ref.id}"

    fun bootstrapPath(data: ApLinkData) =
        "anime/playback-bootstrap/${data.source}/${data.id}?ep=${data.ep}&lang=${data.lang}"

    /** The `t` token of an `embed.settlar.io/embed/v1?t=…` URL, still URL-encoded. */
    fun embedToken(embedUrl: String): String? = tokenParam.find(embedUrl)?.groupValues?.get(1)

    /** Covers, stills and logos come as site-relative paths. */
    fun absolute(mainUrl: String, path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        path.startsWith("/") -> mainUrl + path
        else -> null
    }

    /** Durations come as `24`, `"24"` or `"24 min"`. */
    fun minutes(value: String?): Int? = value?.let { number.find(it)?.value?.toIntOrNull() }

    /** `type` is AniList's format with varying case: TV, Movie/MOVIE, OVA, ONA, Special, TV Short. */
    fun isMovie(type: String?) = type.equals("movie", ignoreCase = true)

    fun isOva(type: String?) = type?.lowercase() in setOf("ova", "ona", "special")

    /** Settlar says "Currently Airing" / "Finished", AniList says RELEASING / FINISHED. */
    fun isOngoing(status: String?): Boolean? = when (status?.lowercase()) {
        "currently airing", "releasing", "not yet aired", "not_yet_released", "hiatus" -> true
        "finished", "finished airing", "cancelled" -> false
        else -> null
    }

    /** Link name; says so when the site fell back to another audio than the one asked for. */
    fun linkLabel(requested: String, effective: String?): String {
        val audio = effective ?: requested
        val label = if (audio == "dub") "Dub" else "Sub"
        return if (audio == requested) label else "$label (no ${if (requested == "dub") "dub" else "sub"})"
    }

    fun decode(token: String): String = URLDecoder.decode(token, "UTF-8")
}
