package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Pure logic for OmniStream: title kinds, the TMDB → AniList episode mapping, matching TMDB titles to site search
 * hits, playlist checks and the finish policy.
 */
object OmniStreamParser {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    const val KIND_GENERAL = "general"
    const val KIND_ANIME = "anime"
    const val KIND_ASIAN = "asian"

    private const val ANIMATION = 16
    private val asianCountries = setOf("KR", "CN", "TW", "HK", "JP", "TH")
    private val asianLanguages = setOf("ko", "zh", "cn", "ja", "th")

    /**
     * Anime is Japanese animation (genre 16 from JP or in Japanese). Asian is live action from East/Southeast Asia,
     * which KatDrama covers. List results of movies carry no origin country, so the language decides there.
     */
    fun kind(genreIds: List<Int>?, originCountry: List<String>?, originalLanguage: String?): String {
        val countries = originCountry.orEmpty()
        val japanese = "JP" in countries || originalLanguage == "ja"
        val animated = genreIds.orEmpty().contains(ANIMATION)
        return when {
            animated && japanese -> KIND_ANIME
            animated -> KIND_GENERAL
            countries.any { it in asianCountries } || originalLanguage in asianLanguages -> KIND_ASIAN
            else -> KIND_GENERAL
        }
    }

    /** `(type, tmdbId)` from a title URL such as `https://www.themoviedb.org/tv/1396`. */
    fun titleRef(url: String): Pair<String, Int>? =
        Regex("""/(movie|tv)/(\d+)""").find(url)?.destructured?.let { (type, id) -> id.toIntOrNull()?.let { type to it } }

    // --- TMDB → AniList ---

    /** One regular TMDB episode with its first air date (`yyyy-MM-dd`). */
    data class AirEpisode(val season: Int, val episode: Int, val airDate: String?)

    data class AniCandidate(val id: Int, val format: String?, val episodes: Int?, val startDay: Int?, val titles: List<String>)

    data class AniTarget(val anilistId: Int, val episode: Int)

    private val seriesFormats = setOf("TV", "TV_SHORT", "ONA")

    /** TMDB and AniList dates can differ by a day (time zones). Wider would let spin-off minis through. */
    private const val DATE_SLACK_DAYS = 1

    fun candidates(json: String): List<AniCandidate> =
        runCatching { mapper.readValue<AniListResponse>(json) }.getOrNull()?.data?.page?.media.orEmpty().mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val start = media.startDate?.let { date ->
                val year = date.year ?: return@let null
                val month = date.month ?: return@let null
                epochDay(year, month, date.day ?: 1)
            }
            val titles = listOfNotNull(media.title?.english, media.title?.romaji, media.title?.native) + media.synonyms.orEmpty()
            AniCandidate(id, media.format, media.episodes, start, titles)
        }

    /**
     * The AniList entry and episode for TMDB `season`×`episode`. TMDB often keeps a whole show in one season while
     * AniList splits it per cour (JJK is one 59-episode TMDB season, three AniList entries), so the entry is the
     * newest matching series that had started when this episode aired, and the episode is its ordinal since then.
     * An entry only counts if it premiered the day one of the show's episodes aired: spin-off minis named after the
     * show (Frieren's chibi ONAs) start on other days. Null when nothing fits, rather than a guess that would play
     * the wrong episode.
     */
    fun pickSeries(show: List<AirEpisode>, season: Int, episode: Int, candidates: List<AniCandidate>, names: List<String>): AniTarget? {
        val aired = show.firstOrNull { it.season == season && it.episode == episode }?.airDate?.let(::epochDay) ?: return null
        val airDays = show.filter { it.season > 0 }.mapNotNull { it.airDate?.let(::epochDay) }
        val chosen = candidates
            .filter { it.format in seriesFormats && it.startDay != null && it.startDay <= aired + DATE_SLACK_DAYS }
            .filter { candidate -> airDays.any { kotlin.math.abs(it - candidate.startDay!!) <= DATE_SLACK_DAYS } }
            .filter { titleMatches(it.titles, names) }
            .maxWithOrNull(compareBy<AniCandidate> { it.startDay!! }.thenBy { if (it.format == "TV") 1 else 0 }) ?: return null
        val since = chosen.startDay!! - DATE_SLACK_DAYS
        val ordinal = show.count { other ->
            val day = other.airDate?.let(::epochDay) ?: return@count false
            other.season > 0 && day >= since && (other.season < season || (other.season == season && other.episode <= episode))
        }
        if (ordinal < 1 || (chosen.episodes != null && ordinal > chosen.episodes)) return null
        return AniTarget(chosen.id, ordinal)
    }

    /** A film: the matching AniList movie released closest to TMDB's date, within a year. */
    fun pickMovie(releaseDate: String?, candidates: List<AniCandidate>, names: List<String>): AniTarget? {
        val released = releaseDate?.let(::epochDay) ?: return null
        return candidates
            .filter { it.format == "MOVIE" && it.startDay != null && kotlin.math.abs(it.startDay - released) <= 365 }
            .filter { titleMatches(it.titles, names) }
            .minByOrNull { kotlin.math.abs(it.startDay!! - released) }
            ?.let { AniTarget(it.id, 1) }
    }

    /** Season entries are named after the show ("JUJUTSU KAISEN Season 2"), so one title must start with the other. */
    fun titleMatches(titles: List<String>, names: List<String>): Boolean {
        val wanted = names.map(::normalize).filter { it.length >= 3 }
        return titles.map(::normalize).filter { it.length >= 3 }.any { title ->
            wanted.any { title.startsWith(it) || it.startsWith(title) }
        }
    }

    private fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    /** Days since 1970-01-01 for `yyyy-MM-dd`; java.time is not available on every supported Android. */
    fun epochDay(date: String): Int? {
        val parts = date.trim().split('-')
        if (parts.size < 3) return null
        return epochDay(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null,
            parts[2].take(2).toIntOrNull() ?: return null)
    }

    /** Howard Hinnant's days_from_civil. */
    private fun epochDay(year: Int, month: Int, day: Int): Int {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    // --- Playlists ---

    private val keyMethod = Regex("""^#EXT-X-(?:SESSION-)?KEY:.*?\bMETHOD=([A-Z0-9-]+)""", RegexOption.MULTILINE)

    fun isPlaylist(body: String): Boolean = body.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    /**
     * True when an HLS playlist is encrypted with a DRM key system (Widevine/PlayReady/FairPlay `SAMPLE-AES*`), which
     * the app's player rejects with UnsupportedDrmException. Plain AES-128 plays fine.
     */
    fun isDrm(playlist: String): Boolean =
        keyMethod.findAll(playlist).any { it.groupValues[1] != "NONE" && it.groupValues[1] != "AES-128" }

    /** The first variant of a master playlist, made absolute against [playlistUrl]; null for a media playlist. */
    fun firstVariant(playlist: String, playlistUrl: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val inf = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }.takeIf { it >= 0 } ?: return null
        val uri = lines.drop(inf + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return runCatching { java.net.URI(playlistUrl).resolve(uri).toString() }.getOrNull()
    }

    // --- Title-searched sites ---

    /** A site's search hit, reduced to what matching needs. [label] is the raw post title, read for season mentions. */
    data class SiteHit<T>(
        val item: T,
        val title: String,
        val year: Int?,
        val imdbId: String? = null,
        val series: Boolean? = null,
        val label: String = title,
    )

    /**
     * The hits that are this title, best first. The IMDb id decides when both sides have one; otherwise the name must
     * be one of [names] and the year within one of [years] (a hit or title without a year passes). A hit that says
     * whether it is a series must agree. For a [season], posts that name only other seasons are dropped and posts
     * naming it come first.
     */
    fun <T> matches(hits: List<SiteHit<T>>, names: List<String>, years: List<Int>, imdbId: String?, series: Boolean, season: Int?): List<T> =
        hits.filter { hit ->
            if (hit.series != null && hit.series != series) return@filter false
            if (!imdbId.isNullOrBlank() && !hit.imdbId.isNullOrBlank()) hit.imdbId.equals(imdbId, ignoreCase = true)
            else sameTitle(hit.title, names) && yearMatches(hit.year, years)
        }.map { it to (season?.let { s -> seasonRank(it.label, s) } ?: 0) }
            .filter { it.second < 2 }
            .sortedBy { it.second }
            .map { it.first.item }

    fun sameTitle(candidate: String, names: List<String>): Boolean {
        val wanted = normalizeTitle(candidate)
        return wanted.isNotEmpty() && names.any { normalizeTitle(it) == wanted }
    }

    /** Sites date a title by another country's release or by its first season, so a year either way still matches. */
    fun yearMatches(year: Int?, years: List<Int>): Boolean =
        year == null || years.isEmpty() || years.any { kotlin.math.abs(it - year) <= 1 }

    /** Letters and digits only, accents dropped and `&` read as "and", so site spellings of one name compare equal. */
    fun normalizeTitle(value: String): String =
        java.text.Normalizer.normalize(value.replace("&", " and "), java.text.Normalizer.Form.NFD)
            .lowercase().filter { it.isLetterOrDigit() }

    /** Names a site may give a season: season 1 often has no number, later ones add "Season N" or just N. */
    fun seasonNames(names: List<String>, season: Int?): List<String> = when (season) {
        null -> names
        1 -> names + names.map { "$it Season 1" }
        else -> names.flatMap { listOf("$it Season $season", "$it $season") }
    }

    private val seasonRange = Regex("""\b(?:Seasons?|S)\s*0*(\d{1,2})\s*(?:[-–&]|to)\s*(?:Season\s*|S)?0*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val seasonSingle = Regex("""\b(?:Season\s*|S)0*(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE)

    /** 0: [label] names [season] (alone or in a range), 1: it names no season, 2: it names only other seasons. */
    fun seasonRank(label: String, season: Int): Int {
        val ranges = seasonRange.findAll(label).map { it.groupValues[1].toInt()..it.groupValues[2].toInt() }.toList()
        if (ranges.any { season in it }) return 0
        val singles = seasonSingle.findAll(seasonRange.replace(label, " ")).map { it.groupValues[1].toInt() }.toList()
        if (season in singles) return 0
        return if (ranges.isEmpty() && singles.isEmpty()) 1 else 2
    }

    private val trailingYear = Regex("""\s*\((\d{4})\)\s*$""")

    /** `Poisonous Love (2025)` → (`Poisonous Love`, 2025): KissKH tells remakes apart by a year after the name. */
    fun splitYear(title: String): Pair<String, Int?> =
        trailingYear.find(title)?.let { title.substring(0, it.range.first).trim() to it.groupValues[1].toInt() } ?: (title.trim() to null)

    // --- Finish policy ---

    /** Enough to stop waiting (after a short grace): a few working links from more than one source, or plenty from one. */
    fun enough(goodLinks: Int, goodSources: Int): Boolean =
        (goodLinks >= 4 && goodSources >= 2) || goodLinks >= 8
}
