package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the AniList entry and episode of a TMDB anime episode, so the AniList-keyed sources can be asked too.
 * AniList is searched by title (series formats or movies only) and [OmniStreamParser.pickSeries] chooses by air date.
 */
internal object OmniStreamAnime {
    private const val ANILIST = "https://graphql.anilist.co"
    private const val TAG = "OmniStream"

    private val seriesFormats = listOf("TV", "TV_SHORT", "ONA")
    private val movieFormats = listOf("MOVIE")

    private const val QUERY = """query (${'$'}search: String, ${'$'}formats: [MediaFormat]) {
  Page(perPage: 30) {
    media(search: ${'$'}search, type: ANIME, format_in: ${'$'}formats, sort: SEARCH_MATCH) {
      id format episodes startDate { year month day } title { romaji english native } synonyms
    }
  }
}"""

    /** Regular episodes per TMDB show id, filled by `load` so playing an episode doesn't refetch them. */
    val showEpisodes = ConcurrentHashMap<Int, List<OmniStreamParser.AirEpisode>>()

    private val searches = ConcurrentHashMap<String, List<OmniStreamParser.AniCandidate>>()
    private val targets = ConcurrentHashMap<String, OmniStreamParser.AniTarget>()

    /**
     * Null when the title isn't on AniList under a matching name, or the episode can't be placed. [fetchEpisodes]
     * reloads the show's episodes when `load` didn't run in this session (e.g. resumed from history).
     */
    suspend fun target(
        link: OmniLink,
        fetchEpisodes: suspend (Int) -> List<OmniStreamParser.AirEpisode>,
    ): OmniStreamParser.AniTarget? {
        val key = "${link.type}:${link.id}:${link.season}:${link.episode}"
        targets[key]?.let { return it }
        val names = listOfNotNull(link.title, link.originalTitle).distinct()
        val target = if (link.type == "movie") {
            names.firstNotNullOfOrNull { name -> OmniStreamParser.pickMovie(link.airDate, search(name, movieFormats), names) }
        } else {
            val season = link.season ?: return null
            val episode = link.episode ?: return null
            val show = showEpisodes[link.id] ?: fetchEpisodes(link.id).also { if (it.isNotEmpty()) showEpisodes[link.id] = it }
            names.firstNotNullOfOrNull { name -> OmniStreamParser.pickSeries(show, season, episode, search(name, seriesFormats), names) }
        }
        Log.i(TAG, "AniList for ${link.title} ${link.season ?: ""}x${link.episode ?: ""}: " +
            (target?.let { "${it.anilistId} ep ${it.episode}" } ?: "no match"))
        return target?.also { targets[key] = it }
    }

    private suspend fun search(name: String, formats: List<String>): List<OmniStreamParser.AniCandidate> {
        val key = "${formats.first()}|$name"
        searches[key]?.let { return it }
        val candidates = try {
            val response = app.post(
                ANILIST,
                headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                json = AniListQuery(QUERY, mapOf("search" to name, "formats" to formats)),
                timeout = 10L,
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "AniList search: HTTP ${response.code}")
                return emptyList()
            }
            OmniStreamParser.candidates(response.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AniList search: ${e.message}")
            return emptyList()
        }
        return candidates.also { searches[key] = it }
    }
}
