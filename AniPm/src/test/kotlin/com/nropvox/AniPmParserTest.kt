package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses saved from ani.pm on 2026-10-28 (recon folder samples/), trimmed. */
class AniPmParserTest {
    private val base = "https://ani.pm"
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private inline fun <reified T> sample(name: String): T =
        mapper.readValue(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test fun searchCardsGiveRefsForBothSources() {
        val items = sample<ApPage>("search.json").items!!
        val frieren = items.first { it.id == 6351L }
        assertEquals(ApRef("settlar", "6351"), AniPmParser.ref(frieren))
        assertEquals(
            "https://ani.pm/anime/frieren-beyond-journeys-end-6351?source=settlar&id=6351",
            AniPmParser.titleUrl(base, frieren.routeId, AniPmParser.ref(frieren)!!)
        )
        assertEquals(ApRef("anilist", "206425"), AniPmParser.ref(items.first { it.source == "anilist" }))
    }

    @Test fun anilistCardIdOffsetIsIgnored() {
        // Latest-episodes and spotlight cards: id 1176314 for AniList 176314.
        val card = sample<ApPage>("latest-episodes.json").items!!.first()
        assertEquals(1176314L, card.id)
        assertEquals(ApRef("anilist", "176314"), AniPmParser.ref(card))
        // Without anilistId the offset is stripped from the id.
        assertEquals(ApRef("anilist", "195539"), AniPmParser.ref(ApCard(id = 1195539, source = "anilist")))
        assertEquals(ApRef("anilist", "11061"), AniPmParser.ref(ApCard(id = 11061, source = "anilist")))
        assertNull(AniPmParser.ref(ApCard(id = 1, source = "other")))
    }

    @Test fun titleUrlsRoundTrip() {
        val url = AniPmParser.titleUrl(base, "cyberpunk-edgerunners-2-195539", ApRef("anilist", "195539"))
        assertEquals(ApRef("anilist", "195539"), AniPmParser.parseRef(url))
        assertEquals("anime/ani/195539", AniPmParser.detailsPath(AniPmParser.parseRef(url)!!))
        assertEquals("anime/series/6351", AniPmParser.detailsPath(ApRef("settlar", "6351")))
        // A site URL has no source; the caller tries both backends.
        assertEquals(ApRef("", "6351"), AniPmParser.parseRef("https://ani.pm/anime/frieren-beyond-journeys-end-6351"))
        assertEquals(ApRef("", "6351"), AniPmParser.parseRef("https://ani.pm/anime/frieren-beyond-journeys-end-6351?ep=3"))
        assertNull(AniPmParser.parseRef("https://ani.pm/"))
    }

    @Test fun detailsCarryEpisodesWithAudioFlags() {
        val settlar = sample<ApDetails>("series-6351.json")
        assertEquals("Frieren: Beyond Journey’s End", settlar.title)
        assertEquals("52991", settlar.malId)
        assertEquals("154587", settlar.anilistId)
        val first = settlar.episodes!!.first()
        assertEquals(1, first.number)
        assertTrue(first.sub == true && first.dub == true)
        assertEquals("https://ani.pm/api/anime/art/still/154587/1", AniPmParser.absolute(base, first.thumbnail))
        assertEquals("https://ani.pm/logos/t/yQuc8JcBxRWXfRHrNUSM47gCXOo.webp", AniPmParser.absolute(base, settlar.logo))
        assertEquals("sousou-no-frieren-marumaru-no-mahou-mini-anime-6180", settlar.relations!!.first().routeId)
        assertEquals(false, AniPmParser.isOngoing(settlar.status))

        val anilist = sample<ApDetails>("ani-195539.json")
        assertEquals(10, anilist.episodes!!.size)
        assertEquals("ONA", anilist.type)
        assertTrue(AniPmParser.isOva(anilist.type))
    }

    @Test fun durationsMayBeText() {
        // Some settlar titles say "125 min"; an Int field made the whole response unparseable.
        val movie = sample<ApDetails>("series-1596.json")
        assertEquals("Gurren Lagann The Movie: The Lights in the Sky are Stars", movie.title)
        assertEquals("125 min", movie.duration)
        assertEquals(125, AniPmParser.minutes(movie.duration))
        assertEquals(24, AniPmParser.minutes(sample<ApDetails>("series-6351.json").duration))
        assertEquals(26, AniPmParser.minutes(sample<ApDetails>("series-6351.json").episodes!!.first().runtime))
        assertNull(AniPmParser.minutes(null))
    }

    @Test fun typesAndStatuses() {
        assertTrue(AniPmParser.isMovie("Movie"))
        assertTrue(AniPmParser.isMovie("MOVIE"))
        assertFalse(AniPmParser.isMovie("TV"))
        assertTrue(AniPmParser.isOva("Special"))
        assertFalse(AniPmParser.isOva("TV Short"))
        assertEquals(true, AniPmParser.isOngoing("Currently Airing"))
        assertEquals(true, AniPmParser.isOngoing("RELEASING"))
        assertEquals(false, AniPmParser.isOngoing("FINISHED"))
        assertNull(AniPmParser.isOngoing(null))
    }

    @Test fun playbackChainPieces() {
        assertEquals(
            "anime/playback-bootstrap/settlar/6351?ep=3&lang=dub",
            AniPmParser.bootstrapPath(ApLinkData("settlar", "6351", 3, "dub"))
        )
        val embedUrl = sample<ApSettlarSession>("settlar-session.json").embedUrl!!
        val token = AniPmParser.embedToken(embedUrl)!!
        assertTrue(token.startsWith("2.dY5kGKSgXX3kXpdMhhgGZHUqB1Jvkqeg"))
        assertFalse(token.contains("&"))

        val session = sample<ApEmbedSession>("embed-session.json")
        assertEquals("hls", session.kind)
        assertTrue(session.source!!.startsWith("https://media.settlar.io/v1/object/"))
        assertEquals(listOf("Arabic", "English", "French"), session.subtitles!!.map { it.label })
    }

    @Test fun linkLabelsShowAudioFallback() {
        assertEquals("Sub", AniPmParser.linkLabel("sub", "sub"))
        assertEquals("Dub", AniPmParser.linkLabel("dub", "dub"))
        assertEquals("Sub (no dub)", AniPmParser.linkLabel("dub", "sub"))
        assertEquals("Sub", AniPmParser.linkLabel("sub", null))
    }
}
