package com.nropvox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.nropvox.OmniStreamParser.SiteHit
import org.junit.Test

class OmniStreamParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** TMDB episode lists saved as `[{season, episode, air_date}]` from `tv/{id}?append_to_response=season/N`. */
    private fun show(name: String): List<OmniStreamParser.AirEpisode> =
        jacksonObjectMapper().readValue<List<Map<String, Any?>>>(text(name)).map {
            OmniStreamParser.AirEpisode(it["season"] as Int, it["episode"] as Int, it["air_date"] as String?)
        }

    private val jjk = show("tmdb-episodes-95479-jujutsu-kaisen.json")
    private val jjkAni = OmniStreamParser.candidates(text("anilist-jujutsu-kaisen-series.json"))
    private val jjkNames = listOf("Jujutsu Kaisen", "呪術廻戦")

    private fun target(id: Int, episode: Int) = OmniStreamParser.AniTarget(id, episode)

    @Test fun oneTmdbSeasonSplitsIntoAniListCours() {
        assertEquals(target(113415, 1), OmniStreamParser.pickSeries(jjk, 1, 1, jjkAni, jjkNames))
        assertEquals(target(113415, 24), OmniStreamParser.pickSeries(jjk, 1, 24, jjkAni, jjkNames))
        assertEquals(target(145064, 1), OmniStreamParser.pickSeries(jjk, 1, 25, jjkAni, jjkNames))
        assertEquals(target(145064, 23), OmniStreamParser.pickSeries(jjk, 1, 47, jjkAni, jjkNames))
    }

    @Test fun doublePremiereCountsBothEpisodes() {
        // JJK season 3 premiered with TMDB episodes 48 and 49 on the same day.
        assertEquals(target(172463, 1), OmniStreamParser.pickSeries(jjk, 1, 48, jjkAni, jjkNames))
        assertEquals(target(172463, 2), OmniStreamParser.pickSeries(jjk, 1, 49, jjkAni, jjkNames))
    }

    @Test fun splitSeasonsAndFinalParts() {
        val aot = show("tmdb-episodes-1429-attack-on-titan.json")
        val ani = OmniStreamParser.candidates(text("anilist-attack-on-titan-series.json"))
        val names = listOf("Attack on Titan", "進撃の巨人")
        assertEquals(target(16498, 25), OmniStreamParser.pickSeries(aot, 1, 25, ani, names))
        // Junior High (TV, 2015) is a spin-off that aired between seasons 1 and 2; it must never be picked.
        assertEquals(target(20958, 1), OmniStreamParser.pickSeries(aot, 2, 1, ani, names))
        assertEquals(target(99147, 12), OmniStreamParser.pickSeries(aot, 3, 12, ani, names))
        assertEquals(target(104578, 1), OmniStreamParser.pickSeries(aot, 3, 13, ani, names))
        assertEquals(target(110277, 16), OmniStreamParser.pickSeries(aot, 4, 16, ani, names))
        assertEquals(target(131681, 1), OmniStreamParser.pickSeries(aot, 4, 17, ani, names))
    }

    @Test fun spinOffMinisAreNotEpisodes() {
        val frieren = show("tmdb-episodes-209867-frieren.json")
        val ani = OmniStreamParser.candidates(text("anilist-frieren-series.json"))
        val names = listOf("Frieren: Beyond Journey's End", "葬送のフリーレン")
        // The chibi ONAs share the native title and start mid-season; the main entries must still win.
        assertEquals(target(154587, 6), OmniStreamParser.pickSeries(frieren, 1, 6, ani, names))
        assertEquals(target(154587, 28), OmniStreamParser.pickSeries(frieren, 1, 28, ani, names))
        assertEquals(target(182255, 1), OmniStreamParser.pickSeries(frieren, 1, 29, ani, names))
    }

    @Test fun noGuessWhenNothingFits() {
        assertNull(OmniStreamParser.pickSeries(jjk, 1, 1, jjkAni, listOf("Some Other Show")))
        assertNull(OmniStreamParser.pickSeries(jjk, 1, 99, jjkAni, jjkNames))
        val unaired = jjk + OmniStreamParser.AirEpisode(1, 60, null)
        assertNull(OmniStreamParser.pickSeries(unaired, 1, 60, jjkAni, jjkNames))
        assertNull(OmniStreamParser.pickSeries(jjk, 1, 1, emptyList(), jjkNames))
    }

    @Test fun moviesMatchByReleaseDate() {
        val ani = OmniStreamParser.candidates(text("anilist-jujutsu-kaisen-0-movie.json"))
        val names = listOf("Jujutsu Kaisen 0", "劇場版 呪術廻戦 0")
        assertEquals(target(131573, 1), OmniStreamParser.pickMovie("2021-12-24", ani, names))
        assertNull(OmniStreamParser.pickMovie("2016-12-24", ani, names))
        assertNull(OmniStreamParser.pickMovie(null, ani, names))
    }

    @Test fun titlesMatchByPrefixIgnoringPunctuation() {
        assertTrue(OmniStreamParser.titleMatches(listOf("Frieren: Beyond Journey’s End Season 2"), listOf("Frieren: Beyond Journey's End")))
        assertTrue(OmniStreamParser.titleMatches(listOf("JUJUTSU KAISEN"), listOf("Jujutsu Kaisen")))
        assertFalse(OmniStreamParser.titleMatches(listOf("Koisuru ONE PIECE"), listOf("One Piece")))
    }

    @Test fun epochDays() {
        assertEquals(0, OmniStreamParser.epochDay("1970-01-01"))
        assertEquals(19723, OmniStreamParser.epochDay("2024-01-01"))
        assertEquals(19782, OmniStreamParser.epochDay("2024-02-29"))
        assertNull(OmniStreamParser.epochDay("2024"))
    }

    @Test fun kinds() {
        assertEquals(OmniStreamParser.KIND_ANIME, OmniStreamParser.kind(listOf(16, 10759), listOf("JP"), "ja"))
        assertEquals(OmniStreamParser.KIND_ANIME, OmniStreamParser.kind(listOf(16), null, "ja"))
        assertEquals(OmniStreamParser.KIND_GENERAL, OmniStreamParser.kind(listOf(16), listOf("US"), "en"))
        assertEquals(OmniStreamParser.KIND_ASIAN, OmniStreamParser.kind(listOf(18), listOf("KR"), "ko"))
        assertEquals(OmniStreamParser.KIND_ASIAN, OmniStreamParser.kind(listOf(18), null, "zh"))
        assertEquals(OmniStreamParser.KIND_GENERAL, OmniStreamParser.kind(listOf(18, 80), listOf("US"), "en"))
        assertEquals(OmniStreamParser.KIND_GENERAL, OmniStreamParser.kind(null, null, null))
    }

    @Test fun titleRefs() {
        assertEquals("tv" to 1396, OmniStreamParser.titleRef("https://www.themoviedb.org/tv/1396"))
        assertEquals("movie" to 550, OmniStreamParser.titleRef("https://www.themoviedb.org/movie/550"))
        assertNull(OmniStreamParser.titleRef("https://www.themoviedb.org/person/287"))
    }

    @Test fun playlistChecks() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\n720/index.m3u8\n"
        assertTrue(OmniStreamParser.isPlaylist("﻿$master"))
        assertFalse(OmniStreamParser.isPlaylist("<!DOCTYPE html><html>"))
        assertEquals("https://cdn.example/a/720/index.m3u8", OmniStreamParser.firstVariant(master, "https://cdn.example/a/master.m3u8"))
        assertNull(OmniStreamParser.firstVariant("#EXTM3U\n#EXTINF:6,\nseg0.ts\n", "https://cdn.example/a/index.m3u8"))
        assertTrue(OmniStreamParser.isDrm("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\""))
        assertTrue(OmniStreamParser.isDrm("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"urn:uuid:edef8ba9\""))
        assertFalse(OmniStreamParser.isDrm("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""))
        assertFalse(OmniStreamParser.isDrm(master))
    }

    @Test fun masterPlaylistQuality() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\n480.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x800,CODECS=\"avc1.640028\"\n1080.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\n720.m3u8\n"
        assertEquals(1080, OmniStreamParser.playlistQuality(master))
        assertEquals(2160, OmniStreamParser.playlistQuality("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=3840x1608\n4k.m3u8\n"))
        assertEquals(720, OmniStreamParser.playlistQuality("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x536\n720.m3u8\n"))
        assertNull(OmniStreamParser.playlistQuality("#EXTM3U\n#EXTINF:6,\nseg0.ts\n"))
    }

    // --- Title-searched sites ---

    @Test fun vegamoviesHitsMatchByImdbId() {
        val search = jacksonObjectMapper().readValue<VgSearch>(text("vegamovies-search-oppenheimer.json"))
        val hits = search.hits.orEmpty().mapNotNull { it.document }.map { doc ->
            SiteHit(doc.permalink!!, VegamoviesParser.cleanTitle(doc.title!!), VegamoviesParser.year(doc.title!!),
                doc.imdbId, VegamoviesParser.isSeries(doc.title!!), doc.title!!)
        }
        assertEquals(listOf("Oppenheimer", "Oppenheimer"), hits.map { it.title })
        fun matches(names: List<String>, year: Int, imdbId: String?, series: Boolean) =
            OmniStreamParser.matches(hits, names, listOf(year), imdbId, series, null)
        // Both posts (dual audio and English) are the film.
        assertEquals(2, matches(listOf("Oppenheimer"), 2023, "tt15398776", false).size)
        // Another title's IMDb id rules them out, whatever the name.
        assertTrue(matches(listOf("Oppenheimer"), 2023, "tt0000001", false).isEmpty())
        // Without an id, the name and the year decide.
        assertEquals(2, matches(listOf("Oppenheimer"), 2024, null, false).size)
        assertTrue(matches(listOf("Oppenheimer"), 2026, null, false).isEmpty())
        assertTrue(matches(listOf("Oppenheimer"), 2023, "tt15398776", true).isEmpty())
    }

    @Test fun moviesModCardsMatchByNameYearAndSeason() {
        val doc = MoviesModParser.body(text("moviesmod-search-lupin.html"), "https://moviesmod.meme/")
        val hits = MoviesModParser.cards(doc).map { card ->
            SiteHit(card.url, MoviesModParser.cleanTitle(card.title), MoviesModParser.year(card.title),
                series = MoviesModParser.isSeries(card.title), label = card.title)
        }
        val show = OmniStreamParser.matches(hits, listOf("Lupin"), listOf(2021), null, series = true, season = 3)
        assertEquals(listOf("https://moviesmod.meme/download-lupin-season-1-3-hindi-480p-720p-1080p/"), show)
        // Season 5 is not in the "Season 1-4" post.
        assertTrue(OmniStreamParser.matches(hits, listOf("Lupin"), listOf(2021), null, series = true, season = 5).isEmpty())
        val film = OmniStreamParser.matches(hits, listOf("Lupin III: The First", "ルパン三世 THE FIRST"), listOf(2019), null, false, null)
        assertEquals(listOf("https://moviesmod.meme/download-lupin-iii-the-first-japanese-480p-720p-1080p/"), film)
    }

    @Test fun fourKHDHubCardsMatchByNameYearAndKind() {
        val doc = org.jsoup.Jsoup.parse(text("4khdhub-search-batman.html"), "https://4khdhub.one/")
        val hits = FourKHDHubParser.cards(doc, "https://4khdhub.one").map { SiteHit(it.url, it.title, it.year, series = it.series) }
        assertEquals(listOf("https://4khdhub.one/batman-movie-908/"), OmniStreamParser.matches(hits, listOf("Batman"), listOf(1989), null, false, null))
        assertEquals(listOf("https://4khdhub.one/batman-robin-movie-911/"),
            OmniStreamParser.matches(hits, listOf("Batman & Robin"), listOf(1997), null, false, null))
        assertEquals(listOf("https://4khdhub.one/batman-caped-crusader-series-939/"),
            OmniStreamParser.matches(hits, listOf("Batman: Caped Crusader"), listOf(2024, 2026), null, true, null))
        assertTrue(OmniStreamParser.matches(hits, listOf("Batman: Caped Crusader"), listOf(2024), null, false, null).isEmpty())
    }

    @Test fun kissKHCardsMatchSeasonNamesAndRemakeYears() {
        val cards = jacksonObjectMapper().readValue<List<KissCard>>(text("kisskh-search-love.json"))
        val hits = cards.map { card -> OmniStreamParser.splitYear(card.title!!).let { (title, year) -> SiteHit(card.id!!, title, year) } }
        fun match(name: String, season: Int?, vararg years: Int) =
            OmniStreamParser.matches(hits, OmniStreamParser.seasonNames(listOf(name), season), years.toList(), null, season != null, null)
        assertEquals(listOf(11122), match("Poisonous Love", 1, 2025))
        // The year after a remade name tells it apart from another show of that name.
        assertTrue(match("Poisonous Love", 1, 2019).isEmpty())
        assertEquals(listOf(11829), match("Tide of Love", 1, 2025))
        assertEquals(listOf(11627), match("EXchange 4 - Transit Love", 4, 2025))
        assertEquals(listOf(10606), match("Speed and Love", 1, 2025))
    }

    @Test fun seasonMentions() {
        assertEquals(0, OmniStreamParser.seasonRank("Download Lupin (Season 1-4) Multi Audio", 2))
        assertEquals(0, OmniStreamParser.seasonRank("Download Lupin S01-S04 Hindi Dubbed 480p", 3))
        assertEquals(0, OmniStreamParser.seasonRank("Download Hercai (Season 1) [E90 Added] Dual Audio", 1))
        assertEquals(2, OmniStreamParser.seasonRank("Download Hercai (Season 1) [E90 Added] Dual Audio", 2))
        assertEquals(0, OmniStreamParser.seasonRank("Download The Boys (Season 4) [S04E08 Added] {Hindi-English}", 4))
        assertEquals(0, OmniStreamParser.seasonRank("Stranger Things Season 4 – Part 2 {Hindi-English} 1080p", 4))
        assertEquals(1, OmniStreamParser.seasonRank("Download Runner (2026) Dual Audio (English-Hindi) WEB-DL", 1))
    }

    @Test fun titleNames() {
        assertTrue(OmniStreamParser.sameTitle("Pokémon: The First Movie", listOf("Pokemon - The First Movie")))
        assertTrue(OmniStreamParser.sameTitle("Batman and Robin", listOf("Batman & Robin")))
        assertFalse(OmniStreamParser.sameTitle("Batman vs Robin", listOf("Batman & Robin")))
        assertTrue(OmniStreamParser.sameTitle("눈물의 여왕", listOf("Queen of Tears", "눈물의 여왕")))
        assertFalse(OmniStreamParser.sameTitle("!!!", listOf("???")))
        assertEquals(listOf("Lovely Runner"), OmniStreamParser.seasonNames(listOf("Lovely Runner"), null))
        assertEquals(listOf("Taxi Driver", "Taxi Driver Season 1"), OmniStreamParser.seasonNames(listOf("Taxi Driver"), 1))
        assertEquals(listOf("Taxi Driver Season 3", "Taxi Driver 3"), OmniStreamParser.seasonNames(listOf("Taxi Driver"), 3))
        assertEquals("Poisonous Love" to 2025, OmniStreamParser.splitYear("Poisonous Love (2025)"))
        assertEquals("Love (Uncut Ver.)" to null, OmniStreamParser.splitYear("Love (Uncut Ver.)"))
        assertTrue(OmniStreamParser.yearMatches(null, listOf(2020)))
        assertTrue(OmniStreamParser.yearMatches(2021, listOf(2020)))
        assertFalse(OmniStreamParser.yearMatches(2022, listOf(2020)))
    }

    @Test fun unairedTitlesGetNoSources() {
        val today = OmniStreamParser.epochDay("2026-10-30")!!
        assertTrue(OmniStreamParser.aired(2024, "2024-12-08", today))
        assertTrue(OmniStreamParser.aired(2026, "2026-10-31", today))
        assertTrue(OmniStreamParser.aired(2019, null, today))
        assertFalse(OmniStreamParser.aired(2026, "2026-11-06", today))
        // TMDB 295656, an announced C-drama also called "Blossom": no dates anywhere.
        assertFalse(OmniStreamParser.aired(null, null, today))
    }

    @Test fun finishPolicy() {
        // Too few links, or all from one source: keep waiting.
        assertNull(OmniStreamParser.grace(3, 3, 2160, 2_000))
        assertNull(OmniStreamParser.grace(5, 1, 2160, 2_000))
        // Enough backups: the best link sets how long stragglers may still come in.
        assertEquals(1_000L, OmniStreamParser.grace(4, 2, 2160, 2_000))
        assertEquals(2_000L, OmniStreamParser.grace(4, 2, 1080, 2_000))
        assertEquals(2_000L, OmniStreamParser.grace(8, 1, 1080, 2_000))
        // Only SD or Unknown links: the slower HD sources get until the soft deadline, then anything will do.
        assertNull(OmniStreamParser.grace(12, 3, 720, 5_999))
        assertEquals(500L, OmniStreamParser.grace(1, 1, 400, OmniStreamParser.SOFT_MS))
        assertNull(OmniStreamParser.grace(0, 0, 0, 11_000))
    }

    // --- Stremio addons (answers saved 2026-10-31) ---

    @Test fun stremioIdsNameTheEpisode() {
        assertEquals("tt15398776", OmniStreamParser.stremioId("tt15398776", null, null))
        assertEquals("tt0903747:1:1", OmniStreamParser.stremioId("tt0903747", 1, 1))
    }

    @Test fun vadapavStreamsCarryFileSizeAndHeight() {
        val movie = OmniStreamParser.stremioStreams(text("vadapav-oppenheimer.json"))
        assertEquals(3, movie.size)
        assertTrue(movie.all { it.height == 1080 && it.url.endsWith("&download=1") && it.headers.isEmpty() })
        assertEquals("Oppenheimer.2023.REPACK.1080p.BluRay.DD+.5.1.X265-Ralphy.mkv · 6.45 GB", movie[0].label)

        val episode = OmniStreamParser.stremioStreams(text("vadapav-breaking-bad-1x1.json")).single()
        assertEquals("Breaking.Bad.S01E01.Pilot.1080p.BluRay.10bit.EAC3.5.1.x265-iVy.mkv · 2.97 GB", episode.label)
    }

    @Test fun stremioStreamsSkipTorrentsAndKeepProxyHeaders() {
        val json = """{"streams":[
            {"infoHash":"abc","name":"Torrent 1080p","title":"x"},
            {"url":"https://cdn.example/v.m3u8","name":"Addon\n4K","description":"Some.Film.HDR",
             "behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://player.example/"}}}},
            {"url":"magnet:?xt=urn:btih:abc","name":"x"}
        ]}"""
        val stream = OmniStreamParser.stremioStreams(json).single()
        assertEquals(2160, stream.height)
        assertEquals("Some.Film.HDR", stream.label)
        assertEquals(mapOf("Referer" to "https://player.example/"), stream.headers)
        assertTrue(OmniStreamParser.stremioStreams("""{"streams":[]}""").isEmpty())
        assertTrue(OmniStreamParser.stremioStreams("<html>").isEmpty())
    }

    @Test fun openSubtitlesKeepsAFewPerLanguage() {
        val subtitles = OmniStreamParser.stremioSubtitles(text("opensubtitles-oppenheimer.json"), 3)
        // 96 files: 14 languages with 3 or more, 6 with 2, 12 with 1.
        assertEquals(14 * 3 + 6 * 2 + 12, subtitles.size)
        assertEquals(3, subtitles.count { it.lang == "eng" })
        assertEquals(1, subtitles.count { it.lang == "spn" })
        assertTrue(subtitles.all { it.url.startsWith("https://subs5.strem.io/") })
        assertEquals("Spanish (Latin America)", OmniStreamParser.openSubtitlesLanguage("spl"))
        assertNull(OmniStreamParser.openSubtitlesLanguage("eng"))
    }

    // --- Site domains ---

    @Test fun domainsListNamesEachSite() {
        val json = text("domains.json")
        assertEquals("https://new1.vegamovies.lease", OmniStreamParser.domain(json, "vegamovies"))
        assertEquals("https://moviesmod.tw", OmniStreamParser.domain(json, "moviesmod"))
        assertEquals("https://4khdhub.one", OmniStreamParser.domain(json, "4khdhub"))
        assertEquals("https://new10.hdhub4u.cl", OmniStreamParser.domain(json, "hdhub4u"))
        assertNull(OmniStreamParser.domain(json, "nosuchsite"))
    }

    @Test fun domainsListIgnoresAnythingButAnOrigin() {
        val json = """{"a":"https://Site.Example/","b":"http://site.example","c":"https://site.example/path","d":"javascript:x","e":5}"""
        assertEquals("https://site.example", OmniStreamParser.domain(json, "a"))
        assertNull(OmniStreamParser.domain(json, "b"))
        assertNull(OmniStreamParser.domain(json, "c"))
        assertNull(OmniStreamParser.domain(json, "d"))
        assertNull(OmniStreamParser.domain(json, "e"))
        assertNull(OmniStreamParser.domain("not json", "a"))
    }

    // --- AniList → TMDB (OmniAnime) ---

    private val frierenZip = OmniStreamParser.aniZip(text("anizip-154587-frieren.json"))!!
    private val frierenS2Zip = OmniStreamParser.aniZip(text("anizip-182255-frieren-s2.json"))!!
    private val jjkS2Zip = OmniStreamParser.aniZip(text("anizip-145064-jujutsu-kaisen-s2.json"))!!
    private val frieren = show("tmdb-episodes-209867-frieren.json")

    private fun air(season: Int, episode: Int, show: List<OmniStreamParser.AirEpisode>) =
        show.first { it.season == season && it.episode == episode }

    @Test fun aniZipIdsAndEpisodes() {
        assertEquals(209867, frierenZip.tmdbId)
        assertEquals("tt22248376", frierenZip.imdbId)
        assertEquals("The Journey's End", frierenZip.episodes[1]?.title)
        assertEquals("2023-09-29", frierenZip.episodes[1]?.airDate)
        assertTrue(frierenZip.episodes[1]?.image!!.startsWith("https://"))
        assertEquals((1..28).toSet(), frierenZip.episodes.keys)
        // New seasons often have no TMDB id yet; OmniAnime then searches TMDB.
        assertNull(frierenS2Zip.tmdbId)
        assertEquals(95479, jjkS2Zip.tmdbId)
        assertNull(OmniStreamParser.aniZip("not json"))
    }

    private fun tmdbEpisode(show: List<OmniStreamParser.AirEpisode>, start: String, episode: Int, zip: OmniStreamParser.AniZip) =
        OmniStreamParser.tmdbEpisode(show, start, episode, zip.episodes[episode]?.airDate)

    @Test fun aniListCourMapsIntoOneTmdbSeason() {
        // JJK season 2 is TMDB 1x25-1x47.
        assertEquals(air(1, 25, jjk), tmdbEpisode(jjk, "2023-07-06", 1, jjkS2Zip))
        assertEquals(air(1, 47, jjk), tmdbEpisode(jjk, "2023-07-06", 23, jjkS2Zip))
        // Frieren season 2 continues TMDB season 1 at episode 29.
        assertEquals(air(1, 29, frieren), tmdbEpisode(frieren, "2026-01-16", 1, frierenS2Zip))
        assertEquals(air(1, 31, frieren), tmdbEpisode(frieren, "2026-01-16", 3, frierenS2Zip))
    }

    @Test fun premiereOfSeveralEpisodesKeepsOrder() {
        // Frieren premiered with four episodes on one day.
        (1..5).forEach { assertEquals(air(1, it, frieren), tmdbEpisode(frieren, "2023-09-29", it, frierenZip)) }
    }

    @Test fun anotherShowsIdGivesNoEpisode() {
        // JJK aired nothing around Frieren's first premiere. (Weekly shows of one season do share dates, which is why
        // OmniAnime also checks the name of a show it found by search.)
        assertNull(OmniStreamParser.tmdbEpisode(jjk, "2024-03-29", 1, null))
        assertNull(OmniStreamParser.tmdbEpisode(frieren, null, 1, null))
    }

    @Test fun airDateOverridesAnOffOrdinal() {
        // TMDB counts a recap AniList doesn't: the ordinal lands a week early, the air date finds the right one.
        val show = listOf(
            OmniStreamParser.AirEpisode(1, 1, "2024-01-05"),
            OmniStreamParser.AirEpisode(1, 2, "2024-01-12"),
            OmniStreamParser.AirEpisode(1, 3, "2024-01-19"),
            OmniStreamParser.AirEpisode(1, 4, "2024-01-26"),
        )
        assertEquals(show[3], OmniStreamParser.tmdbEpisode(show, "2024-01-05", 3, "2024-01-26"))
        assertEquals(show[2], OmniStreamParser.tmdbEpisode(show, "2024-01-05", 3, null))
        assertNull(OmniStreamParser.tmdbEpisode(show, "2024-01-05", 3, "2024-03-01"))
    }

    @Test fun baseTitlesDropSeasonSuffixes() {
        assertEquals(listOf("Sousou no Frieren"), OmniStreamParser.baseTitles(listOf("Sousou no Frieren 2nd Season")))
        assertEquals(listOf("Frieren: Beyond Journey's End"), OmniStreamParser.baseTitles(listOf("Frieren: Beyond Journey's End Season 2")))
        assertEquals(listOf("Shingeki no Kyojin"), OmniStreamParser.baseTitles(listOf("Shingeki no Kyojin: The Final Season Part 2")))
        assertEquals(listOf("Overlord"), OmniStreamParser.baseTitles(listOf("Overlord II", "OVERLORD 2")))
        assertEquals(listOf("Hawaii Five"), OmniStreamParser.baseTitles(listOf("Hawaii Five")))
    }

    @Test fun airedEpisodeCount() {
        val today = OmniStreamParser.epochDay("2023-10-10")!!
        assertEquals(7, OmniStreamParser.airedEpisodes("RELEASING", 12, 8, emptyMap(), today))
        assertEquals(0, OmniStreamParser.airedEpisodes("NOT_YET_RELEASED", 12, null, emptyMap(), today))
        assertEquals(28, OmniStreamParser.airedEpisodes("FINISHED", 28, null, frierenZip.episodes, today))
        // Releasing with no count and no schedule: what ani.zip dates up to today (episodes 1-5 by 2023-10-10).
        assertEquals(5, OmniStreamParser.airedEpisodes("RELEASING", null, null, frierenZip.episodes, today))
    }

    @Test fun aniListDatesAndFilmReleases() {
        assertEquals("2023-09-29", OmniStreamParser.isoDate(2023, 9, 29))
        assertEquals("2026-01-01", OmniStreamParser.isoDate(2026, 1, null))
        assertNull(OmniStreamParser.isoDate(2026, null, null))
        assertTrue(OmniStreamParser.sameRelease("2022-11-11", "2023-04-14"))
        assertFalse(OmniStreamParser.sameRelease("2016-08-26", "2022-11-11"))
        assertFalse(OmniStreamParser.sameRelease(null, "2022-11-11"))
    }
}
