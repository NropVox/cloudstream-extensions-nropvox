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

    /** [malId] is 0 when unknown (Reanime reads it as "none"). */
    data class AniTarget(val anilistId: Int, val episode: Int, val malId: Int = 0)

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

    // --- AniList → TMDB (OmniAnime) ---

    /** What ani.zip knows of an AniList entry: its TMDB and IMDb ids (often missing for new seasons) and its episodes. */
    data class AniZip(val tmdbId: Int?, val imdbId: String?, val episodes: Map<Int, AniZipEpisode>)

    data class AniZipEpisode(val title: String?, val image: String?, val overview: String?, val airDate: String?, val runtime: Int?)

    private fun com.fasterxml.jackson.databind.JsonNode.text(): String? =
        takeIf { it.isTextual || it.isNumber }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    /** An `api.ani.zip/mappings` answer; specials (`S1`, …) are left out, like TMDB's season 0. */
    fun aniZip(json: String): AniZip? {
        val tree = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: return null
        val mappings = tree.get("mappings")
        val episodes = tree.get("episodes")?.fields()?.asSequence()?.mapNotNull { (key, node) ->
            val number = key.toIntOrNull() ?: return@mapNotNull null
            val titles = node.get("title")
            number to AniZipEpisode(
                // TVDB writes apostrophes as backticks ("The Journey`s End").
                title = (titles?.get("en")?.text() ?: titles?.get("x-jat")?.text())?.replace('`', '\''),
                image = node.get("image")?.text(),
                overview = node.get("overview")?.text() ?: node.get("summary")?.text(),
                airDate = node.get("airDate")?.text() ?: node.get("airdate")?.text(),
                runtime = node.get("runtime")?.takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 },
            )
        }?.toMap().orEmpty()
        return AniZip(
            mappings?.get("themoviedb_id")?.text()?.toIntOrNull(),
            mappings?.get("imdb_id")?.text()?.takeIf { it.startsWith("tt") },
            episodes,
        )
    }

    /**
     * The TMDB episode of AniList [episode] of an entry that premiered on [start], the inverse of [pickSeries]: TMDB
     * keeps cours AniList splits in one season, so it is the [episode]th regular TMDB episode aired since [start]. The
     * show must have aired something on [start] (else the id is another show), and when ani.zip dates the episode
     * ([airDate]) the pick must have aired then too; failing that, the one TMDB episode of that day. Otherwise null,
     * rather than a guess that would play the wrong episode.
     */
    fun tmdbEpisode(show: List<AirEpisode>, start: String?, episode: Int, airDate: String?): AirEpisode? {
        val since = start?.let(::epochDay) ?: return null
        val regular = show.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))
            .mapNotNull { other -> other.airDate?.let(::epochDay)?.let { other to it } }
        if (regular.none { (_, day) -> kotlin.math.abs(day - since) <= DATE_SLACK_DAYS }) return null
        val pick = regular.filter { (_, day) -> day >= since - DATE_SLACK_DAYS }.getOrNull(episode - 1)
        val aired = airDate?.let(::epochDay) ?: return pick?.first
        if (pick != null && kotlin.math.abs(pick.second - aired) <= DATE_SLACK_DAYS) return pick.first
        return regular.filter { (_, day) -> kotlin.math.abs(day - aired) <= DATE_SLACK_DAYS }.singleOrNull()?.first
    }

    /** A TMDB film is the AniList one when it came out within a year of it (festival vs. wide release). */
    fun sameRelease(start: String?, release: String?): Boolean {
        val a = start?.let(::epochDay) ?: return false
        val b = release?.let(::epochDay) ?: return false
        return kotlin.math.abs(a - b) <= 365
    }

    private val seasonSuffix = Regex(
        """(?i)[\s:,-]+(?:(?:season|part|cour)\s*\d+|\d+(?:st|nd|rd|th)\s+(?:season|part|cour)|(?:the\s+)?(?:second|third|fourth|fifth|final)\s+season|i{2,}|\d+)\s*$"""
    )

    /**
     * The show's name without the season AniList appends ("Sousou no Frieren 2nd Season", "… Final Season Part 2"),
     * which is what TMDB calls the whole show; what TMDB returns is checked by air date, so a loose cut is harmless.
     */
    fun baseTitles(titles: List<String>): List<String> = titles.map { title ->
        var base = title.trim()
        repeat(3) { base = seasonSuffix.replace(base, "").trim() }
        base
    }.filter { it.length >= 3 }.distinctBy(::normalize)

    /** `yyyy-MM-dd` from AniList's date parts, the day defaulting to the 1st; null without a month. */
    fun isoDate(year: Int?, month: Int?, day: Int?): String? {
        if (year == null || month == null) return null
        return "%04d-%02d-%02d".format(year, month, day ?: 1)
    }

    /**
     * Episodes out so far: up to the one before [nextAiring], else AniList's count, else the episodes ani.zip dates
     * on or before [today] (days since 1970). Nothing for an entry that hasn't started.
     */
    fun airedEpisodes(status: String?, episodes: Int?, nextAiring: Int?, aniZip: Map<Int, AniZipEpisode>, today: Int): Int = when {
        status == "NOT_YET_RELEASED" -> 0
        nextAiring != null -> nextAiring - 1
        episodes != null -> episodes
        else -> aniZip.filter { (_, episode) -> episode.airDate?.let(::epochDay)?.let { it <= today } == true }.keys.maxOrNull() ?: 0
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

    private val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""")

    /** Standard heights by the frame they name; a variant counts as the tallest whose width or height it nearly reaches. */
    private val heights = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360, 426 to 240)

    /**
     * The quality of a master playlist's best variant (`RESOLUTION=1920x800` is 1080p: cinema crops keep the width),
     * or null for a media playlist, which names no resolution.
     */
    fun playlistQuality(playlist: String): Int? = resolution.findAll(playlist).mapNotNull { match ->
        val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        heights.firstOrNull { (w, h) -> width >= w * 9 / 10 || height >= h * 9 / 10 }?.second ?: 144
    }.maxOrNull()

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

    /**
     * False for what can't have streams yet: a title TMDB gives no date at all, or an episode due after [today]
     * (days since 1970). Every source would answer with a namesake (an announced "Blossom" got the 2024 one's files).
     */
    fun aired(titleYear: Int?, airDate: String?, today: Int): Boolean {
        if (titleYear == null && airDate.isNullOrBlank()) return false
        val day = airDate?.let(::epochDay) ?: return true
        return day <= today + DATE_SLACK_DAYS
    }

    // --- Stremio addons ---

    /** An addon's id for a title: the IMDb id, with `:season:episode` for an episode. */
    fun stremioId(imdbId: String, season: Int?, episode: Int?): String =
        if (season != null && episode != null) "$imdbId:$season:$episode" else imdbId

    /** One direct stream of an addon. [label] is the file name and size; [height] is null when nothing names it. */
    data class StremioStream(val url: String, val label: String, val height: Int?, val headers: Map<String, String>)

    private val streamHeight = Regex("""(?i)\b(2160|1440|1080|720|576|480|360)p\b""")
    private val uhd = Regex("""(?i)\b(4k|uhd)\b""")
    private val fileSize = Regex("""(?i)\b\d+(?:\.\d+)?\s*[GM]B\b""")

    /**
     * The direct streams of a `/stream/...json` answer. Torrent-only entries (an `infoHash`, no `url`) can't be played
     * here and are left out. The title is the file name, sometimes followed by its size on the next line.
     */
    fun stremioStreams(json: String): List<StremioStream> {
        val streams = runCatching { mapper.readTree(json) }.getOrNull()?.get("streams") ?: return emptyList()
        return streams.mapNotNull { stream ->
            val url = stream.get("url")?.asText()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val name = stream.get("name")?.asText().orEmpty()
            val title = (stream.get("title") ?: stream.get("description"))?.asText().orEmpty()
            val hints = stream.get("behaviorHints")
            val file = hints?.get("filename")?.asText() ?: title.lineSequence().firstOrNull()?.trim().orEmpty()
            val size = fileSize.find(title)?.value
            val text = "$name $title"
            val height = streamHeight.find(text)?.groupValues?.get(1)?.toInt() ?: if (uhd.containsMatchIn(text)) 2160 else null
            val headers = hints?.get("proxyHeaders")?.get("request")?.fields()?.asSequence()
                ?.associate { (key, value) -> key to value.asText() }.orEmpty()
            StremioStream(url, listOf(file, size).filterNot { it.isNullOrBlank() }.joinToString(" · ").ifEmpty { name }, height, headers)
        }
    }

    data class StremioSubtitle(val lang: String, val url: String)

    /** The subtitles of a `/subtitles/...json` answer, at most [perLanguage] of each language, in the addon's order. */
    fun stremioSubtitles(json: String, perLanguage: Int): List<StremioSubtitle> {
        val subtitles = runCatching { mapper.readTree(json) }.getOrNull()?.get("subtitles") ?: return emptyList()
        val counts = HashMap<String, Int>()
        return subtitles.mapNotNull { subtitle ->
            val url = subtitle.get("url")?.asText()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val lang = subtitle.get("lang")?.asText()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val count = counts.getOrDefault(lang, 0)
            if (count >= perLanguage) return@mapNotNull null
            counts[lang] = count + 1
            StremioSubtitle(lang, url)
        }
    }

    /** OpenSubtitles' own codes for regional variants, which no ISO 639 table knows. */
    fun openSubtitlesLanguage(code: String): String? = when (code) {
        "pob" -> "Portuguese (Brazil)"
        "spl" -> "Spanish (Latin America)"
        "spn" -> "Spanish (Spain)"
        "zht" -> "Chinese (Traditional)"
        "zhe" -> "Chinese (Bilingual)"
        "mne" -> "Montenegrin"
        else -> null
    }

    // --- Site domains ---

    private val origin = Regex("""^https://[a-z0-9-]+(?:\.[a-z0-9-]+)+$""")

    /**
     * The site [key] names in a domains list (`{"vegamovies": "https://…", …}`, keys in any case), when it is a plain
     * https origin; anything else (a path, another scheme) is ignored so a bad entry can't redirect a source.
     */
    fun domain(json: String, key: String): String? {
        val tree = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val value = tree.fields().asSequence().firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.asText() ?: return null
        return value.trim().trimEnd('/').lowercase().takeIf { origin.matches(it) }
    }

    // --- Finish policy ---

    /** Until then the race holds out for an HD link; after it, any working link will do. */
    const val SOFT_MS = 6_000L

    /**
     * How much longer the link race should wait for stragglers, or null to keep waiting: [links] working links from
     * [sources] sources, the best of them [best] (a height; download-only links rank as Unknown, 400), [elapsed] ms
     * into the race. The player starts the best link, so with a 4K or 1080p one and a few backups from more than one
     * source (or plenty from one) waiting longer only costs time. Without an HD link the slower sources still get
     * until [SOFT_MS]; after that the first working link ends the wait.
     */
    fun grace(links: Int, sources: Int, best: Int, elapsed: Long): Long? = when {
        links == 0 -> null
        elapsed >= SOFT_MS -> 500L
        links < 4 || (sources < 2 && links < 8) -> null
        best >= 2160 -> 1_000L
        best >= 1080 -> 2_000L
        else -> null
    }
}
