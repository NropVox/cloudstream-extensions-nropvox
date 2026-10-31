package com.nropvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MkvBaseParserTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    // Expected values from the site's own page functions run under node (UTF-8 XOR, double SHA-256, HMAC).
    @Test fun signsLikeThePage() {
        val time = 1793465641123L
        val query = MkvBaseParser.encodeQuery("Amélie 2", time)
        assertEquals("e2ce600acfcac68391", query)
        val nonce = MkvBaseParser.proofOfWork("9f2c1e7ab3", 3, query)
        assertEquals(11024, nonce)
        assertEquals("530890229441de6830e0d72688c95ded37db4dacf38b2adb3e07720ac49b5108",
            MkvBaseParser.signature("k3y-Cl1ent", query, time, "7", nonce, MkvBaseParser.ENTROPY))
    }

    @Test fun challengeCookieCarriesItsDifficulty() {
        assertEquals("ab12" to 3, MkvBaseParser.challenge("ab12:3"))
        assertEquals("ab12" to 2, MkvBaseParser.challenge("ab12"))
    }

    @Test fun queryDropsApostrophesAndPunctuation() {
        assertEquals("Greys Anatomy", MkvBaseParser.query("Grey's Anatomy"))
        assertEquals("Avatar The Last Airbender", MkvBaseParser.query("Avatar: The Last Airbender"))
    }

    @Test fun readsReleaseNames() {
        assertEquals(MkvBaseParser.Release("The Devil Wears Prada 2", 2026, null, null, false),
            MkvBaseParser.release("The Devil Wears Prada 2 (2026) 1080p BluRay [Hindi DDP 5 1 + English DDP 5 1] H 265 (Celdra IvY) mkv"))
        assertEquals(MkvBaseParser.Release("Schindlers List", 1993, null, null, false),
            MkvBaseParser.release("GDFlix | Schindlers List 1993 2160p MA WEB DL H 265 HDR DDP5 1 Atmos HHWEB mkv"))
        assertEquals(MkvBaseParser.Release("Alchemy of Souls", null, 1, 3, false),
            MkvBaseParser.release("(Movies4u Foo) Alchemy of Souls S01 E03 1080p WEB DL English Korean ESubs H 264 mkv"))
        assertEquals(MkvBaseParser.Release("Bleach Thousand Year Blood War", null, 2, 4, false),
            MkvBaseParser.release("Bleach Thousand Year Blood War S02E04 Heart Of Wolf 720p HS WEB DL AAC2 0 H 264 MoviesDrives CFD mkv"))
        assertTrue(MkvBaseParser.release("Bleach S01 1080p AMZN WEB DL Hindi DDP2 0 Japanese DDP2 0 H 265 4kHdHub Com zip").pack)
        assertTrue(MkvBaseParser.release("TitanCloud | Greys Anatomy S20 1080p AMZN WEB DL DDP5 1 H 264 FLUX pack").pack)
        // A number in the name stays when a year follows it.
        assertEquals("Blade Runner 2049", MkvBaseParser.release("Blade Runner 2049 (2017) 2160p BluRay mkv").name)
        assertEquals("1917", MkvBaseParser.release("1917 2019 1080p BluRay x264 mkv").name)
    }

    @Test fun movieMatchesItsYearNotTheOriginal() {
        val results = MkvBaseParser.results(text("mkvbase-devil-wears-prada.json"))
        val sequel = MkvBaseParser.matches(results, listOf("The Devil Wears Prada 2"), listOf(2026), null, null)
        assertEquals(25, sequel.size)
        assertTrue(sequel.all { it.title!!.startsWith("The Devil Wears Prada 2 ") })
        val original = MkvBaseParser.matches(results, listOf("The Devil Wears Prada"), listOf(2006), null, null)
        assertEquals(14, original.size)
        assertTrue(original.none { it.title!!.contains("2026") })
    }

    @Test fun episodeMatchesSeasonAndNumberWithoutPacks() {
        val results = MkvBaseParser.results(text("mkvbase-mixed.json"))
        val tybw = MkvBaseParser.matches(results, listOf("Bleach: Thousand-Year Blood War"), listOf(2022, 2024), 2, 4)
        assertEquals(listOf("https://hubcloud.ist/drive/filddhltz1tlfdq"), tybw.map { it.url })
        val alchemy = MkvBaseParser.matches(results, listOf("Alchemy of Souls"), listOf(2022), 1, 3)
        assertEquals(listOf("https://gdflix.dev/file/5YePgd8Zl6FYA4Z"), alchemy.map { it.url })
        // Plain "Bleach" must not take the sequel series' files.
        assertTrue(MkvBaseParser.matches(results, listOf("Bleach"), listOf(2004), 2, 4).isEmpty())
    }
}
