package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB for OmniStream's catalog and OmniAnime's AniList → TMDB mapping: the public API with a site key; when that
 * key is refused (or the API is unreachable), the keyless TMDB mirror Movy uses. Answers are kept for [TTL_MS], so
 * reopening a title or playing its episodes doesn't ask again.
 */
internal object OmniTmdb {
    private const val TAG = "OmniStream"
    private const val API = "https://api.themoviedb.org/3"
    private const val MIRROR = "https://db.wecollege.net/3"

    /** dulo.mov's TMDB key, from its JS bundle (the site calls TMDB from the browser with it). */
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"

    @Volatile
    private var keyRefused = false

    private const val TTL_MS = 30 * 60 * 1000L
    private const val CACHE_SIZE = 100

    /** TMDB answers by query, with when they were fetched. */
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Null for a missing title. */
    suspend fun get(path: String): String? {
        val separator = if ('?' in path) '&' else '?'
        val query = "$path${separator}language=en-US&include_adult=false"
        val now = System.currentTimeMillis()
        cache[query]?.let { (at, body) -> if (now - at < TTL_MS) return body }
        return fetch(query)?.also {
            if (cache.size >= CACHE_SIZE) cache.clear()
            cache[query] = now to it
        }
    }

    private suspend fun fetch(query: String): String? {
        if (!keyRefused) {
            try {
                val response = app.get("$API/$query&api_key=$KEY", timeout = 20L)
                if (response.isSuccessful) return response.text
                if (response.code != 401 && response.code != 403 && response.code != 429) return null
                if (response.code != 429) keyRefused = true
                Log.w(TAG, "TMDB key: HTTP ${response.code}, using the mirror")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TMDB: ${e.message}, trying the mirror")
            }
        }
        return try {
            app.get("$MIRROR/$query", timeout = 20L).takeIf { it.isSuccessful }?.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TMDB mirror: ${e.message}")
            null
        }
    }

    /** Regular season numbers: specials (season 0) are skipped, the sources only index regular seasons. */
    fun seasonNumbers(details: OmniTmdbDetails) = details.seasons.orEmpty().mapNotNull { it.seasonNumber?.takeIf { n -> n > 0 } }

    /** Episodes of the given seasons, in season order; TMDB allows at most 20 appended sub-requests per call. */
    suspend fun seasons(id: Int, numbers: List<Int>): List<Pair<Int, List<OmniTmdbEpisode>>> =
        numbers.chunked(20).amap { chunk ->
            val appended = chunk.joinToString(",") { "season/$it" }
            val seasons = try {
                get("tv/$id?append_to_response=$appended")?.let { mapper.readTree(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@amap emptyList()
            chunk.map { number ->
                val season = seasons.get("season/$number")?.let { mapper.treeToValue(it, OmniTmdbSeason::class.java) }
                number to season?.episodes.orEmpty()
            }
        }.flatten()

    fun airEpisodes(seasons: List<Pair<Int, List<OmniTmdbEpisode>>>) = seasons.flatMap { (number, episodes) ->
        episodes.mapNotNull { episode -> episode.episodeNumber?.let { OmniStreamParser.AirEpisode(number, it, episode.airDate) } }
    }

    /** Every regular episode of a show with its air date; empty when TMDB can't be read. */
    suspend fun episodesOf(id: Int): List<OmniStreamParser.AirEpisode> {
        val details = get("tv/$id")?.let { tryParseJson<OmniTmdbDetails>(it) } ?: return emptyList()
        return airEpisodes(seasons(id, seasonNumbers(details)))
    }
}
