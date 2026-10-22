package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Snippets trimmed from REST `content.rendered` and nexdrive pages saved on 2026-10-22. */
class XPrimeHubParserTest {
    private val base = "https://xprimehub.pics"

    @Test fun titlesKeepSeasonPartsAndEpisodesApart() {
        assertEquals("Vermilion", XPrimeHubParser.cleanTitle("Vermilion (2022) UNRATED Japanese Full Movie 480p | 720p WEB-DL | Free Download"))
        assertEquals(
            "Andhadhundh S01 Part 2",
            XPrimeHubParser.cleanTitle("Download [18+] Andhadhundh (2026) S01 Part 2 Hindi ULLU Originals Complete WEB Series 480p | 720p | 1080p WEB-DL")
        )
        assertEquals("Sarpanchi S01E07&08", XPrimeHubParser.cleanTitle("Sarpanchi (2026) S01E07&#038;08 Hindi 9RedMovies Web Series 720p WEB-DL [670MB] | Free Download"))
        assertEquals("Mega Perv Episode 6", XPrimeHubParser.cleanTitle("Mega Perv Episode 6 (2026) Brazzers Originals English Adult Short Film 720p WEB-DL [330MB]"))
        assertEquals(2022, XPrimeHubParser.year("Vermilion (2022) UNRATED"))
    }

    @Test fun episodeHeadingsSplitASeriesPost() {
        val doc = XPrimeHubParser.body(
            """<h3>: SCREENSHOTS :</h3><p><img src="https://imageflix.cam/images/a.png"></p><hr>
            <h5>[18+] Paap S01 Hindi {Episode 1} 720p [150MB]</h5>
            <p><a href="https://nexdrive.beer/genxfm784776214000/"><button> &#x26a1; Download Now &#x26a1; </button></a></p>
            <h5>[18+] Paap S01 Hindi {Episode 2} 720p [250MB]</h5>
            <p><a href="https://nexdrive.beer/genxfm784776216237/"><button> &#x26a1; Download Now &#x26a1; </button></a></p>
            <hr><h3>Winding Up ❤️</h3><p>Thank You For Visiting <a href="/">XPrimeHub</a></p>""",
            base
        )
        val groups = XPrimeHubParser.groups(doc)
        assertEquals(listOf(1, 2), groups.map { XPrimeHubParser.episode(it.heading) })
        assertEquals("https://nexdrive.beer/genxfm784776216237/", groups[1].buttons.single().url)
        assertEquals("Download Now", groups[1].buttons.single().label)
        assertEquals(1, XPrimeHubParser.season(groups[0].heading))
        assertEquals("[250MB]", XPrimeHubParser.size(groups[1].heading))
        assertEquals("https://imageflix.cam/images/a.png", XPrimeHubParser.screenshot(doc, "xprimehub.pics"))
    }

    @Test fun newTemplateDetailsAndButton() {
        val doc = XPrimeHubParser.body(
            """<table class="xp-info-table"><tbody>
            <tr><td class="xp-td-label">🎭 Cast</td><td><strong>Aika Yamagishi, Hirokio Andô</strong></td></tr>
            <tr><td class="xp-td-label">🗣 Language</td><td><strong><span class="xp-td-red">Japanese</span></strong></td></tr>
            <tr><td class="xp-td-label">🎭 Genre</td><td><strong>Erotic, Hot, Romance</strong></td></tr></tbody></table>
            <h3 class="xp-section-heading">📖 Storyline / PLOT</h3><p class="xp-plot-box">The daughter of a wealthy family.</p>
            <h4 class="xp-download-title">Vermilion (2022) UNRATED <span>Japanese Full Movie</span> 720p WEB-DL [270MB]</h4>
            <div class="xp-download-wrap"><a href="https://nexdrive.beer/genxfm784776515527/" class="xp-download-btn">⚡ Download Now ⚡</a></div>""",
            base
        )
        assertEquals(listOf("Aika Yamagishi", "Hirokio Andô"), XPrimeHubParser.list(XPrimeHubParser.detail(doc, "Cast")))
        assertEquals("Japanese", XPrimeHubParser.detail(doc, "Language"))
        assertEquals("The daughter of a wealthy family.", XPrimeHubParser.plot(doc))
        val group = XPrimeHubParser.groups(doc).single()
        assertNull(XPrimeHubParser.episode(group.heading))
        assertEquals(720, XPrimeHubParser.quality(group.heading))
    }

    @Test fun oldTemplateDetails() {
        val doc = XPrimeHubParser.body(
            """<p><strong>Full Name: </strong>Andhadhundh<br /><strong>Cast:</strong> Subhati (Geet), Muskaan (Preet)<br />
            <strong>Genre: </strong>Erotic, Hot, Romance<br /></p>
            <h3><span><strong>: Storyline/PLOT :</strong></span></h3><p>The hidden scheme of Suhas.</p>""",
            base
        )
        assertEquals("Subhati (Geet), Muskaan (Preet)", XPrimeHubParser.detail(doc, "Cast"))
        assertEquals(listOf("Erotic", "Hot", "Romance"), XPrimeHubParser.list(XPrimeHubParser.detail(doc, "Genre")))
        assertEquals("The hidden scheme of Suhas.", XPrimeHubParser.plot(doc))
    }

    @Test fun nexdriveHostLinksSkipPromos() {
        val doc = XPrimeHubParser.body(
            """<div class="entry-inner">Visit <a href="https://vglist.cv">VgList.cv</a>
            <a href="https://fastdl.beer/embed.php?download=slOacQ4wh1dEYn7W8EfxBjiYo"> &#x26a1; G-Direct [Instant] </a>
            <a href="https://vcloud.beer/a2ub1oxmz4vx3u4"> &#x26a1; V-Cloud [Resumable] </a>
            <a href="https://one.vegadrive.app/s/pwnWFjFUkmWg"><button>V-Drive [Multi]</button></a>
            <a href="https://filebee.xyz/file/69a1f5964b1ecb5acf73d2ee"> &#x26a1; Filepress [G-Drive] </a></div>""",
            "https://nexdrive.beer"
        )
        val links = XPrimeHubParser.hostLinks(doc)
        assertEquals(
            listOf(XPrimeHubParser.Host.GDIRECT, XPrimeHubParser.Host.VCLOUD, XPrimeHubParser.Host.VDRIVE),
            links.map { XPrimeHubParser.host(it) }
        )
        assertEquals("pwnWFjFUkmWg", XPrimeHubParser.vdriveId(links[2]))
    }
}
