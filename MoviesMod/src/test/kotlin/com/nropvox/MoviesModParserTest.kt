package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snippets trimmed from pages saved on 2026-10-28 (moviesmod.meme-recon/samples and raw). */
class MoviesModParserTest {
    private val base = "https://moviesmod.meme"

    @Test fun cardTitlesLoseTheSeoTail() {
        assertEquals("Runner", MoviesModParser.cleanTitle("Download Runner (2026) Dual Audio (English-Hindi) WEB-DL ESubs 480p [320MB] || 720p [890MB] || 1080p [2.1GB]"))
        assertEquals("Lupin", MoviesModParser.cleanTitle("Download Lupin (Season 1-4) Multi Audio {Hindi-English-French} WeB-DL 480p [170MB] || 720p [290MB] || 1080p [1GB]"))
        assertEquals("VisionQuest", MoviesModParser.cleanTitle("Download VisionQuest (Season 1) [E06 Added] Dual Audio (English-Hindi) WEB-DL MSubs 480p [160MB]"))
        assertEquals("Mum, I’m Alien Pregnant", MoviesModParser.cleanTitle("Download Mum, I’m Alien Pregnant (2026) (English Audio) WEB-DL ESubs 480p"))
        assertEquals("The Little Rascals Save the Day", MoviesModParser.cleanTitle("Dowload The Little Rascals Save the Day (2014) English BluRay"))
        assertEquals(2026, MoviesModParser.year("Download Runner (2026) Dual Audio"))
        assertNull(MoviesModParser.year("Download Lupin (Season 1-4) Multi Audio"))
        assertTrue(MoviesModParser.isSeries("Download Hercai (Season 1) [E90 Added] Dual Audio"))
        assertFalse(MoviesModParser.isSeries("Download Runner (2026) Dual Audio (English-Hindi) WEB-DL"))
    }

    @Test fun cardsAndPaging() {
        val doc = MoviesModParser.body(
            """<article id="post-28630" class="latestPost excerpt">
            <a href="https://moviesmod.meme/download-lupin-season-1-3-hindi-480p-720p-1080p/" title="Download Lupin (Season 1-4) Multi Audio" class="post-image post-image-left">
            <div class="featured-thumbnail"><img width="200" height="300" src="https://moviesmod.meme/wp-content/uploads/2023/10/Download-Lupin.jpg"></div></a>
            <header><h2 class="title front-view-title"><a href="https://moviesmod.meme/download-lupin-season-1-3-hindi-480p-720p-1080p/">Download Lupin (Season 1-4) Multi Audio</a></h2></header>
            </article><nav><a class="next page-numbers" href="https://moviesmod.meme/page/2/">Next</a></nav>""",
            base
        )
        val card = MoviesModParser.cards(doc).single()
        assertEquals("https://moviesmod.meme/download-lupin-season-1-3-hindi-480p-720p-1080p/", card.url)
        assertEquals("https://moviesmod.meme/wp-content/uploads/2023/10/Download-Lupin.jpg", card.poster)
        assertTrue(MoviesModParser.hasNext(doc))
    }

    @Test fun movieDetailsAndQualityGroups() {
        val doc = MoviesModParser.body(
            """<div class="thecontent clearfix">
            <div class="imdbwp imdbwp--movie dark"><div class="imdbwp__thumb"><a class="imdbwp__link" title="Runner" href="https://www.imdb.com/title/tt31349844/"><img class="imdbwp__img" src="https://image.tmdb.org/t/p/w185/vJk2TW1QZAmBiD7oj9ZF9EPTYY1.jpg"></a></div>
            <div class="imdbwp__content"><div class="imdbwp__header"><span class="imdbwp__title">Runner</span> (2026)</p>
            <div class="imdbwp__meta"><span>N/A</span>|<span>Action,Comedy</span>|<span>September 7, 2026</span></div></div>
            <div class="imdbwp__belt"><span class="imdbwp__star">8.3</span><span class="imdbwp__rating"><strong>Rating:</strong> 8.3 / 10 from 740 users</span></div>
            <div class="imdbwp__teaser">A former soldier is thrown into a brutal race against time.</div>
            <div class="imdbwp__footer"><strong>Director:</strong> <span>Scott Waugh</span><br /><strong>Actors:</strong> <span>Alan Ritchson, Owen Wilson</span></div></div></div>
            <h3 style="text-align: center;">Download Runner (2026) English-Hindi 480p, 720p &amp; 1080p ~ MoviesMod</h3>
            <h2>Screenshots:</h2><img src="https://blogger.googleusercontent.com/img/a/x">
            <h4 style="text-align: center;">Download Runner (2026) {English-Hindi} 480p x264 [320MB]</h4>
            <p style="text-align: center;"><a class="maxbutton-1 maxbutton maxbutton-download-links" href="https://links.modpro.blog/archives/160391">Download Links</a></p>
            <h4 style="text-align: center;">Download Runner (2026) {English-Hindi} 1080p 10bit [1.7GB]</h4>
            <p style="text-align: center;"><a class="maxbutton-1 maxbutton maxbutton-download-links" href="https://links.modpro.blog/archives/160392">Download Links</a></p>
            </div>""",
            base
        )
        val details = MoviesModParser.details(doc)
        assertEquals("Runner", details.title)
        assertEquals(2026, details.year)
        assertEquals("https://image.tmdb.org/t/p/w500/vJk2TW1QZAmBiD7oj9ZF9EPTYY1.jpg", details.poster)
        assertEquals(listOf("Action", "Comedy"), details.genres)
        assertEquals(8.3, details.rating!!, 0.0)
        assertEquals(listOf("Alan Ritchson", "Owen Wilson"), details.actors)
        assertEquals("tt31349844", details.imdbId)

        val groups = MoviesModParser.groups(doc)
        assertEquals(2, groups.size)
        assertEquals("https://links.modpro.blog/archives/160392", groups[1].buttons.single().url)
        assertFalse(MoviesModParser.isEpisodeList(groups[0].buttons.single().url))
        assertEquals(1080, MoviesModParser.quality(groups[1].heading))
        assertEquals("{English-Hindi} 1080p 10bit [1.7GB]", MoviesModParser.tags(groups[1].heading))
    }

    @Test fun seriesHeadingsNameSeasonAndSkipBatch() {
        val doc = MoviesModParser.body(
            """<div class="thecontent clearfix">
            <h3 style="text-align: center;">Season 2 {Hindi-English-French} 720p x264 Msubs [440MB]</h3>
            <p style="text-align: center;"><a class="maxbutton-23 maxbutton maxbutton-episode-links" href="https://episodes.modpro.blog/archives/19867">Episode Links</a>
            <a class="maxbutton-24 maxbutton maxbutton-batch-zip" href="https://episodes.modpro.blog/archives/19874">Batch/Zip File</a></p>
            <h3 style="text-align: center;">S01 (E06 Added) {English-Hindi} 480p x264 [160MB]</h3>
            <p style="text-align: center;"><a class="maxbutton-23 maxbutton maxbutton-episode-links" href="https://episodes.modpro.blog/archives/134851">Episode Links</a></p>
            </div>""",
            base
        )
        val groups = MoviesModParser.groups(doc)
        assertEquals(listOf(2, 1), groups.map { MoviesModParser.season(it.heading) })
        assertEquals(listOf("Episode Links", "Batch/Zip File"), groups[0].buttons.map { it.label })
        assertTrue(MoviesModParser.isBatch(groups[0].buttons[1].label))
        assertTrue(MoviesModParser.isEpisodeList(groups[1].buttons.single().url))
        assertEquals(6, MoviesModParser.addedEpisodes(groups[1].heading))
        assertNull(MoviesModParser.addedEpisodes(groups[0].heading))
    }

    @Test fun episodeListSkipsTheBatchLink() {
        val doc = MoviesModParser.body(
            """<div class="entry-content">
            <center><div><a href="https://en.thenaukriadda.in/?sid=BATCH"><button class=buttonred>All Episodes Batch</button></a></div></center>
            <h3 style="text-align: center;"><a target=_blank href="https://en.thenaukriadda.in/?sid=EP1">Episode 1 </a></h3>
            <h3 style="text-align: center;"><a target=_blank href="https://en.thenaukriadda.in/?sid=EP2">Episode 2 </a></h3>
            </div>""",
            "https://episodes.modpro.blog/archives/134851"
        )
        assertEquals(
            listOf(1 to "https://en.thenaukriadda.in/?sid=EP1", 2 to "https://en.thenaukriadda.in/?sid=EP2"),
            MoviesModParser.episodes(doc)
        )
    }

    @Test fun serverButtonsAreDeduplicated() {
        val doc = MoviesModParser.body(
            """<div class="entry-content">
            <a class="maxbutton-1 maxbutton" href="https://cloud.unblockedgames.world/?sid=A">✅ Fast Server (G-Drive)</a>
            <a class="maxbutton-2 maxbutton" href="https://cloud.unblockedgames.world/?sid=A">🔗 Gdrive Links (Login)</a>
            <a class="maxbutton-3 maxbutton" href="https://cloud.unblockedgames.world/?sid=B">⚡ G-Direct</a>
            <p>Please 📌 Comment if you find any broken links in <a href="https://cloud.unblockedgames.world/?sid=C">our comment section</a>.</p>
            </div>""",
            "https://posts.modpro.blog/archives/29889"
        )
        assertEquals(
            listOf("https://cloud.unblockedgames.world/?sid=A", "https://cloud.unblockedgames.world/?sid=B"),
            MoviesModParser.servers(doc)
        )
    }

    @Test fun newGateHops() {
        val landing = MoviesModParser.gateForm(MoviesModParser.body(
            """<form id="lp-land" method="POST" action="https://en.thenaukriadda.in/">
            <input type="hidden" name="_lp_http" value="THpv"></form>""",
            "https://en.thenaukriadda.in/?sid=THpv"
        ))!!
        assertEquals("https://en.thenaukriadda.in/", landing.action)
        assertEquals(mapOf("_lp_http" to "THpv"), landing.fields)

        // The step page also carries WordPress search forms, which must not be mistaken for the gate.
        val step = MoviesModParser.gateForm(MoviesModParser.body(
            """<form role="search" method="get" action="https://en.thenaukriadda.in/"><input type="search" name="s" value=""></form>
            <form id="lp-s1-form" method="POST" style="display:none;" action="https://en.thenaukriadda.in/what-is-cache-memory/">
            <input type="hidden" name="_lp_http2" value="eJwFwctygjAUANBf4qFOWbgo8ipIKCH3gtkFwhRJwIw6yOTrew7LzDYiTeicG/TNB2G3=">
            <input type="hidden" name="_lp_token" value="dnA3L0kyMVhOWEdYL05XS3NOUjJKdz09">
            <input type="hidden" name="_lp_chain" value="WyJodHRwczpc">
            <input type="hidden" name="_lp_hop_index" value="1"></form>""",
            "https://en.thenaukriadda.in/"
        ))!!
        assertEquals("https://en.thenaukriadda.in/what-is-cache-memory/", step.action)
        assertEquals(4, step.fields.size)
        assertEquals("eJwFwctygjAUANBf4qFOWbgo8ipIKCH3gtkFwhRJwIw6yOTrew7LzDYiTeicG/TNB2G3=", MoviesModParser.http2(step))

        val article = """bg.setAttribute('href',"https:\/\/en.thenaukriadda.in\/?lp_go=lp-6ae1fb78ad5c7");
            sc("lp-6ae1fb78ad5c7","eJwFwctygjAUANBf4qFOWbgo8ipIKCH3gtkFwhRJwIw6yOTrew7LzDYiTeicG\/TNB2G3=",60);"""
        assertEquals("lp_go" to "lp-6ae1fb78ad5c7", MoviesModParser.goToken(article))

        val go = """<script>setTimeout(function(){window.location.replace("https:\/\/driveseed.org\/r?key=UWhw=&id=WU85S1pr==");},350);</script>"""
        assertEquals("https://driveseed.org/r?key=UWhw=&id=WU85S1pr==", MoviesModParser.redirect(go))
    }

    @Test fun oldGateUsesWpFields() {
        val landing = MoviesModParser.gateForm(MoviesModParser.body(
            """<form id="landing" method="POST" action="https://cloud.unblockedgames.world/">
            <input type="hidden" name="_wp_http" value="a3Y4" /></form>""",
            "https://cloud.unblockedgames.world/?sid=a3Y4"
        ))!!
        assertEquals(mapOf("_wp_http" to "a3Y4"), landing.fields)
        assertEquals("go" to "abc123", MoviesModParser.goToken("""s_343('?go=abc123', 'x')"""))
    }

    @Test fun driveSeedFilePage() {
        assertEquals("/file/IbyEshXtHS", MoviesModParser.redirect("""<script>window.location.replace("/file/IbyEshXtHS")</script>"""))
        val file = MoviesModParser.driveFile(MoviesModParser.body(
            """<ul><li class="list-group-item">Name : Runner.2026.480p.WEB-DL.English.Hindi.ESubs.MoviesMod.meme.mkv</li>
            <li class="list-group-item">Size : 323.39MB</li></ul>
            <a href="https://pub-2fbc508ae8a44ef5b8b74ff2d026eb97.r2.dev/818278c6/Runner.2026.480p.mkv" class="btn btn-success"><i class="fas fa-arrow-down"></i> Cloud Download  </a>
            <a href="/zfile/IbyEshXtHS" class="btn btn-warning"><i class="fas fa-link"></i> Resume Cloud </a>
            <a href="https://cdn.video-gen.xyz/601c8d92::2a9e6c78" class="btn btn-danger"><i class="fas fa-arrow-down"></i> Instant Download  </a>
            <a href="https://seedtg.xyz/?start=MEVq&amp;bot=seedcdn_bot" class="btn btn-info"><i class="fas fa-arrow-down"></i> Telegram File </a>
            <a href="/wfile/IbyEshXtHS" target="_blank" class="btn btn-outline-success"><i class="fas fa-link"></i> Direct Links</a>""",
            "https://driveseed.org/file/IbyEshXtHS"
        ))
        assertEquals("Runner.2026.480p.WEB-DL.English.Hindi.ESubs.MoviesMod.meme.mkv", file.name)
        assertEquals("https://pub-2fbc508ae8a44ef5b8b74ff2d026eb97.r2.dev/818278c6/Runner.2026.480p.mkv", file.cloud)
        assertEquals("https://cdn.video-gen.xyz/601c8d92::2a9e6c78", file.instant)
        assertEquals("https://driveseed.org/wfile/IbyEshXtHS", file.direct)
        assertEquals(480, MoviesModParser.quality(file.name!!))
        assertEquals(
            "https://purple-tree-f504.solo020.workers.dev/b84382c9/driveseed.org/Runner.2026.480p.mkv",
            MoviesModParser.workerLink(MoviesModParser.body(
                """<a href="/wfile/IbyEshXtHS?type=2" class="btn btn-outline-info">Server 2 </a>
                <a href="https://purple-tree-f504.solo020.workers.dev/b84382c9/driveseed.org/Runner.2026.480p.mkv" type="button" class="btn btn-success" download>Download</a>""",
                "https://driveseed.org/wfile/IbyEshXtHS?type=1"
            ))
        )
        assertEquals(
            "https://video-downloads.googleusercontent.com/ADGPM2kd",
            MoviesModParser.instantTarget("https://video-seed.dev/?url=https://video-downloads.googleusercontent.com/ADGPM2kd")
        )
    }
}
