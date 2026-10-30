package com.nropvox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Calendar

/**
 * AnimeX (animex.one) is a SvelteKit site over two open APIs: GraphQL for catalog, search and details,
 * and the `pp` REST API for episodes and streams. Neither needs auth, cookies or tokens.
 * Titles are keyed by AniList ID in page URLs, but `pp` only accepts the GraphQL slug. The stream calls are
 * [AnimeXSource] in `shared/animexsource` (shared with OmniStream).
 */
class AnimeX : MainAPI() {
    override var mainUrl = "https://animex.one"
    override var name = "AnimeX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val graphqlUrl = AnimeXSource.GRAPHQL
    private val recentUrl = "https://graphql.animex.one/api/recent"
    private val ppApi = "https://pp.animex.one/rest/api"

    private val browserHeaders = AnimeXSource.browserHeaders

    // Site URLs end in the AniList ID: /anime/{slug}-{id} and /watch/{slug}-{id}-episode-{n}.
    private val anilistIdPattern = Regex("""-(\d+)(?:-episode-\d+)?$""")

    override val mainPage = mainPageOf(
        "recent" to "Latest Episodes",
        "trending" to "Trending Now",
        "season" to "Popular This Season",
        "popular" to "All-Time Popular",
        "top" to "Top Rated",
        "movies" to "Popular Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "recent") {
            val recent = fetch<RecentPage>("$recentUrl?page=$page")
                ?: throw ErrorLoadingException("Could not load ${request.name}")
            return newHomePageResponse(
                request.name,
                recent.results.orEmpty().mapNotNull { it.toSearchResponse() },
                hasNext = recent.hasNextPage == true
            )
        }

        // Upcoming titles have nothing to play yet.
        val filter = mutableMapOf<String, Any>("statusIn" to listOf("RELEASING", "FINISHED", "HIATUS"))
        val sort = when (request.data) {
            "season" -> {
                val (season, year) = currentSeason()
                filter["seasonIn"] = listOf(season)
                filter["seasonYearMin"] = year
                filter["seasonYearMax"] = year
                "TRENDING"
            }
            "popular" -> "POPULARITY"
            "top" -> "AVERAGE_SCORE"
            "movies" -> {
                filter["formatIn"] = listOf("MOVIE")
                "POPULARITY"
            }
            else -> "TRENDING"
        }

        val result = catalog(filter, sort, PAGE_SIZE, (page - 1) * PAGE_SIZE)
            ?: throw ErrorLoadingException("Could not load ${request.name}")
        return newHomePageResponse(
            request.name,
            result.items.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = result.hasNextPage == true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val variables = mapOf("query" to query.trim(), "limit" to PAGE_SIZE, "offset" to (page - 1) * PAGE_SIZE)
        val result = graphql<SearchData>(SEARCH_QUERY, variables)?.searchAnime
        return newSearchResponseList(
            result?.items.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = result?.hasNextPage == true
        )
    }

    // The site's header autocomplete uses the catalog's text filter rather than searchAnime.
    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        catalog(mapOf("query" to query.trim(), "includeAdult" to false), null, QUICK_SEARCH_SIZE, 0)
            ?.items?.mapNotNull { it.toSearchResponse() }

    private suspend fun catalog(filter: Map<String, Any>, sort: String?, limit: Int, offset: Int): Connection? {
        val variables = mapOf(
            "filter" to filter,
            "sort" to sort?.let { listOf(mapOf("field" to it, "direction" to "DESC")) },
            "limit" to limit,
            "offset" to offset,
        )
        return graphql<CatalogData>(CATALOG_QUERY, variables)?.catalogAnime
    }

    private fun Card.toSearchResponse(): SearchResponse? {
        val anilistId = anilistId ?: return null
        val title = displayTitle(titleEnglish, titleRomaji) ?: return null
        val card = this
        return newAnimeSearchResponse(title, detailUrl(id, anilistId), tvType(format)) {
            posterUrl = card.coverImage?.best()
            year = card.seasonYear
            score = Score.from100(card.averageScore)
            val subs = card.subCount ?: 0
            val dubs = card.dubCount ?: 0
            addDubStatus(dubs > 0, subs > 0, dubs.takeIf { it > 0 }, subs.takeIf { it > 0 })
        }
    }

    private fun RecentItem.toSearchResponse(): SearchResponse? {
        val anilistId = anilistId ?: return null
        val title = displayTitle(titleEnglish, titleRomaji) ?: return null
        val item = this
        return newAnimeSearchResponse(title, detailUrl(id, anilistId), tvType(format)) {
            posterUrl = item.coverImage?.best()
            val dub = item.isDub == true
            val sub = item.isSub != false
            addDubStatus(dub, sub, item.episode.takeIf { dub }, item.episode.takeIf { sub })
        }
    }

    private fun LinkedAnime.toSearchResponse(): SearchResponse? {
        val anilistId = anilistId ?: return null
        val title = title ?: return null
        val linked = this
        return newAnimeSearchResponse(title, detailUrl(animeId, anilistId), tvType(format)) {
            posterUrl = linked.coverImage?.best() ?: linked.image
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = url.substringBefore('?').trimEnd('/')
        val anilistId = anilistIdPattern.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val anime = graphql<AnimeData>(DETAILS_QUERY, mapOf("anilistId" to anilistId))?.anime
            ?: throw ErrorLoadingException("Could not load AnimeX title $anilistId")
        val slug = anime.id ?: throw ErrorLoadingException("AnimeX title $anilistId has no slug")
        val title = displayTitle(anime.titleEnglish, anime.titleRomaji) ?: return null

        // Episodes are numbered absolutely within each AniList entry; sub and dub share one list.
        val episodes = fetch<List<EpisodeInfo>>("$ppApi/episodes?id=${encode(slug)}").orEmpty()
        fun episodesFor(lang: String, available: (EpisodeInfo) -> Boolean) =
            episodes.filter(available).mapNotNull { info ->
                val number = info.number ?: return@mapNotNull null
                newEpisode(LinkData(slug, number, lang).toJson()) {
                    episode = number
                    name = info.titles?.let { displayTitle(it.en, it.romaji) }
                    description = info.description
                    posterUrl = info.img
                    runTime = info.length
                    score = Score.from10(info.rating)
                    addDate(info.airDateUtc?.take(10))
                }
            }
        val subbed = episodesFor("sub") { it.hasSub != false }
        val dubbed = episodesFor("dub") { it.hasDub == true }

        return newAnimeLoadResponse(title, detailUrl(slug, anilistId), tvType(anime.format)) {
            engName = anime.titleEnglish
            japName = anime.titleRomaji
            synonyms = anime.synonyms.orEmpty()
            posterUrl = anime.coverImage?.best()
            backgroundPosterUrl = anime.backdropUrl ?: anime.bannerImage
            logoUrl = anime.logos?.let { logos -> logos.firstOrNull { it.iso639_1 == "en" } ?: logos.firstOrNull() }?.src
            plot = anime.description
            year = anime.seasonYear ?: anime.startDateYear
            tags = anime.genres
            duration = anime.duration
            score = Score.from100(anime.averageScore)
            showStatus = when (anime.status) {
                "RELEASING", "HIATUS" -> ShowStatus.Ongoing
                "FINISHED", "CANCELLED" -> ShowStatus.Completed
                else -> null
            }
            comingSoon = subbed.isEmpty() && dubbed.isEmpty()
            val nextEpisode = anime.nextAiringEpisode
            val nextAt = anime.nextAiringAt
            if (nextEpisode != null && nextAt != null) nextAiring = NextAiring(nextEpisode, nextAt.toLong(), null)
            actors = anime.characters?.mapNotNull { it.toActorData() }
            // Each season is its own AniList entry, so list the franchise ahead of the recommendations.
            recommendations = (anime.seasons.orEmpty() + anime.recommendations.orEmpty())
                .filter { it.anilistId != anilistId }
                .distinctBy { it.anilistId }
                .mapNotNull { it.toSearchResponse() }

            addAniListId(anilistId)
            addMalId(anime.malId)
            addKitsuId(anime.kitsuId)
            addSimklId(anime.simklId)
            addImdbId(anime.imdbId)
            anime.trailerId?.let { addTrailer("https://www.youtube.com/watch?v=$it") }

            addEpisodes(DubStatus.Subbed, subbed)
            addEpisodes(DubStatus.Dubbed, dubbed)
        }
    }

    private fun Character.toActorData(): ActorData? {
        val characterName = name ?: return null
        val voice = voiceActors?.firstOrNull { it.language == "Japanese" } ?: voiceActors?.firstOrNull()
        return ActorData(
            Actor(characterName, image),
            role = when (role) {
                "MAIN" -> ActorRole.Main
                "SUPPORTING" -> ActorRole.Supporting
                "BACKGROUND" -> ActorRole.Background
                else -> null
            },
            voiceActor = voice?.name?.let { Actor(it, voice.image) }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<LinkData>(data)
        return AnimeXSource.load(link.slug, link.episode, link.lang, name, subtitleCallback, callback)
    }

    private suspend inline fun <reified T> graphql(query: String, variables: Map<String, Any?>): T? {
        val body = mapOf("query" to query, "variables" to variables).toJson()
            .toRequestBody("application/json".toMediaType())
        val response = app.post(graphqlUrl, headers = browserHeaders, requestBody = body)
        if (response.code == 429) throw ErrorLoadingException("AnimeX rate limit reached, try again in a minute")
        val parsed = tryParseJson<GraphQlResponse<T>>(response.text)
        parsed?.errors?.firstOrNull()?.message?.let { Log.w(name, "GraphQL error: $it") }
        return parsed?.data
    }

    private suspend inline fun <reified T> fetch(url: String): T? =
        tryParseJson<T>(app.get(url, headers = browserHeaders).text)

    private fun detailUrl(slug: String?, anilistId: Int) = "$mainUrl/anime/${slug ?: "anime"}-$anilistId"

    private fun displayTitle(english: String?, romaji: String?) =
        english?.takeIf { it.isNotBlank() } ?: romaji?.takeIf { it.isNotBlank() }

    private fun tvType(format: String?) = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "SPECIAL" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    /** AniList seasons: winter is January to March, and so on. */
    private fun currentSeason(): Pair<String, Int> {
        val now = Calendar.getInstance()
        val season = listOf("WINTER", "SPRING", "SUMMER", "FALL")[now.get(Calendar.MONTH) / 3]
        return season to now.get(Calendar.YEAR)
    }

    companion object {
        // GraphQL allows 30 requests a minute; 24 matches the site's own page size.
        private const val PAGE_SIZE = 24
        private const val QUICK_SEARCH_SIZE = 8

        private const val CARD_FIELDS =
            "id anilistId titleEnglish titleRomaji coverImage format seasonYear averageScore subCount dubCount"

        private const val CATALOG_QUERY =
            "query(\$filter: AnimeCatalogFilterInput, \$sort: [AnimeSortInput!], \$limit: Int, \$offset: Int) { " +
                "catalogAnime(filter: \$filter, sort: \$sort, limit: \$limit, offset: \$offset) { " +
                "items { $CARD_FIELDS } hasNextPage } }"

        private const val SEARCH_QUERY =
            "query(\$query: String!, \$limit: Int, \$offset: Int) { " +
                "searchAnime(query: \$query, limit: \$limit, offset: \$offset, includeAdult: false) { " +
                "items { $CARD_FIELDS } hasNextPage } }"

        private const val LINKED_FIELDS = "animeId anilistId title format image coverImage"

        private const val DETAILS_QUERY =
            "query(\$anilistId: Int) { anime(anilistId: \$anilistId) { " +
                "id anilistId malId kitsuId simklId imdbId titleEnglish titleRomaji synonyms description " +
                "coverImage bannerImage backdropUrl logos { src iso639_1 } trailerId format status duration " +
                "genres seasonYear startDateYear averageScore nextAiringAt nextAiringEpisode characters " +
                "seasons { $LINKED_FIELDS } recommendations { $LINKED_FIELDS } } }"
    }

    data class LinkData(val slug: String, val episode: Int, val lang: String)

    data class GraphQlError(val message: String? = null)

    data class GraphQlResponse<T>(val data: T? = null, val errors: List<GraphQlError>? = null)

    data class CoverImage(val extraLarge: String? = null, val large: String? = null, val medium: String? = null) {
        fun best() = extraLarge ?: large ?: medium
    }

    data class Card(
        val id: String? = null,
        val anilistId: Int? = null,
        val titleEnglish: String? = null,
        val titleRomaji: String? = null,
        val coverImage: CoverImage? = null,
        val format: String? = null,
        val seasonYear: Int? = null,
        val averageScore: Int? = null,
        val subCount: Int? = null,
        val dubCount: Int? = null,
    )

    data class Connection(val items: List<Card>? = null, val hasNextPage: Boolean? = null)

    data class CatalogData(val catalogAnime: Connection? = null)

    data class SearchData(val searchAnime: Connection? = null)

    data class RecentItem(
        val id: String? = null,
        val anilistId: Int? = null,
        val titleEnglish: String? = null,
        val titleRomaji: String? = null,
        val coverImage: CoverImage? = null,
        val format: String? = null,
        val episode: Int? = null,
        val isSub: Boolean? = null,
        val isDub: Boolean? = null,
    )

    data class RecentPage(val hasNextPage: Boolean? = null, val results: List<RecentItem>? = null)

    data class LinkedAnime(
        val animeId: String? = null,
        val anilistId: Int? = null,
        val title: String? = null,
        val format: String? = null,
        val image: String? = null,
        val coverImage: CoverImage? = null,
    )

    data class Logo(val src: String? = null, val iso639_1: String? = null)

    data class VoiceActor(val name: String? = null, val image: String? = null, val language: String? = null)

    data class Character(
        val name: String? = null,
        val image: String? = null,
        val role: String? = null,
        val voiceActors: List<VoiceActor>? = null,
    )

    data class Anime(
        val id: String? = null,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: Int? = null,
        val simklId: Int? = null,
        val imdbId: String? = null,
        val titleEnglish: String? = null,
        val titleRomaji: String? = null,
        val synonyms: List<String>? = null,
        val description: String? = null,
        val coverImage: CoverImage? = null,
        val bannerImage: String? = null,
        val backdropUrl: String? = null,
        val logos: List<Logo>? = null,
        val trailerId: String? = null,
        val format: String? = null,
        val status: String? = null,
        val duration: Int? = null,
        val genres: List<String>? = null,
        val seasonYear: Int? = null,
        val startDateYear: Int? = null,
        val averageScore: Int? = null,
        val nextAiringAt: Int? = null,
        val nextAiringEpisode: Int? = null,
        val characters: List<Character>? = null,
        val seasons: List<LinkedAnime>? = null,
        val recommendations: List<LinkedAnime>? = null,
    )

    data class AnimeData(val anime: Anime? = null)

    data class EpisodeTitles(
        val en: String? = null,
        @JsonProperty("x-jat") val romaji: String? = null,
    )

    data class EpisodeInfo(
        val number: Int? = null,
        val titles: EpisodeTitles? = null,
        val img: String? = null,
        val description: String? = null,
        val rating: String? = null,
        val length: Int? = null,
        val airDateUtc: String? = null,
        val hasSub: Boolean? = null,
        val hasDub: Boolean? = null,
    )
}
