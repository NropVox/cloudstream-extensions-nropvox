package com.nropvox

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.nropvox.OmniStreamParser.SiteHit
import java.util.concurrent.ConcurrentHashMap

/**
 * The sources that are found by searching the site for the title: each matches the TMDB title against the site's
 * hits ([OmniStreamParser.matches]), checks the IMDb or TMDB id where the site's page shows one, then plays the
 * episode through the site's own resolver from `shared/`. What a title resolved to is kept for the session, so the
 * next episode skips the search.
 */
internal object OmniStreamSites {
    private const val TAG = "OmniStream"

    /** Posts or pages read per site and title; a show may have one post per season. */
    private const val MAX_PAGES = 3
    private const val CACHE_SIZE = 200

    private val cache = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, find: suspend () -> T?): T? {
        cache[key]?.let { return it as T }
        val value = find() ?: return null
        if (cache.size > CACHE_SIZE) cache.clear()
        cache[key] = value
        return value
    }

    private val OmniLink.series get() = type == "tv"
    private val OmniLink.names get() = listOfNotNull(title, originalTitle).distinct()

    /** The title's year and, for an episode, the year it aired: sites date later seasons by their own year. */
    private val OmniLink.years get() = listOfNotNull(year, airDate?.take(4)?.toIntOrNull()).distinct()

    private fun OmniLink.key(site: String) = "$site:$type:$id:${season ?: 0}"

    suspend fun vegamovies(link: OmniLink, name: String, callback: (ExtractorLink) -> Unit) {
        val pages = cached(link.key("vegamovies")) {
            val hits = VegamoviesSource.search(link.title)?.hits.orEmpty().mapNotNull { hit ->
                val doc = hit.document ?: return@mapNotNull null
                val raw = doc.title ?: return@mapNotNull null
                val permalink = doc.permalink ?: return@mapNotNull null
                SiteHit(VegamoviesSource.SITE + permalink, VegamoviesParser.cleanTitle(raw), VegamoviesParser.year(raw),
                    doc.imdbId, VegamoviesParser.isSeries(raw), raw)
            }
            val urls = OmniStreamParser.matches(hits, link.names, link.years, link.imdbId, link.series, link.season)
            urls.take(MAX_PAGES).amap { url -> safely(TAG, "Vegamovies post") { VegamoviesSource.post(url) } }.filterNotNull()
                .filter { post -> link.imdbId == null || post.imdbId == null || post.imdbId.equals(link.imdbId, true) }
                .flatMap { post ->
                    // A heading without a season is the season the post title names, or the only one.
                    val groups = if (!link.series) post.groups else post.groups.filter { group ->
                        (group.season ?: VegamoviesParser.part(post.rawTitle)?.first ?: 1) == link.season
                    }
                    groups.flatMap(VegamoviesSource::pagesOf)
                }.ifEmpty { null }
        } ?: return
        VegamoviesSource.loadLinks(VgLink(pages, link.episode.takeIf { link.series }), name, callback)
    }

    suspend fun moviesMod(link: OmniLink, name: String, callback: (ExtractorLink) -> Unit) {
        val sources = cached(link.key("moviesmod")) {
            val doc = MoviesModSource.search(link.title) ?: return@cached null
            val hits = MoviesModParser.cards(doc).map { card ->
                SiteHit(card.url, MoviesModParser.cleanTitle(card.title), MoviesModParser.year(card.title),
                    series = MoviesModParser.isSeries(card.title), label = card.title)
            }
            // Cards carry no id; the post's IMDb link is checked below.
            val urls = OmniStreamParser.matches(hits, link.names, link.years, null, link.series, link.season)
            urls.take(MAX_PAGES).amap { url ->
                safely(TAG, "MoviesMod post") {
                    val post = app.get(url, timeout = 30L).document
                    val imdbId = MoviesModParser.details(post).imdbId
                    if (link.imdbId != null && imdbId != null && !imdbId.equals(link.imdbId, true)) return@safely null
                    MoviesModSource.sources(post).filter { source ->
                        if (link.series) MoviesModParser.isEpisodeList(source.url) && MoviesModParser.season(source.heading) == link.season
                        else !MoviesModParser.isEpisodeList(source.url)
                    }
                }
            }.filterNotNull().flatten().ifEmpty { null }
        } ?: return
        MoviesModSource.loadLinks(MmLink(sources, link.episode.takeIf { link.series }), name, callback)
    }

    suspend fun fourKHDHub(link: OmniLink, name: String, callback: (ExtractorLink) -> Unit) {
        // A series page lists every season, so the whole show is kept, not one season.
        val files = cached("4khdhub:${link.type}:${link.id}") {
            val (cards, _) = FourKHDHubSource.search(link.title)
            val hits = cards.map { SiteHit(it.url, it.title, it.year, series = it.series) }
            val urls = OmniStreamParser.matches(hits, link.names, link.years, null, link.series, null)
            urls.take(MAX_PAGES).amap { url ->
                safely(TAG, "4KHDHub page") {
                    val response = app.get(url, timeout = 30L)
                    val doc = response.document
                    if (link.series) return@safely FourKHDHubParser.episodes(doc)
                    // Movie pages name their TMDB id in the player script.
                    val tmdbId = FourKHDHubParser.tmdbId(response.text)
                    if (tmdbId != null && tmdbId != link.id) return@safely null
                    FourKHDHubParser.movieFiles(doc).map { FkEpisode(0, 0, it) }
                }
            }.filterNotNull().flatten().ifEmpty { null }
        } ?: return
        val wanted = if (link.series) files.filter { it.season == link.season && it.episode == link.episode } else files
        if (wanted.isNotEmpty()) FourKHDHubSource.loadLinks(FkLink(wanted.map { it.file }), name, callback)
    }

    suspend fun kissKH(link: OmniLink, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val episodes = cached(link.key("kisskh")) {
            val hits = KissKHSource.search(link.title).mapNotNull { card ->
                val id = card.id ?: return@mapNotNull null
                val (title, year) = OmniStreamParser.splitYear(card.title ?: return@mapNotNull null)
                SiteHit(id, title, year)
            }
            val names = OmniStreamParser.seasonNames(link.names, link.season.takeIf { link.series })
            OmniStreamParser.matches(hits, names, link.years, null, link.series, null).take(MAX_PAGES)
                .firstNotNullOfOrNull { id ->
                    val drama = safely(TAG, "KissKH drama") { KissKHSource.drama(id) } ?: return@firstNotNullOfOrNull null
                    if ((drama.type == "Movie") == link.series) return@firstNotNullOfOrNull null
                    // KissKH names most shows without a year and dates them by when it added them, never before they
                    // aired: a date well before the title's year is an older show of the same name.
                    val added = drama.releaseDate?.take(4)?.toIntOrNull()
                    if (added != null && link.years.isNotEmpty() && added < link.years.min() - 1) return@firstNotNullOfOrNull null
                    drama.episodes.mapNotNull { episode ->
                        val number = episode.number?.takeIf { it % 1.0 == 0.0 }?.toInt() ?: return@mapNotNull null
                        episode.id?.let { number to it }
                    }.toMap().ifEmpty { null }
                }
        } ?: return
        val episodeId = (if (link.series) episodes[link.episode] else episodes[1] ?: episodes.values.singleOrNull()) ?: return
        KissKHSource.loadEpisode(episodeId, name, subtitleCallback, callback)
    }

    /** [source] is OmniStream's API name: GoPlay's single-use URLs are renewed by its video interceptor. */
    suspend fun goPlay(link: OmniLink, source: String, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val title = cached(link.key("goplay")) {
            val hits = GoPlaySource.search(link.title).map { SiteHit(it.url, GoPlaySource.stripOriginalTitle(it.title), null) }
            val names = OmniStreamParser.seasonNames(link.names, link.season.takeIf { link.series })
            OmniStreamParser.matches(hits, names, link.years, null, link.series, null).take(MAX_PAGES)
                .firstNotNullOfOrNull { url ->
                    val page = safely(TAG, "GoPlay title") { GoPlaySource.title(url) } ?: return@firstNotNullOfOrNull null
                    // GoPlay puts the original name after the English one; where both sides have it, it must agree.
                    val original = page.originalTitle
                    val sameOriginal = original == null || link.originalTitle == null ||
                        OmniStreamParser.sameTitle(original, listOf(link.originalTitle))
                    page.takeIf { it.isMovie != link.series && sameOriginal }
                }
        } ?: return
        val data = (if (link.series) title.episodes[link.episode] else title.url) ?: return
        GoPlaySource.loadLinks(data, source, name, subtitleCallback, callback)
    }
}
