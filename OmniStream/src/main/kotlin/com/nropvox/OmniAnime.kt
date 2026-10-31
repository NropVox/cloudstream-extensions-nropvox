package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
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
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * OmniAnime: OmniStream for anime only, on an AniList catalog. AniList entries are what the anime sites key their
 * episodes by, so AnimoTv, Ani.pm, Reanime and AnimeX are asked straight away with no title matching, while
 * [OmniAnimeMap] places the episode on TMDB for OmniStream's TMDB-keyed sources, sites and Stremio addons. All of them
 * run in one [OmniLinkRace]. Episode names and stills come from ani.zip.
 */
class OmniAnime : MainAPI() {
    override var mainUrl = "https://anilist.co"
    override var name = "OmniAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "trending" to "Trending now",
        "airing" to "Airing now",
        "popular" to "All-time popular",
        "top" to "Top rated",
        "movies" to "Popular movies",
    )

    /** AniList filters per row. A filter sent as null matches entries where that field is null, so absent ones are left out. */
    private fun rowFilters(row: String): Map<String, Any> = when (row) {
        "airing" -> mapOf("sort" to listOf("POPULARITY_DESC"), "status" to "RELEASING")
        "popular" -> mapOf("sort" to listOf("POPULARITY_DESC"))
        "top" -> mapOf("sort" to listOf("SCORE_DESC"))
        "movies" -> mapOf("sort" to listOf("POPULARITY_DESC"), "format" to "MOVIE")
        else -> mapOf("sort" to listOf("TRENDING_DESC"))
    }

    private suspend fun aniList(query: String, variables: Map<String, Any>): String? = safely(name, "AniList") {
        val response = app.post(
            ANILIST,
            headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            json = AniListQuery(query, variables),
            timeout = 15L,
        )
        if (!response.isSuccessful) Log.w(name, "AniList: HTTP ${response.code}")
        response.takeIf { it.isSuccessful }?.text
    }

    private suspend fun page(filters: Map<String, Any>, page: Int): OmniAniPage? =
        aniList(PAGE_QUERY, filters + ("page" to page))?.let { tryParseJson<OmniAniPageResponse>(it) }?.data?.page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = page(rowFilters(request.data), page)
        val items = result?.media.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = result?.pageInfo?.hasNextPage == true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val result = page(mapOf("search" to query.trim(), "sort" to listOf("SEARCH_MATCH")), page)
        return newSearchResponseList(result?.media.orEmpty().mapNotNull { it.toSearchResponse() }, hasNext = result?.pageInfo?.hasNextPage == true)
    }

    private fun OmniAniMedia.displayTitle() = title?.english?.takeIf { it.isNotBlank() } ?: title?.romaji ?: title?.native

    private fun OmniAniMedia.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = displayTitle() ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", tvType(format)) {
            posterUrl = coverImage?.extraLarge ?: coverImage?.large
            year = startDate?.year
            score = Score.from100(averageScore)
        }
    }

    private fun tvType(format: String?) = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "SPECIAL", "MUSIC" -> TvType.OVA
        else -> TvType.Anime
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val (anime, zip) = coroutineScope {
            val zip = async { OmniAnimeMap.aniZip(id) }
            val anime = aniList(DETAILS_QUERY, mapOf("id" to id))?.let { tryParseJson<OmniAniMediaResponse>(it) }?.data?.media
            anime to zip.await()
        }
        if (anime == null) return null
        val title = anime.displayTitle() ?: return null
        val titles = (listOfNotNull(anime.title?.english, anime.title?.romaji) + anime.synonyms.orEmpty().filter { it.all(Char::isLatin) })
            .filter { it.isNotBlank() }.distinct()
        val link = OmniAnimeLink(
            anilistId = id,
            malId = anime.idMal,
            format = anime.format,
            startDate = anime.startDate?.let { OmniStreamParser.isoDate(it.year, it.month, it.day) },
            titles = titles,
        )
        // The page is open, so the TMDB lookup the TMDB-keyed sources wait for starts now, not on play.
        OmniAnimeMap.warm(link)

        val type = tvType(anime.format)
        val load: LoadResponse = if (type == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, type, link.toJson())
        } else {
            val today = (System.currentTimeMillis() / 86_400_000L).toInt()
            val count = OmniStreamParser.airedEpisodes(anime.status, anime.episodes, anime.nextAiringEpisode?.episode, zip?.episodes.orEmpty(), today)
            val episodes = (1..count).map { number ->
                val info = zip?.episodes?.get(number)
                newEpisode(link.copy(episode = number).toJson()) {
                    episode = number
                    name = info?.title
                    description = info?.overview
                    posterUrl = info?.image
                    runTime = info?.runtime
                    addDate(info?.airDate)
                }
            }
            newAnimeLoadResponse(title, url, type) {
                engName = anime.title?.english
                japName = anime.title?.romaji
                synonyms = anime.synonyms.orEmpty()
                showStatus = when (anime.status) {
                    "RELEASING", "HIATUS" -> ShowStatus.Ongoing
                    "FINISHED", "CANCELLED" -> ShowStatus.Completed
                    else -> null
                }
                comingSoon = episodes.isEmpty()
                anime.nextAiringEpisode?.let { next ->
                    if (next.episode != null && next.airingAt != null) nextAiring = NextAiring(next.episode, next.airingAt, null)
                }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
        return load.apply {
            posterUrl = anime.coverImage?.extraLarge ?: anime.coverImage?.large
            backgroundPosterUrl = anime.bannerImage
            plot = ReanimeParser.plainText(anime.description)
            year = anime.seasonYear ?: anime.startDate?.year
            tags = anime.genres
            duration = anime.duration
            score = Score.from100(anime.averageScore)
            actors = anime.characters?.edges.orEmpty().mapNotNull { it.toActorData() }
            // Each season is its own AniList entry, so the franchise goes ahead of the recommendations.
            recommendations = (anime.relations?.edges.orEmpty().filter { it.node?.type == "ANIME" }.mapNotNull { it.node } +
                anime.recommendations?.nodes.orEmpty().mapNotNull { it.media })
                .filter { it.id != id }
                .distinctBy { it.id }
                .mapNotNull { it.toSearchResponse() }
            addAniListId(id)
            addMalId(anime.idMal)
            addImdbId(zip?.imdbId)
            addTMDbId(zip?.tmdbId?.toString())
            anime.trailer?.takeIf { it.site == "youtube" }?.id?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
        }
    }

    private fun OmniAniCharacterEdge.toActorData(): ActorData? {
        val character = node?.name?.full ?: return null
        val voice = voiceActors?.firstOrNull()
        return ActorData(
            Actor(character, node?.image?.large),
            role = when (role) {
                "MAIN" -> ActorRole.Main
                "SUPPORTING" -> ActorRole.Supporting
                "BACKGROUND" -> ActorRole.Background
                else -> null
            },
            voiceActor = voice?.name?.full?.let { Actor(it, voice.image?.large) },
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val anime = parseJson<OmniAnimeLink>(data)
        val target = OmniStreamParser.AniTarget(anime.anilistId, anime.episode, anime.malId ?: 0)
        val sources = OmniStreamAnime.sources { target } + OmniStream.tmdbSources(once { OmniAnimeMap.link(anime) })
        return OmniLinkRace(name, subtitleCallback, callback).run(sources)
    }

    companion object {
        private const val ANILIST = "https://graphql.anilist.co"

        private const val CARD = "id idMal type format status episodes averageScore " +
            "title { romaji english native } coverImage { extraLarge large } startDate { year month day }"

        private const val PAGE_QUERY = "query (${'$'}page: Int, ${'$'}sort: [MediaSort], ${'$'}format: MediaFormat, " +
            "${'$'}status: MediaStatus, ${'$'}search: String) { Page(page: ${'$'}page, perPage: 30) { pageInfo { hasNextPage } " +
            "media(type: ANIME, isAdult: false, sort: ${'$'}sort, format: ${'$'}format, status: ${'$'}status, search: ${'$'}search) { $CARD } } }"

        private const val DETAILS_QUERY = "query (${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) { $CARD " +
            "duration description(asHtml: false) genres seasonYear synonyms bannerImage nextAiringEpisode { episode airingAt } " +
            "trailer { id site } " +
            "characters(sort: [ROLE, RELEVANCE], perPage: 25) { edges { role node { name { full } image { large } } " +
            "voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full } image { large } } } } " +
            "recommendations(sort: [RATING_DESC], perPage: 20) { nodes { mediaRecommendation { $CARD } } } " +
            "relations { edges { relationType node { $CARD } } } } }"
    }
}

/** Latin letters, digits, punctuation: synonyms in other scripts are no use for a TMDB search. */
private fun Char.isLatin() = code < 0x250
