package com.nropvox

import kotlin.io.encoding.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
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
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Miruro is a SvelteKit app. Catalogue, search and details come from its page loaders
 * (`{route}/__data.json`, see [SvelteData]); streams come from the JSON `/api/sources` endpoint.
 * Requests need a browser user agent and must not carry query params a route doesn't read (400 otherwise).
 */
class Miruro : MainAPI() {
    override var mainUrl = "https://barelystarted.miruro.tv"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // curl/* and other bot-looking user agents get 403.
    private val browserHeaders = mapOf("User-Agent" to USER_AGENT)

    private val idPattern = Regex("""/(?:info|watch)/([\w-]{16,})""")

    // Some server names carry a long hash prefix: "cf01a14a…-cloudflare-2" -> "cloudflare-2".
    private val serverHashPrefix = Regex("^[0-9a-f]{16,}-")

    // Search pages are cursor-based; remember each page's cursor so page N+1 can be requested.
    private val searchCursors = ConcurrentHashMap<String, String>()

    @Volatile
    private var proxy: Proxy? = null

    override val mainPage = mainPageOf(
        "latest/raw" to "Latest Episodes",
        "latest/dub" to "Latest Dubbed Episodes",
        "trending" to "Trending This Season",
        "search?status=RELEASING" to "Currently Airing",
        "search?" to "Most Popular",
        "search?sort=-score" to "Top Rated",
        "search?format=MOVIE" to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val (items, hasNext) = when {
            data.startsWith("latest/") -> {
                val rails = if (page > 1) null else fetchPage<Home>("/")?.rails
                val rail = if (data.endsWith("dub")) rails?.dub else rails?.raw
                rail?.latest.orEmpty().mapNotNull { it.toSearchResponse() } to false
            }
            // The current season fits on one page.
            data == "trending" -> {
                val items = if (page > 1) null else fetchPage<Trending>("/trending")?.items
                items.orEmpty().mapNotNull { it.toSearchResponse() } to false
            }
            else -> searchPage(data.substringAfter('?'), page)
        }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (items, hasNext) = searchPage("q=${encode(query.trim())}", page)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        // This endpoint only accepts exactly limit=8 and type=ANIME.
        val url = "$mainUrl/api/search/browse?q=${encode(query.trim())}&limit=8&type=ANIME"
        return tryParseJson<QuickSearch>(app.get(url, headers = browserHeaders).text)
            ?.data?.mapNotNull { it.toSearchResponse() }
    }

    private suspend fun searchPage(params: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val cursor = if (page <= 1) null else searchCursors["$params|$page"] ?: return emptyList<SearchResponse>() to false
        val query = listOfNotNull(params.ifEmpty { null }, cursor?.let { "cursor=${encode(it)}" }).joinToString("&")
        val result = fetchPage<SearchPage>("/search", query) ?: return emptyList<SearchResponse>() to false
        val next = result.nextCursor?.takeIf { result.hasMore == true }
        next?.let { searchCursors["$params|${page + 1}"] = it }
        return result.items.orEmpty().mapNotNull { it.toSearchResponse() } to (next != null)
    }

    private fun Anime.toSearchResponse(): SearchResponse? =
        searchResponse(id, title, format, coverUrl, seasonYear, averageScore, episodeCounts)

    private fun RailItem.toSearchResponse(): SearchResponse? =
        searchResponse(animeId, title, format, coverUrl, seasonYear, null, episodeCounts)

    private fun searchResponse(
        id: String?,
        title: Title?,
        format: String?,
        cover: String?,
        year: Int?,
        averageScore: Int?,
        counts: EpisodeCounts?,
    ): SearchResponse? {
        val animeId = id ?: return null
        val name = title?.preferred() ?: return null
        return newAnimeSearchResponse(name, infoUrl(animeId, title), tvType(format)) {
            posterUrl = cover
            this.year = year
            score = Score.from100(averageScore)
            val subbed = counts?.subbed() ?: 0
            val dubbed = counts?.dub ?: 0
            addDubStatus(dubbed > 0, subbed > 0, dubbed.takeIf { it > 0 }, subbed.takeIf { it > 0 })
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = idPattern.find(url)?.groupValues?.get(1) ?: return null
        // Any slug works; the loader redirects to the canonical one.
        val slug = url.substringAfter("/$id/", "").substringBefore('/').substringBefore('?').ifEmpty { "x" }
        val page = fetchPage<InfoPage>("/info/$id/$slug") ?: throw ErrorLoadingException("Could not load $id")
        val detail = page.detail ?: throw ErrorLoadingException("Could not load $id")
        val title = detail.title?.preferred() ?: return null

        val metadata = page.episodes.orEmpty().associateBy { it.episodeNumber }
        fun episodes(count: Int, dub: Boolean) = (1..count).map { number ->
            val info = metadata[number]
            newEpisode(LinkData(id, number, dub).toJson()) {
                episode = number
                name = info?.title
                description = info?.synopsis
                posterUrl = info?.thumbnailUrl
                runTime = info?.durationSeconds?.div(60)
                addDate(info?.airedOn)
            }
        }
        // "raw" can run ahead of "sub" on airing shows; both play from the subbed tracks.
        val subbed = episodes(detail.episodeCounts?.subbed() ?: 0, dub = false)
        val dubbed = episodes(detail.episodeCounts?.dub ?: 0, dub = true)

        return newAnimeLoadResponse(title, infoUrl(id, detail.title), tvType(detail.format)) {
            engName = detail.title?.english
            japName = detail.title?.native
            synonyms = detail.alternateTitles.orEmpty().mapNotNull { it.value }.distinct()
            posterUrl = detail.coverUrl
            backgroundPosterUrl = detail.backgroundUrl ?: detail.bannerUrl
            logoUrl = detail.logoUrl
            year = detail.seasonYear
            plot = detail.description
            tags = detail.genres
            duration = detail.episodeDurationMinutes
            score = Score.from100(detail.averageScore)
            showStatus = when (detail.status) {
                "RELEASING", "HIATUS" -> ShowStatus.Ongoing
                "FINISHED", "CANCELLED" -> ShowStatus.Completed
                else -> null
            }
            comingSoon = subbed.isEmpty() && dubbed.isEmpty()
            nextAiring = detail.nextAiring?.let { next ->
                val episode = next.episodeNumber ?: return@let null
                parseIsoSeconds(next.airAt)?.let { NextAiring(episode, it, null) }
            }
            actors = page.characters.orEmpty().mapNotNull { it.toActorData() }
            // Relations come first so the other seasons are easy to reach.
            recommendations = (page.relations.orEmpty().filter { it.anime?.format != "MUSIC" }.mapNotNull { it.anime } +
                    page.recommendations.orEmpty().mapNotNull { it.anime })
                .distinctBy { it.id }
                .mapNotNull { it.toSearchResponse() }
            if (subbed.isNotEmpty()) addEpisodes(DubStatus.Subbed, subbed)
            if (dubbed.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubbed)
            addMalId(detail.externalId("mal")?.toIntOrNull())
            addAniListId(detail.externalId("anilist")?.toIntOrNull())
            addKitsuId(detail.externalId("kitsu"))
            addImdbId(detail.externalId("imdb"))
            detail.trailerYoutubeId?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
        }
    }

    private fun Character.toActorData(): ActorData? {
        val characterName = name?.full ?: return null
        val voice = voiceActors.orEmpty().firstOrNull { it.language == "Japanese" } ?: voiceActors?.firstOrNull()
        return ActorData(
            Actor(characterName, imageUrl),
            role = when (role) {
                "MAIN" -> ActorRole.Main
                "SUPPORTING" -> ActorRole.Supporting
                else -> ActorRole.Background
            },
            voiceActor = voice?.name?.let { Actor(it, voice.imageUrl) },
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<LinkData>(data)
        // Stream URLs are tokenised and short-lived, so they are always fetched fresh.
        val sources = tryParseJson<Sources>(
            app.get("$mainUrl/api/sources?id=${link.id}&n=${link.episode}", headers = browserHeaders).text
        ) ?: return false

        // Soft subs first: same video as the hard-sub track, but with selectable subtitles.
        val tracks = if (link.dub) listOf("dub") else listOf("ssub", "sub")
        val providerOrder = sources.providerOrder.orEmpty()
        val emittedSubtitles = HashSet<String>()
        var found = false

        for (trackName in tracks) {
            val track = sources.tracks.orEmpty().firstOrNull { it.track == trackName } ?: continue
            val providers = track.providers.orEmpty()
                .sortedBy { providerOrder.indexOf(it.provider).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            for (provider in providers) {
                val providerName = provider.provider ?: continue
                val config = sources.config?.get(providerName)
                if (config?.visible == false) continue
                val proxied = config?.proxy?.isObject == true
                val rotate = config?.proxy?.get("rotate")?.asBoolean() == true
                // The site rewrites these providers' segment hosts client-side, which a link can't do;
                // servers that depend on it fail at the proxy, so they are checked before being offered.
                val needsSegmentRewrite = config?.hls?.get("segments")?.get("enabled")?.asBoolean() == true
                val label = providerName.replaceFirstChar { it.uppercase() }

                for (server in provider.servers.orEmpty()) {
                    val serverHeaders = server.headers.orEmpty()
                    val serverReferer = serverHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                    val streams = server.streams.orEmpty()
                    for (stream in streams) {
                        val url = stream.url ?: continue
                        val isHls = stream.format == "hls" || url.contains(".m3u8")
                        // Same rule as the site: providers flagged for the proxy only play through it
                        // (their CDNs reject direct segment requests even with the right Referer).
                        val viaProxy = proxied && isHls
                        val playUrl = if (viaProxy) {
                            proxy().url(url, serverReferer, rotate, "/pl.m3u8", link.id, link.episode)
                        } else url
                        if (viaProxy && needsSegmentRewrite && !isReachable(playUrl)) {
                            Log.w(name, "Skipping $providerName ${server.server}: proxy rejected the playlist")
                            continue
                        }
                        val quality = stream.quality ?: stream.resolution?.height?.let { "${it}p" }
                        // Only kiwi reports per-stream qualities, and its CDN intermittently 403s the proxy's
                        // uncached segment fetches. A rated quality would make the player prefer it over the
                        // steadier adaptive servers, so proxied streams keep the resolution in the name only.
                        val qualityValue = if (viaProxy) null else quality?.let { getQualityFromName(it) }
                        val serverName = server.server?.replace(serverHashPrefix, "")
                        // The player appends rated qualities itself.
                        val linkName = listOfNotNull(
                            "$name $label", serverName, quality.takeIf { qualityValue == null }, TRACK_LABELS[trackName]
                        ).joinToString(" · ")
                        callback(
                            newExtractorLink(
                                "$name $label",
                                linkName,
                                playUrl,
                                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = qualityValue ?: Qualities.Unknown.value
                                if (viaProxy) {
                                    this.referer = "$mainUrl/"
                                    this.headers = browserHeaders
                                } else {
                                    this.referer = serverReferer ?: ""
                                    this.headers = browserHeaders + serverHeaders
                                }
                            }
                        )
                        found = true
                    }
                    // Iframe-only servers; playable only if an installed extractor knows the host.
                    if (streams.isEmpty()) {
                        server.embed?.url?.let {
                            if (loadExtractor(it, "$mainUrl/", subtitleCallback, callback)) found = true
                        }
                    }
                }

                val subtitleHeaders = provider.servers?.firstOrNull()?.headers.orEmpty()
                val subtitleReferer = subtitleHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                for (subtitle in provider.subtitles.orEmpty()) {
                    val file = subtitle.file ?: continue
                    if (!emittedSubtitles.add(file)) continue
                    val language = subtitle.label ?: subtitle.language ?: "Unknown"
                    val subtitleUrl = if (proxied) {
                        proxy().url(file, subtitleReferer, rotate, "/sub.vtt", link.id, link.episode)
                    } else file
                    subtitleCallback(newSubtitleFile("$language [$label]", subtitleUrl) {
                        headers = if (proxied) browserHeaders else browserHeaders + subtitleHeaders
                    })
                }
            }
        }
        return found
    }

    /** Proxy hosts and key are build-time env in the HTML shell; read them so a redeploy doesn't break playback. */
    private suspend fun proxy(): Proxy {
        proxy?.let { return it }
        val html = runCatching { app.get("$mainUrl/", headers = browserHeaders).text }.getOrNull()
        fun env(key: String) = html?.let { Regex(""""$key"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        val hostA = env("PUBLIC_PROXY_A")
        val hostB = env("PUBLIC_PROXY_B")
        val key = env("PUBLIC_PROXY_OBF_KEY")
        return Proxy(
            listOf(hostA ?: DEFAULT_PROXY_A, hostB ?: DEFAULT_PROXY_B),
            (key ?: DEFAULT_PROXY_KEY).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        ).also { if (hostA != null && hostB != null && key != null) proxy = it }
    }

    private suspend fun isReachable(url: String): Boolean =
        runCatching { app.get(url, headers = browserHeaders, timeout = PROBE_TIMEOUT).isSuccessful }.getOrDefault(false)

    private suspend inline fun <reified T> fetchPage(route: String, query: String = ""): T? {
        var path = route.trimEnd('/') + "/__data.json" + if (query.isEmpty()) "" else "?$query"
        repeat(MAX_REDIRECTS) {
            when (val result = SvelteData.parse(app.get("$mainUrl$path", headers = browserHeaders).text)) {
                is SvelteData.Result.Page -> return mapper.treeToValue(result.data, T::class.java)
                is SvelteData.Result.Redirect -> {
                    val location = URI(result.location)
                    path = location.rawPath.trimEnd('/') + "/__data.json" +
                            (location.rawQuery?.let { "?$it" } ?: "")
                }
            }
        }
        return null
    }

    private fun infoUrl(id: String, title: Title?): String {
        val slug = (title?.romaji ?: title?.english).orEmpty().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }
        return "$mainUrl/info/$id/$slug"
    }

    private fun tvType(format: String?) = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "SPECIAL" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun parseIsoSeconds(value: String?): Long? = value?.let {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(it.take(19))?.time?.div(1000)
        }.getOrNull()
    }

    /** Miruro's HLS/subtitle proxy; it adds the upstream Referer itself and rewrites child playlist URIs. */
    class Proxy(private val hosts: List<String>, private val key: ByteArray) {
        /** `{host}{obf(url)}[~{obf(referer)}][~anon]{suffix}`, as built by the site's player. */
        fun url(target: String, referer: String?, rotate: Boolean, suffix: String, animeId: String, episode: Int): String {
            val tail = when {
                referer != null -> "~" + obfuscate(referer)
                rotate -> "~"
                else -> ""
            }
            return host(animeId, episode) + obfuscate(target) + tail + (if (rotate) "~anon" else "") + suffix
        }

        // The site spreads load with FNV-1a("{animeId}|{episode}") parity; both hosts serve everything.
        private fun host(animeId: String, episode: Int): String {
            var hash = FNV_OFFSET
            for (char in "$animeId|$episode") hash = (hash xor char.code) * FNV_PRIME
            return hosts[hash and 1]
        }

        private fun obfuscate(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).encode(bytes)
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 3
        private const val PROBE_TIMEOUT = 10L
        private const val DEFAULT_PROXY_A = "https://s1.keeply.top/"
        private const val DEFAULT_PROXY_B = "https://s2.keeply.top/"
        private const val DEFAULT_PROXY_KEY = "a54d389c18527d9fd3e7f0643e27edbe"
        private const val FNV_OFFSET = 0x811C9DC5.toInt()
        private const val FNV_PRIME = 16777619
        private val TRACK_LABELS = mapOf("ssub" to "Soft Sub", "sub" to "Hard Sub", "dub" to "Dub")
    }

    data class LinkData(val id: String, val episode: Int, val dub: Boolean)

    data class Title(val romaji: String? = null, val english: String? = null, val native: String? = null) {
        fun preferred() = english ?: romaji ?: native
    }

    data class EpisodeCounts(val raw: Int? = null, val sub: Int? = null, val dub: Int? = null) {
        fun subbed() = maxOf(raw ?: 0, sub ?: 0)
    }

    data class Anime(
        val id: String? = null,
        val title: Title? = null,
        val format: String? = null,
        @JsonProperty("cover_url") val coverUrl: String? = null,
        @JsonProperty("season_year") val seasonYear: Int? = null,
        @JsonProperty("average_score") val averageScore: Int? = null,
        @JsonProperty("episode_counts") val episodeCounts: EpisodeCounts? = null,
    )

    /** "Latest episode" rail entries use camelCase, unlike search items. */
    data class RailItem(
        val animeId: String? = null,
        val title: Title? = null,
        val format: String? = null,
        val coverUrl: String? = null,
        val seasonYear: Int? = null,
        val episodeCounts: EpisodeCounts? = null,
    )

    data class Rail(val latest: List<RailItem>? = null)

    data class Rails(val raw: Rail? = null, val dub: Rail? = null)

    data class Home(val rails: Rails? = null)

    data class Trending(val items: List<Anime>? = null)

    data class SearchPage(
        val items: List<Anime>? = null,
        val nextCursor: String? = null,
        val hasMore: Boolean? = null,
    )

    data class QuickSearch(val data: List<Anime>? = null)

    data class AlternateTitle(val value: String? = null)

    data class NextAiringInfo(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("air_at") val airAt: String? = null,
    )

    data class Detail(
        val id: String? = null,
        val title: Title? = null,
        @JsonProperty("alternate_titles") val alternateTitles: List<AlternateTitle>? = null,
        val format: String? = null,
        val status: String? = null,
        @JsonProperty("season_year") val seasonYear: Int? = null,
        @JsonProperty("episode_counts") val episodeCounts: EpisodeCounts? = null,
        @JsonProperty("episode_duration_minutes") val episodeDurationMinutes: Int? = null,
        @JsonProperty("next_airing") val nextAiring: NextAiringInfo? = null,
        @JsonProperty("average_score") val averageScore: Int? = null,
        @JsonProperty("cover_url") val coverUrl: String? = null,
        @JsonProperty("banner_url") val bannerUrl: String? = null,
        @JsonProperty("background_url") val backgroundUrl: String? = null,
        @JsonProperty("logo_url") val logoUrl: String? = null,
        val description: String? = null,
        @JsonProperty("trailer_youtube_id") val trailerYoutubeId: String? = null,
        val genres: List<String>? = null,
        @JsonProperty("external_ids") val externalIds: Map<String, List<String>>? = null,
    ) {
        fun externalId(key: String) = externalIds?.get(key)?.firstOrNull()
    }

    data class CharacterName(val full: String? = null)

    data class VoiceActor(
        val name: String? = null,
        val language: String? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
    )

    data class Character(
        val name: CharacterName? = null,
        @JsonProperty("image_url") val imageUrl: String? = null,
        val role: String? = null,
        @JsonProperty("voice_actors") val voiceActors: List<VoiceActor>? = null,
    )

    data class RelatedAnime(val anime: Anime? = null)

    data class EpisodeInfo(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        val title: String? = null,
        val synopsis: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
        @JsonProperty("aired_on") val airedOn: String? = null,
        @JsonProperty("duration_seconds") val durationSeconds: Int? = null,
    )

    data class InfoPage(
        val detail: Detail? = null,
        val characters: List<Character>? = null,
        val relations: List<RelatedAnime>? = null,
        val recommendations: List<RelatedAnime>? = null,
        val episodes: List<EpisodeInfo>? = null,
    )

    data class ProviderConfig(
        val visible: Boolean? = null,
        /** `false`, or `{rotate, segments}` when the provider must be played through the proxy. */
        val proxy: JsonNode? = null,
        /** `null`, or `{segments: {enabled, origin}, query: {enabled, params}}` playlist rewrites. */
        val hls: JsonNode? = null,
    )

    data class Embed(val url: String? = null)

    data class Resolution(val height: Int? = null)

    data class Stream(
        val url: String? = null,
        val format: String? = null,
        val quality: String? = null,
        val resolution: Resolution? = null,
    )

    data class Server(
        val server: String? = null,
        val headers: Map<String, String>? = null,
        val embed: Embed? = null,
        val streams: List<Stream>? = null,
    )

    data class Subtitle(
        val language: String? = null,
        val label: String? = null,
        val file: String? = null,
    )

    data class ProviderSources(
        val provider: String? = null,
        val servers: List<Server>? = null,
        val subtitles: List<Subtitle>? = null,
    )

    data class Track(val track: String? = null, val providers: List<ProviderSources>? = null)

    data class Sources(
        val providerOrder: List<String>? = null,
        val config: Map<String, ProviderConfig>? = null,
        val tracks: List<Track>? = null,
    )
}
