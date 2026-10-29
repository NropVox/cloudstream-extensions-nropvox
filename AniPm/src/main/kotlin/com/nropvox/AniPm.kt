package com.nropvox

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
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
import java.net.URLEncoder

/**
 * ani.pm is a Vite SPA over a JSON API (`/api/anime/...`) that mixes two backends: its own
 * "settlar" library and AniList-keyed titles. Both play through the same chain:
 * playback-bootstrap → settlar session → embed.settlar.io session → plain HLS on media.settlar.io, run by
 * [AniPmSource] in `shared/anipmsource` (shared with OmniStream).
 */
class AniPm : MainAPI() {
    override var mainUrl = "https://ani.pm"
    override var name = "Ani.pm"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val api get() = "$mainUrl/api"

    // Every ani.pm and settlar host, covers and segments included, answers 403 to non-browser user agents.
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    // sfw=1 drops the site's hentai titles, as its "hide hentai" setting does.
    override val mainPage = mainPageOf(
        "anime/catalog?sort=trending" to "Trending",
        "anime/latest-episodes?limit=40" to "Latest Episodes",
        "anime/catalog?sort=newest&status=RELEASING&avail=sourced" to "New Releases",
        "anime/catalog?sort=popular" to "Most Popular",
        "anime/catalog?sort=score" to "Top Rated",
        "anime/catalog?sort=trending&format=MOVIE" to "Movies",
        "anime/catalog?sort=trending&audio=dub" to "Dubbed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = fetch<ApPage>("${request.data}&sfw=1&page=$page")
            ?: throw ErrorLoadingException("Could not load ${request.name}")
        // Latest episodes lists a title once per new episode.
        val items = result.items.orEmpty().mapNotNull { it.toSearchResponse() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = result.hasNextPage == true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // The endpoint ignores paging and returns at most 30 matches.
        if (page > 1 || query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val result = fetch<ApPage>("anime/search?q=$encoded&sfw=1")
        return newSearchResponseList(result?.items.orEmpty().mapNotNull { it.toSearchResponse() }, hasNext = false)
    }

    private fun ApCard.toSearchResponse(): SearchResponse? {
        val ref = AniPmParser.ref(this) ?: return null
        val title = title ?: return null
        val card = this
        return newAnimeSearchResponse(title, AniPmParser.titleUrl(mainUrl, routeId, ref), tvType(type)) {
            posterUrl = AniPmParser.absolute(mainUrl, card.poster)
            posterHeaders = browserHeaders
            year = card.year
            score = Score.from10(card.score)
            val subbed = card.subCount ?: 0
            val dubbed = card.dubCount ?: 0
            addDubStatus(dubbed > 0, subbed > 0, dubbed.takeIf { it > 0 }, subbed.takeIf { it > 0 })
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parsed = AniPmParser.parseRef(url) ?: return null
        // A pasted site URL doesn't say which backend the number belongs to.
        val candidates = if (parsed.source.isEmpty()) {
            listOf(ApRef(AniPmParser.SETTLAR, parsed.id), ApRef(AniPmParser.ANILIST, parsed.id))
        } else {
            listOf(parsed)
        }
        val (ref, details) = candidates.firstNotNullOfOrNull { ref ->
            fetch<ApDetails>(AniPmParser.detailsPath(ref))?.takeIf { it.title != null }?.let { ref to it }
        } ?: throw ErrorLoadingException("Could not load ani.pm title ${parsed.id}")
        val title = details.title ?: return null

        fun episodes(dub: Boolean) = details.episodes.orEmpty()
            .filter { (if (dub) it.dub else it.sub) == true }
            .mapNotNull { info ->
                val number = info.number ?: return@mapNotNull null
                newEpisode(ApLinkData(ref.source, ref.id, number, if (dub) "dub" else "sub").toJson()) {
                    episode = number
                    name = info.title
                    description = info.description
                    // No episode thumbnails: stills need the browser UA and episodes can't carry headers.
                    runTime = AniPmParser.minutes(info.runtime)
                    score = Score.from10(info.rating?.toDoubleOrNull())
                    addDate(info.aired)
                }
            }
        val subbed = episodes(dub = false)
        val dubbed = episodes(dub = true)

        return newAnimeLoadResponse(title, AniPmParser.titleUrl(mainUrl, details.routeId, ref), tvType(details.type)) {
            japName = details.native
            synonyms = details.providerTitles.orEmpty().filter { it != title }.distinct()
            posterUrl = AniPmParser.absolute(mainUrl, details.poster)
            posterHeaders = browserHeaders
            backgroundPosterUrl = AniPmParser.absolute(mainUrl, details.heroBackdrop ?: details.banner)
            logoUrl = AniPmParser.absolute(mainUrl, details.logo)
            year = details.year
            plot = details.synopsis
            tags = details.genres
            duration = AniPmParser.minutes(details.duration)
            contentRating = details.rating?.takeIf { it != "N/A" }
            score = Score.from10(details.score)
            showStatus = when (AniPmParser.isOngoing(details.status)) {
                true -> ShowStatus.Ongoing
                false -> ShowStatus.Completed
                null -> null
            }
            comingSoon = subbed.isEmpty() && dubbed.isEmpty()
            nextAiring = details.nextAiring?.let { next ->
                val episode = next.episode ?: return@let null
                next.airingAt?.let { NextAiring(episode, it, null) }
            }
            // The site never fills recommendations; relations hold the other seasons.
            recommendations = details.relations.orEmpty().mapNotNull { it.toSearchResponse() }
            if (subbed.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbed)
            if (dubbed.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbed)
            addMalId(details.malId?.toIntOrNull())
            addAniListId(details.anilistId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = AniPmSource.load(parseJson<ApLinkData>(data), name, subtitleCallback, callback)

    private fun tvType(type: String?) = when {
        AniPmParser.isMovie(type) -> TvType.AnimeMovie
        AniPmParser.isOva(type) -> TvType.OVA
        else -> TvType.Anime
    }

    private suspend inline fun <reified T> fetch(path: String): T? =
        tryParseJson<T>(app.get("$api/$path", headers = browserHeaders).text)
}
