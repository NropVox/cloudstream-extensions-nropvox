package com.nropvox

import com.nropvox.KayoanimeParser.Kind
import com.nropvox.KayoanimeParser.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases taken from the 2026-10-28 crawl of ~200 kayoanime posts and their Drive folders. */
class KayoanimeParserTest {
    private fun file(name: String, label: String = "1080p", vararg folders: String, group: String? = null) =
        SourceFile("id-" + name.hashCode() + label.hashCode() + folders.joinToString().hashCode(), name, label, folders.toList(), group)

    private fun KayoanimeParser.Episodes.slots() = entries.map { "S${it.season}E${it.episode}" }

    // ---- links ---------------------------------------------------------------------------------------------

    @Test fun refsReadEveryLinkShape() {
        val html = """
            <iframe src="https://drive.google.com/file/d/1mhNT4khZ1T8xVQsJqdRIgd850pUpegeo/preview"></iframe>
            <div class="toggle"><h3 class="toggle-head">Download The Anime From Drive</h3><div class="toggle-content">
            <a href="https://tinyurl.com/9yh733xs" class="shortc-button">Google Group</a>
            <a href="https://tinyurl.com/4yb963jf" class="shortc-button">Season 1 [Private Drive]</a>
            <a href="https://drive.google.com/drive/folders/1JBzseQeoEh9Da0iAaD7h5AG2eM6EjQV-?usp=sharing">SEASON 3 [Private Drive]</a>
            <a href="https://drive.google.com/drive/u/0/folders/1JBzseQeoEh9Da0iAaD7h5AG2eM6EjQV-">Copy</a>
            <a href="https://drive.google.com/open?id=1abcdefghijklmnopqrstuv">Old</a>
            <a href="https://worker-lucky-shadow-98e9.kayoanime159.workers.dev/0:/OK/">Season 3</a>
            <a href="https://mega.nz/folder/sk5DkSoQ#wwkjr0SWNpYL_YQzKkuO-Q">1080p</a>
            <a href="" class="shortc-button">Season 1 [1080p]</a>
            <a href="https://kayoanime.com/burn-the-witch-episode-1-english-subbed/">Burn the Witch</a>
            </div></div>""".trimIndent()
        val refs = KayoanimeParser.refs(html)
        assertEquals(
            listOf(
                Kind.DRIVE to "1mhNT4khZ1T8xVQsJqdRIgd850pUpegeo",
                Kind.SHORT to "https://tinyurl.com/4yb963jf",
                Kind.DRIVE to "1JBzseQeoEh9Da0iAaD7h5AG2eM6EjQV-",
                Kind.DRIVE to "1abcdefghijklmnopqrstuv",
                Kind.INDEX to "https://worker-lucky-shadow-98e9.kayoanime159.workers.dev/0:/OK/",
            ),
            refs.map { it.kind to it.target },
        )
        assertEquals("SEASON 3 [Private Drive]", refs[2].label)
        assertTrue(refs.all { it.group == null })
    }

    @Test fun compilationToggleHeadsBecomeGroups() {
        val html = """
            <div class="toggle"><h3 class="toggle-head">Summer Wars</h3><div class="toggle-content">
              <a href="https://drive.google.com/file/d/1AUB7THXuhAc3-2BnzPPTK1OPkDQuBitB/view">1080p</a></div></div>
            <div class="toggle"><h3 class="toggle-head">Ninja Scroll</h3><div class="toggle-content">
              <a href="https://drive.google.com/drive/folders/18dcH54moGqd98PQSUZHkx-5XP55rxPR1">1080p</a></div></div>""".trimIndent()
        assertEquals(listOf("Summer Wars", "Ninja Scroll"), KayoanimeParser.refs(html).map { it.group })
    }

    @Test fun shortLinkTargetsAreClassified() {
        assertEquals(Kind.INDEX, KayoanimeParser.classify("https://drive.kayoani.online/0:/Mushoku%20Tensei%20S1/", "S1")?.kind)
        assertEquals(Kind.DRIVE, KayoanimeParser.classify("https://drive.google.com/drive/folders/1ggQfRP8wyMypzCb2vxQOGTGUXbSvC5H2?usp=sharing", "")?.kind)
        assertNull(KayoanimeParser.classify("https://groups.google.com/g/kayoanime-naruto-shippuden/c/mJvLAi43420", ""))
        assertNull(KayoanimeParser.classify("https://mega.nz/folder/5SsU0agT#NG-4rEwk1eFnMPsRj8gRUA", ""))
    }

    @Test fun indexPathsRoundTrip() {
        val folder = "https://worker-delicate-meadow-kayo161.kayoanime161.workers.dev/0:/Dragon%20ball%20%2B%20Z/"
        assertEquals("Dragon ball + Z", KayoanimeParser.indexName(folder))
        assertEquals("", KayoanimeParser.indexName("https://drive.kayoani.online/0:/"))
        val child = KayoanimeParser.indexChild(folder, "Dragon Ball Z - 01 [x265].mkv", false)
        assertEquals("$folder" + "Dragon%20Ball%20Z%20-%2001%20%5Bx265%5D.mkv", child)
        assertEquals("Dragon Ball Z - 01 [x265].mkv", KayoanimeParser.indexName(child))
    }

    @Test fun sourcesRoundTripDriveAndIndex() {
        val files = listOf(
            SourceFile("1PEOVkFFabc", "Show - 01 [1080p].mkv", "Season 1 [Private Drive]"),
            SourceFile("https://drive.kayoani.online/0:/Hunter/Hunter%20x%20Hunter%20(2011)%20-%20S01E001.mkv", "Hunter x Hunter (2011) - S01E001.mkv", "1080p"),
        )
        val decoded = KayoanimeParser.decodeSources(KayoanimeParser.encodeSources(files))
        assertEquals(files.map { Triple(it.id, it.name, it.label) }, decoded.map { Triple(it.id, it.name, it.label) })
        assertEquals(listOf(false, true), decoded.map { it.isIndex })
    }

    // ---- episodes ------------------------------------------------------------------------------------------

    @Test fun anotherArcAtTakenNumbersGetsItsOwnNamedSeason() {
        val episodes = KayoanimeParser.entries(listOf(
            file("[Kayoanime] Code Geass S1 - 01.mkv", "Season 1", "[Kayoanime] Code Geass (Season 1) [1080p] (Batch)"),
            file("[Kayoanime] Code Geass S2 - 01.mkv", "Season 2", "[Kayoanime] Code Geass (Season 2) [1080p] (Batch)"),
            file("Code Geass - Dakkan no Roze - 01.mkv", "Roze of the Recapture", "Code Geass: Roze of the Recapture"),
            file("Code Geass Movie.mkv", "Movie [Private Drive]", "Code Geass Fukkatsu no Lelouch"),
        ))
        assertEquals(listOf("S1E1", "S2E1", "S3E1", "S0E1"), episodes.slots())
        assertEquals(mapOf(3 to "Code Geass - Dakkan no Roze"), episodes.seasonNames)
        assertEquals("Code Geass Movie", episodes.entries.last().title)
    }

    @Test fun releasesOfOneShowAreCopies() {
        val episodes = KayoanimeParser.entries(listOf(
            file("Trinity Seven - S01E01 - Administer And Third Selection.mkv", "1080p", "Trinity Seven [BD][1080p][HEVC 10bit x265][Dual Audio]"),
            file("Trinity Seven - 01.mkv", "720p", "Trinity Seven"),
        ))
        assertEquals(listOf("S1E1"), episodes.slots())
        assertEquals(2, episodes.entries.single().files.size)
        assertTrue(episodes.seasonNames.isEmpty())
    }

    @Test fun titleAliasesJoinReleasesNamedInTwoLanguages() {
        val files = listOf(
            file("[Kayoanime] Okashi na Tensei - S01E01.mkv", "1080p", "Okashi na Tensei (Sweet Reincarnation)"),
            file("S01E01-Dreaming of a Land of Sweets [A5DE540C].mkv", "1080p", "Sweet Reincarnation S01"),
        )
        assertEquals(2, KayoanimeParser.entries(files).entries.size)
        val aliases = KayoanimeParser.aliases("Sweet Reincarnation (Okashi na Tensei) (Season 1) 1080p Dual Audio HEVC")
        assertEquals(listOf("sweet reincarnation", "okashi na tensei"), aliases)
        assertEquals(listOf("S1E1"), KayoanimeParser.entries(files, aliases).slots())
    }

    @Test fun halfEpisodesAndRecapsAreSpecials() {
        val episodes = KayoanimeParser.entries(listOf(
            file("Usagi Drop - 02 [x265 10 bit 1920x1080 AAC].mkv"),
            file("Usagi Drop - 02.5 [x265 10 bit 1920x1080 AAC].mkv"),
            file("Episode 20.5 -  Twentieth Mourning and Offense.mkv", "BOX 1", "Shiki"),
            file("[Kayoanime] Tensei Shitara Slime Datta Ken - S03E17 Recap.mkv", "Season 3"),
        ))
        assertEquals(listOf("S1E2", "S0E1", "S0E2", "S0E3"), episodes.slots())
    }

    @Test fun showNamesWithExtrasAreNotExtrasFolders() {
        val episodes = KayoanimeParser.entries(listOf(
            file("Blood Lad - OVA.mkv", "1080p [Private Drive]", "Blood Lad + OVA"),
            file("Blood Lad 01 She's a Skeleton Now.mkv", "1080p [Private Drive]", "Blood Lad + OVA"),
            file("Blood Lad 02 Back Home, But Not Really.mkv", "1080p [Private Drive]", "Blood Lad + OVA"),
            file("Noragami - 01.mkv", "1080p", "Specials", "Noragami S1"),
        ))
        assertEquals(listOf("S1E1", "S1E2", "S0E1", "S0E2"), episodes.slots())
    }

    @Test fun specialsAreNeverMergedAndKeepReadableNames() {
        val episodes = KayoanimeParser.entries(listOf(
            file("DB - TV Sp 1 - Goku's Traffic Safety (1988) [R2J].mkv", "OVAs", "OVAs & TV Specials"),
            file("DBZ - OVA 1 - Plan to Destroy the Saiyans (1993) [R2J].mkv", "OVAs", "OVAs & TV Specials"),
            file("Code：Breaker][OVA1].mkv", "OVA", "Code:Breaker"),
        ))
        assertEquals(listOf("S0E1", "S0E2", "S0E3"), episodes.slots())
        assertEquals(
            listOf("DB - TV Sp 1 - Goku's Traffic Safety", "DBZ - OVA 1 - Plan to Destroy the Saiyans", "Code：Breaker OVA1"),
            episodes.entries.map { it.title },
        )
    }

    @Test fun releaseTagsAreNotEpisodeNumbers() {
        val slot = KayoanimeParser.slot("Giovanni's.Island.BDRip.1080p.10Bit.Dual.Audio.FLAC.5.1.X.265.mkv", "1080p")
        assertNull(slot.episode)
        assertEquals("Giovanni's Island BDRip 1080p 10Bit Dual Audio FLAC 5 1 X 265",
            KayoanimeParser.displayName("Giovanni's.Island.BDRip.1080p.10Bit.Dual.Audio.FLAC.5.1.X.265.mkv"))
    }

    @Test fun compilationMoviesTakeSeasonOneAndSeriesMoveOut() {
        val episodes = KayoanimeParser.entries(listOf(
            file("Bloody Escape - Jigoku no Tousou Geki.mkv", group = "Bloody Escape: Bats out of Hell"),
            file("Ninja Scroll (The Series) - Ep. 01 - Tragedy in the Hidden Village (1080p BluRay - DUAL Audio).mkv", "1080p", "Ninja Scroll (2003)", group = "Ninja Scroll"),
            file("Summer Wars.mkv", group = "Summer Wars"),
            file("Towa no Quon - 01.mkv", "1080p", "Towa no Quon", group = "Towa no Quon"),
        ))
        assertEquals(listOf("S1E1", "S1E2", "S2E1", "S3E1"), episodes.slots())
        assertEquals(listOf("Bloody Escape: Bats out of Hell", "Summer Wars"), episodes.entries.take(2).map { it.title })
        assertEquals(mapOf(2 to "Ninja Scroll", 3 to "Towa no Quon"), episodes.seasonNames)
    }

    @Test fun sharedFolderLabelsDoNotNameAMovedSeason() {
        val episodes = KayoanimeParser.entries(listOf(
            file("The Seven Deadly Sins - S01E01.mkv", "Season 1", "The Seven Deadly Sins - S01"),
            file("Grudge of Edinburgh Part 01.mkv", "Prisoners of the Sky [Private Drive]", "Nanatsu"),
            file("Nanatsu no Taizai - Seisen no Shirushi - 01.mkv", "Prisoners of the Sky [Private Drive]", "Signs of Holy War", "Nanatsu"),
        ))
        assertEquals(mapOf(2 to "Grudge of Edinburgh Part", 3 to "Signs of Holy War"), episodes.seasonNames)
    }

    @Test fun nearestCopyOfAFileWins() {
        val far = SourceFile("x", "Show - 01.mkv", "Season 1", listOf("Season 1", "Show"))
        val near = SourceFile("x", "Show - 01.mkv", "Season 1 [Private Drive]", listOf("Season 1"))
        assertEquals(listOf(near), KayoanimeParser.distinctFiles(listOf(far, near)))
    }
}
