package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * reanime.to is a SvelteKit site over its own JSON API under `/api/v1/`. Streams come from the embedded
 * `flixcloud.cc` player: `/api/flix/{anilist}/{ep}` lists its embeds, whose page carries an encrypted master URL
 * that [ReanimeParser.decodeEmbed] unlocks. Sub and dub are audio renditions of one HLS master. The player chain is
 * [ReanimeSource] in `shared/reanimesource` (shared with OmniStream).
 */
class Reanime : MainAPI() {
    override var mainUrl = ReanimeSource.SITE
    override var name = "Reanime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.NSFW)

    // Home rows: `home:*` come from one /api/v1/home call (the later pages of three of them continue by cursor),
    // `search:*` are browse queries on /api/v1/search paged by offset.
    override val mainPage = mainPageOf(
        "home:trending" to "Trending",
        "home:latest_aired" to "Latest Episodes",
        "home:new_on_site" to "New on Site",
        "search:sort=popularity_desc" to "Most Popular",
        "search:format=MOVIE&sort=popularity_desc" to "Popular Movies",
        "home:upcoming" to "Upcoming",
    )

    /** Cursor to fetch page N+1 of a row, keyed by `row#N`. Rows are only ever paged forward. */
    private val cursors = ConcurrentHashMap<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (kind, arg) = request.data.split(':', limit = 2)
        if (kind == "search") {
            val result = tryParseJson<RaSearch>(app.get("$mainUrl/api/v1/search?$arg&limit=$PAGE_SIZE&offset=${(page - 1) * PAGE_SIZE}").text)
                ?: throw ErrorLoadingException("Could not load ${request.name}")
            val total = result.total ?: 0
            return newHomePageResponse(
                request.name,
                result.results.orEmpty().mapNotNull { it.toSearchResponse() },
                hasNext = page * PAGE_SIZE < total
            )
        }

        if (page <= 1) {
            val home = tryParseJson<RaHome>(app.get("$mainUrl/api/v1/home").text)
                ?: throw ErrorLoadingException("Could not load ${request.name}")
            val (cards, cursor) = when (arg) {
                "latest_aired" -> home.latest_aired to home.latest_aired_cursor
                "new_on_site" -> home.new_on_site to home.new_on_site_cursor
                "upcoming" -> home.upcoming to home.upcoming_cursor
                else -> home.trending to null
            }
            cursor?.let { cursors["$arg#2"] = it }
            return newHomePageResponse(request.name, cards.orEmpty().mapNotNull { it.toSearchResponse() }, hasNext = cursor != null)
        }

        val cursor = cursors["$arg#$page"] ?: return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        val path = arg.replace('_', '-')
        val result = tryParseJson<RaCursorPage>(
            app.get("$mainUrl/api/v1/home/$path?limit=$HOME_PAGE_SIZE&cursor=${encode(cursor)}").text
        ) ?: throw ErrorLoadingException("Could not load ${request.name}")
        result.next_cursor?.takeIf { result.has_more == true }?.let { cursors["$arg#${page + 1}"] = it }
        return newHomePageResponse(
            request.name,
            result.data.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = result.has_more == true && result.next_cursor != null
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val result = searchPage(query, PAGE_SIZE, (page - 1) * PAGE_SIZE)
        return newSearchResponseList(
            result?.results.orEmpty().mapNotNull { it.toSearchResponse() },
            hasNext = page * PAGE_SIZE < (result?.total ?: 0)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        if (query.isBlank()) emptyList() else searchPage(query, QUICK_SEARCH_SIZE, 0)?.results.orEmpty().mapNotNull { it.toSearchResponse() }

    private suspend fun searchPage(query: String, limit: Int, offset: Int): RaSearch? =
        tryParseJson<RaSearch>(app.get("$mainUrl/api/v1/search?q=${encode(query.trim())}&limit=$limit&offset=$offset").text)

    override suspend fun load(url: String): LoadResponse {
        val slug = ReanimeParser.slugOf(url) ?: throw ErrorLoadingException("Not a $name URL: $url")
        val anime = tryParseJson<RaDetails>(app.get("$mainUrl/api/v1/anime/$slug").text)
            ?: throw ErrorLoadingException("Could not load $name title $slug")
        val title = ReanimeParser.pickTitle(anime.title?.english, anime.title?.romaji, anime.title?.native)
            ?: throw ErrorLoadingException("$name title $slug has no name")
        // The list is flat and absolute-numbered; each season is its own title on the site.
        val episodes = runCatching {
            tryParseJson<RaEpisodes>(app.get("$mainUrl/api/v1/anime/$slug/episodes?limit=$EPISODE_LIMIT").text)?.data
        }.onFailure { Log.w(name, "Episodes for $slug: $it") }.getOrNull().orEmpty()
            .filter { it.playable != false && it.episode_number != null }
            .sortedBy { it.episode_number }

        val pageUrl = "$mainUrl/anime/$slug"
        val type = tvType(anime.format, anime.is_adult == true || anime.genres.orEmpty().contains(HENTAI))

        fun watch(number: Int, lang: String) = ReanimeParser.watchUrl(mainUrl, slug, number, anime.anilist_id, anime.mal_id, lang)

        suspend fun LoadResponse.fill() {
            posterUrl = anime.cover_image?.extra_large ?: anime.cover_image?.large
            backgroundPosterUrl = anime.banner_image
            plot = ReanimeParser.plainText(anime.description)
            year = anime.season_year ?: anime.start_date?.year
            tags = anime.genres
            duration = anime.duration
            score = Score.from100(anime.average_score)
            anime.anilist_id?.takeIf { it > 0 }?.let { addAniListId(it) }
            anime.mal_id?.takeIf { it > 0 }?.let { addMalId(it) }
            anime.trailer?.takeIf { it.site == "youtube" }?.id?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
        }

        if (type == TvType.AnimeMovie && episodes.size <= 1) {
            return newMovieLoadResponse(title, pageUrl, type, watch(episodes.firstOrNull()?.episode_number ?: 1, "sub")) { fill() }
        }

        fun episodesFor(lang: String, available: (RaEpisode) -> Boolean) = episodes.filter(available).map { info ->
            val number = info.episode_number!!
            newEpisode(watch(number, lang)) {
                episode = number
                name = info.title?.takeUnless { it.equals("Episode $number", ignoreCase = true) }
                description = ReanimeParser.plainText(info.description)
                posterUrl = info.thumbnail
                addDate(info.aired?.take(10))
            }
        }
        val subbed = episodesFor("sub") { it.subbed != false }
        val dubbed = episodesFor("dub") { it.dubbed == true }

        return newAnimeLoadResponse(title, pageUrl, type) {
            fill()
            engName = anime.title?.english?.takeIf { it.isNotBlank() }
            japName = anime.title?.romaji
            synonyms = anime.synonyms
            showStatus = when (anime.status) {
                "Releasing", "RELEASING" -> ShowStatus.Ongoing
                "Finished", "FINISHED", "Cancelled" -> ShowStatus.Completed
                else -> null
            }
            comingSoon = subbed.isEmpty() && dubbed.isEmpty()
            addEpisodes(DubStatus.Subbed, subbed)
            addEpisodes(DubStatus.Dubbed, dubbed)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watch = ReanimeParser.parseWatchUrl(data) ?: return false
        return ReanimeSource.load(watch.anilistId, watch.malId, watch.episode, listOf(watch.lang), name, subtitleCallback, callback)
    }

    private fun RaCard.toSearchResponse(): SearchResponse? {
        val slug = anime_id ?: return null
        val title = ReanimeParser.pickTitle(title?.english, title?.romaji, title?.native) ?: return null
        val card = this
        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug", tvType(format, genres.orEmpty().contains(HENTAI))) {
            posterUrl = card.cover_image?.large ?: card.cover_image?.medium
            year = card.season_year
            val dubs = card.dubbed ?: 0
            val subs = card.subbed ?: 0
            addDubStatus(dubs > 0, subs > 0, dubs.takeIf { it > 0 }, subs.takeIf { it > 0 })
        }
    }

    private fun tvType(format: String?, adult: Boolean) = when {
        adult -> TvType.NSFW
        format.equals("MOVIE", ignoreCase = true) -> TvType.AnimeMovie
        format.equals("OVA", ignoreCase = true) || format.equals("SPECIAL", ignoreCase = true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val HENTAI = "Hentai"
        private const val PAGE_SIZE = 20
        private const val HOME_PAGE_SIZE = 12
        private const val QUICK_SEARCH_SIZE = 6
        private const val EPISODE_LIMIT = 2000
    }
}
