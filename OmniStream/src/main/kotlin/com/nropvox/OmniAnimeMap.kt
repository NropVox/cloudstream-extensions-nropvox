package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the TMDB title and episode of an OmniAnime (AniList) episode, so the TMDB-keyed sources can be asked too.
 * ani.zip names the TMDB id; new seasons often lack one, and then TMDB is searched by the show's base name. Either
 * way [OmniStreamParser.tmdbEpisode] (or [OmniStreamParser.sameRelease] for a film) checks the dates, so a wrong id
 * gives no match rather than another show's episode.
 */
internal object OmniAnimeMap {
    private const val TAG = "OmniAnime"
    private const val ANIZIP = "https://api.ani.zip/mappings?anilist_id="
    private const val ANIMATION = 16

    /** TMDB search hits tried per name; the right show is nearly always first. */
    private const val HITS_PER_NAME = 2
    private const val MAX_NAMES = 3
    private const val CACHE_SIZE = 300

    private val aniZips = ConcurrentHashMap<Int, OmniStreamParser.AniZip>()
    private val links = ConcurrentHashMap<String, OmniLink>()
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Started by `load`: maps the first episode, which fetches (and caches) the TMDB show and its seasons, so playing
     * any episode only has to place it.
     */
    fun warm(anime: OmniAnimeLink) {
        warmScope.launch { safely(TAG, "warm") { link(anime) } }
    }

    /** Null when ani.zip can't be read; an entry it doesn't know comes back empty. */
    suspend fun aniZip(anilistId: Int): OmniStreamParser.AniZip? {
        aniZips[anilistId]?.let { return it }
        val json = safely(TAG, "ani.zip") { app.get(ANIZIP + anilistId, timeout = 8L).takeIf { it.isSuccessful }?.text } ?: return null
        return OmniStreamParser.aniZip(json)?.also {
            if (aniZips.size > CACHE_SIZE) aniZips.clear()
            aniZips[anilistId] = it
        }
    }

    /** The TMDB title and (for a series) episode, or null when it can't be placed for sure. */
    suspend fun link(anime: OmniAnimeLink): OmniLink? {
        val key = "${anime.anilistId}:${anime.episode}"
        links[key]?.let { return it }
        val zip = aniZip(anime.anilistId)
        val link = if (anime.format == "MOVIE") movie(anime, zip) else series(anime, zip)
        Log.i(TAG, "TMDB for AniList ${anime.anilistId} ep ${anime.episode}: " +
            (link?.let { "${it.type}/${it.id} ${it.season ?: ""}x${it.episode ?: ""}" } ?: "no match"))
        return link?.also {
            if (links.size > CACHE_SIZE) links.clear()
            links[key] = it
        }
    }

    private suspend fun series(anime: OmniAnimeLink, zip: OmniStreamParser.AniZip?): OmniLink? {
        val airDate = zip?.episodes?.get(anime.episode)?.airDate
        zip?.tmdbId?.let { id -> return seriesOn(id, anime, airDate, zip.imdbId, found = false) }
        for (id in search("tv", anime.titles)) seriesOn(id, anime, airDate, null, found = true)?.let { return it }
        return null
    }

    /**
     * [found] by search: weekly shows of one season share air dates, so the name must match too (AniList's season
     * names start with TMDB's show name).
     */
    private suspend fun seriesOn(id: Int, anime: OmniAnimeLink, airDate: String?, imdbId: String?, found: Boolean): OmniLink? {
        val details = OmniTmdb.get("tv/$id?append_to_response=external_ids")?.let { tryParseJson<OmniTmdbDetails>(it) } ?: return null
        val title = details.name ?: return null
        if (found && !OmniStreamParser.titleMatches(anime.titles, listOfNotNull(title, details.originalName))) return null
        val show = OmniTmdb.airEpisodes(OmniTmdb.seasons(id, OmniTmdb.seasonNumbers(details)))
        val episode = OmniStreamParser.tmdbEpisode(show, anime.startDate, anime.episode, airDate) ?: return null
        return OmniLink(
            id = id,
            type = "tv",
            title = title,
            originalTitle = details.originalName?.takeIf { it != title },
            year = details.firstAirDate?.take(4)?.toIntOrNull(),
            imdbId = details.externalIds?.imdbId ?: imdbId,
            totalSeasons = details.numberOfSeasons,
            season = episode.season,
            episode = episode.episode,
            kind = OmniStreamParser.KIND_ANIME,
            airDate = episode.airDate,
        )
    }

    private suspend fun movie(anime: OmniAnimeLink, zip: OmniStreamParser.AniZip?): OmniLink? {
        zip?.tmdbId?.let { id -> return movieOn(id, anime, zip.imdbId, found = false) }
        for (id in search("movie", anime.titles)) movieOn(id, anime, null, found = true)?.let { return it }
        return null
    }

    private suspend fun movieOn(id: Int, anime: OmniAnimeLink, imdbId: String?, found: Boolean): OmniLink? {
        val details = OmniTmdb.get("movie/$id?append_to_response=external_ids")?.let { tryParseJson<OmniTmdbDetails>(it) } ?: return null
        val title = details.title ?: return null
        if (found && !OmniStreamParser.titleMatches(anime.titles, listOfNotNull(title, details.originalTitle))) return null
        if (!OmniStreamParser.sameRelease(anime.startDate, details.releaseDate)) return null
        return OmniLink(
            id = id,
            type = "movie",
            title = title,
            originalTitle = details.originalTitle?.takeIf { it != title },
            year = details.releaseDate?.take(4)?.toIntOrNull(),
            imdbId = details.imdbId ?: details.externalIds?.imdbId ?: imdbId,
            kind = OmniStreamParser.KIND_ANIME,
            airDate = details.releaseDate,
        )
    }

    /** Animated TMDB titles of [type] found under the entry's first few base names, best first. */
    private suspend fun search(type: String, titles: List<String>): List<Int> =
        OmniStreamParser.baseTitles(titles).take(MAX_NAMES).flatMap { name ->
            val query = URLEncoder.encode(name, "UTF-8")
            OmniTmdb.get("search/$type?query=$query")?.let { tryParseJson<OmniTmdbPage>(it) }?.results.orEmpty()
                .filter { it.genreIds.orEmpty().contains(ANIMATION) }
                .mapNotNull { it.id }
                .take(HITS_PER_NAME)
        }.distinct()
}
