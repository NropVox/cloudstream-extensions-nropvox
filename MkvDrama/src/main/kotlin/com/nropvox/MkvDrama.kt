package com.nropvox

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

const val PIXELDRAIN_API_KEY_PREF_KEY = "pixeldrain_api_key"

class MkvDrama(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {
    override var mainUrl = "https://mkvdrama.net"
    override var name = "MkvDrama"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie)
    override val mainPage = mainPageOf(
        "type=drama&order=latest" to "Latest dramas",
        "type=mini_drama&order=latest" to "Mini dramas",
        "type=movie&order=latest" to "Movies",
        "order=popular" to "Popular",
        "order=rating" to "Top rated",
    )
    private val linkCache = MkvDramaCache()

    private fun image(value: String?): String? = value?.let { URI(mainUrl).resolve(it).toString() }
        ?.takeIf(MkvDramaDecoder::isHttp)?.let { url ->
            if (URI(url).host == URI(mainUrl).host && URI(url).path.startsWith("/wp-content/"))
                "https://i2.wp.com/${url.substringAfter("://")}" else url
        }

    private fun card(title: String, url: String, type: String?, poster: String?, release: String? = null): SearchResponse =
        if (type == "movie") newMovieSearchResponse(title, url, TvType.Movie) {
            posterUrl = image(poster)
            year = release?.take(4)?.toIntOrNull()
        } else newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
            posterUrl = image(poster)
            year = release?.take(4)?.toIntOrNull()
        }

    private fun JsonNode.toCard(): SearchResponse? {
        val title = textOrNull("title") ?: return null
        val slug = textOrNull("slug")?.takeIf { '/' !in it } ?: return null
        return card(title, "$mainUrl/$slug", textOrNull("type"), textOrNull("cover_url") ?: textOrNull("poster_url"), textOrNull("release_date"))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = MkvDramaSession().get("$mainUrl/titles/?${request.data}&page=$page", acceptJson = true).json()
        check(result.path("results").isArray) { "Unrecognized MKVDrama catalog response" }
        return newHomePageResponse(request.name, result.path("results").mapNotNull { it.toCard() },
            hasNext = result.path("page").asInt(page).toLong() * result.path("per_page").asInt(30) < result.path("total").asLong())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val url = "$mainUrl/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page"
        val response = MkvDramaSession().get(url)
        val document = Jsoup.parse(response.text, response.url)
        val results = document.select("article.bs a.tip[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href")
            if (!MkvDramaDecoder.isHttp(href) || URI(href).host != URI(mainUrl).host) return@mapNotNull null
            val title = anchor.attr("title").ifBlank { anchor.selectFirst("h2")?.text().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val img = anchor.selectFirst("img")
            val poster = img?.attr("data-src")?.ifBlank { img.attr("data-lazy-src") }?.ifBlank { img.attr("src") }
            card(title, href, anchor.selectFirst(".typez")?.text()?.lowercase(), poster)
        }.distinctBy { it.url }
        return newSearchResponseList(results, hasNext = document.selectFirst(".hpage a.page-next[href]") != null)
    }

    override suspend fun load(url: String): LoadResponse {
        val session = MkvDramaSession()
        val canonical = URI(mainUrl).resolve(URI(url).path).toString()
        val series = session.get(canonical, acceptJson = true).json().path("series")
        val title = series.textOrNull("title") ?: throw ErrorLoadingException("MKVDrama title metadata is unavailable")
        val poster = image(series.textOrNull("cover_url"))
        val releaseYear = series.textOrNull("release_date")?.take(4)?.toIntOrNull()
        val genres = series.path("genres").mapNotNull { it.textOrNull("name") }
        val actors = series.path("cast").mapNotNull { it.textOrNull("name") }
        val trailer = series.textOrNull("trailer_id")?.let { "https://www.youtube.com/watch?v=$it" }
        val plot = series.textOrNull("synopsis")
        if (series.textOrNull("type") == "movie") {
            return newMovieLoadResponse(title, canonical, TvType.Movie, mkvJson.writeValueAsString(MkvEpisodeData(canonical))) {
                posterUrl = poster
                year = releaseYear
                this.plot = plot
                tags = genres
                score = Score.from10(series.get("score")?.takeUnless { it.isNull }?.asDouble())
                duration = series.path("duration_minutes").asInt().takeIf { it > 0 }
                addActors(actors)
                addTrailer(trailer)
            }
        }
        val releases = MkvDramaDecoder.releases(session.panel(canonical), canonical)
        val regular = releases.filterNot { MkvDramaDecoder.isExtra(it.group) }
        // The metadata total is a planned count; expose only ranges published in the panel.
        val numbers = regular.flatMap { item ->
            MkvDramaDecoder.span(item.group)?.let { range ->
                if (range.last <= 2000) (range.first..range.last).toList() else emptyList()
            }.orEmpty()
        }.distinct().sorted()
        val episodes = numbers.map { number ->
            newEpisode(mkvJson.writeValueAsString(MkvEpisodeData(canonical, number))) {
                name = "Episode $number"
                episode = number
            }
        } + releases.filter { MkvDramaDecoder.isExtra(it.group) }.distinctBy { it.group }.map { item ->
            newEpisode(mkvJson.writeValueAsString(MkvEpisodeData(canonical, group = item.group))) { name = item.group }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException("MKVDrama has no published, identifiable episode links for this title")
        return newTvSeriesLoadResponse(title, canonical, TvType.AsianDrama, episodes) {
            posterUrl = poster
            backgroundPosterUrl = image(series.textOrNull("big_cover_url"))
            year = releaseYear
            this.plot = plot
            tags = genres
            score = Score.from10(series.get("score")?.takeUnless { it.isNull }?.asDouble())
            showStatus = when (series.textOrNull("status")) {
                "completed" -> ShowStatus.Completed
                "ongoing" -> ShowStatus.Ongoing
                else -> null
            }
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun pixeldrainApiKey(): String =
        sharedPref?.getString(PIXELDRAIN_API_KEY_PREF_KEY, "")?.trim().orEmpty()

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val target = mkvJson.readValue(data, MkvEpisodeData::class.java)
        if (!MkvDramaLinks(linkCache, target, pixeldrainApiKey(), subtitleCallback, callback).play())
            throw ErrorLoadingException("No playable MKVDrama mirror resolved. Shortener/Filecrypt verification or an unsupported file host may block this release.")
        return true
    }
}
