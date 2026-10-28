package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages saved from animeflix.my, episodes.animeflix.my and driveseed.org on 2026-10-29 (src/test/resources). */
class AnimeFlixParserTest {
    private val base = "https://animeflix.my"

    private fun text(name: String): String = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun doc(name: String, uri: String = base) = AnimeFlixParser.body(text(name), uri)

    @Test fun listingUrls() {
        assertEquals("https://animeflix.my/", AnimeFlixParser.pageUrl(base, "", 1))
        assertEquals("https://animeflix.my/page/3/", AnimeFlixParser.pageUrl(base, "", 3))
        assertEquals("https://animeflix.my/category/movies/page/2/", AnimeFlixParser.pageUrl(base, "category/movies/", 2))
        assertEquals("https://animeflix.my/?af_status=Completed", AnimeFlixParser.pageUrl(base, "?af_status=Completed", 1))
        assertEquals("https://animeflix.my/page/2/?af_status=Completed", AnimeFlixParser.pageUrl(base, "?af_status=Completed", 2))
        assertEquals("https://animeflix.my/search/one%20piece/", AnimeFlixParser.searchUrl(base, " one piece ", 1))
        assertEquals("https://animeflix.my/search/one%20piece/page/2/", AnimeFlixParser.searchUrl(base, "one piece", 2))
    }

    @Test fun homeGridSkipsHeroAndBatchRail() {
        val home = doc("home.html")
        val cards = AnimeFlixParser.cards(home)
        assertEquals(10, cards.size)
        val first = cards.first()
        assertEquals("Dragon Ball Super: Beerus", first.title)
        assertEquals("https://animeflix.my/download-dragon-ball-super-beerus-2026-eng-subbed/", first.url)
        assertTrue(first.poster!!.startsWith("https://animeflix.my/wp-content/uploads/2026/10/"))
        assertFalse(first.movie)
        assertTrue(AnimeFlixParser.hasNext(home))
    }

    @Test fun searchCardsMarkMovies() {
        val cards = AnimeFlixParser.cards(doc("search-naruto.html"))
        assertEquals(9, cards.size)
        assertEquals("Naruto: Shippuden", cards.first().title)
        val movies = AnimeFlixParser.cards(
            AnimeFlixParser.body(
                """<div class="grid" data-grid><a class="card" href="https://animeflix.my/download-one-piece-film-red/" title="Download One Piece Film: Red 2022 (Hindi-Eng-Japanese) HDTS">
                <div class="poster"><img src="https://animeflix.my/wp-content/uploads/2022/11/red.jpg"></div>
                <span class="card-t clamp2">One Piece Film: Red</span><span class="card-m"> Movie · 2022 <span class="rating">7.2</span></span></a></div>""",
                base
            )
        )
        assertTrue(movies.single().movie)
    }

    @Test fun seriesDetailsAndQualities() {
        val page = doc("detail-series.html")
        val details = AnimeFlixParser.details(page)
        assertEquals("I'm Dating a Dark Summoner", details.title)
        assertEquals(listOf("Comedy", "Ecchi", "Fantasy", "Romance"), details.genres)
        assertEquals(9.2, details.rating!!, 0.001)
        assertEquals("Ongoing", details.info["Status"])
        assertEquals(2026, AnimeFlixParser.leadingInt(details.info["Released"]))
        assertTrue(details.plot!!.startsWith("Amona’s a dark summoner"))
        assertEquals("https://animeflix.my/wp-content/uploads/2026/10/i-m-dating-a-dark-summoner-season-1-animeflix-my.jpg", details.poster)

        val seasons = AnimeFlixParser.seasons(page, AnimeFlixParser.leadingInt(details.info["Season"]) ?: 1)
        assertEquals(1, seasons.size)
        assertEquals(1, seasons[0].season)
        assertEquals(
            listOf(
                AfQuality("720p", "100MB", "https://episodes.animeflix.my/archives/10908"),
                AfQuality("1080p", "200MB", "https://episodes.animeflix.my/archives/10909"),
            ),
            seasons[0].qualities
        )
        assertEquals(5, AnimeFlixParser.recommendations(page).size)
    }

    @Test fun multiSeasonPostGroupsCardsBySeason() {
        val page = doc("detail-multiseason.html")
        val details = AnimeFlixParser.details(page)
        assertEquals(1, AnimeFlixParser.leadingInt(details.info["Season"]))  // "1-2"
        val seasons = AnimeFlixParser.seasons(page, 1)
        assertEquals(listOf(1, 2), seasons.map { it.season })
        val s2 = seasons[1].qualities
        assertEquals(listOf("720p", "720p", "1080p"), s2.map { it.quality })
        assertEquals("720p · 10bit · 180MB", AnimeFlixParser.label(s2[0]))
        assertEquals("720p · x264 · 285MB", AnimeFlixParser.label(s2[1]))
        assertEquals("https://episodes.animeflix.my/archives/9747", s2[2].url)
    }

    @Test fun movieDetails() {
        val page = doc("detail-movie.html")
        val details = AnimeFlixParser.details(page)
        assertEquals("1", details.info["Episodes"])
        assertNull(details.info["Season"])
        val seasons = AnimeFlixParser.seasons(page, 1)
        assertEquals("https://episodes.animeflix.my/archives/9132", seasons.single().qualities.single().url)
    }

    @Test fun episodeListPages() {
        val list = AnimeFlixParser.entries(doc("episodes-list.html", "https://episodes.animeflix.my/archives/10908"))
        assertEquals(listOf(1, 2, 3, 4), list.map { it.episode })
        assertTrue(list.all { it.url.startsWith("https://episodes.animeflix.my/getlink/") })

        val old = AnimeFlixParser.entries(doc("episodes-bracketed.html", "https://episodes.animeflix.my/archives/6314"))
        assertEquals("Episode 1", old.first().label)
        assertEquals((1..43).toList(), old.map { it.episode })

        val movie = AnimeFlixParser.entries(doc("episodes-movie.html", "https://episodes.animeflix.my/archives/9132"))
        assertEquals(listOf("Link for – 1036p", "Link for – 690p"), movie.map { it.label })
        assertTrue(movie.all { it.episode == null })
    }

    @Test fun driveSeedPages() {
        assertEquals("/file/yLLBedicNyc74jBZ7uAJ", AnimeFlixParser.redirect(text("driveseed-r.html")))
        val file = AnimeFlixParser.driveFile(doc("driveseed-file.html", "https://driveseed.org/file/yLLBedicNyc74jBZ7uAJ"))
        assertEquals("AnimeFlix.my_Dark_Summoner_to_Dekiteiru_EP_01_720p_WebRip.mp4", file.name)
        assertTrue(file.cloud!!.startsWith("https://pub-2fbc508ae8a44ef5b8b74ff2d026eb97.r2.dev/"))
        assertTrue(file.instant!!.startsWith("https://cdn.video-gen.xyz/"))
        assertNull(file.direct)
        assertEquals(60.43, file.sizeMb!!, 0.001)
        assertEquals(1228.8, AnimeFlixParser.sizeMb("1.2GB")!!, 0.001)
        assertEquals(720, AnimeFlixParser.quality(file.name!!))
        assertEquals(
            "https://video-downloads.googleusercontent.com/ADGPM2m",
            AnimeFlixParser.instantTarget("https://video-seed.dev/?url=https://video-downloads.googleusercontent.com/ADGPM2m")
        )
    }

    @Test fun cardTitleFallback() {
        assertEquals("Tomb Raider King", AnimeFlixParser.cleanTitle("Download Tomb Raider King Season 1 [All Episodes Added] (2026) Eng Subbed || 720p [100MB]"))
        assertEquals("Dragon Ball Super: Beerus", AnimeFlixParser.cleanTitle("Download Dragon Ball Super: Beerus [E03 Added] (2026) Eng Subbed"))
    }
}
