package com.nropvox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MkvDramaPathTest {
    @Test fun qualitiesRankAs1080pHdThenGreatestHeight() {
        val ranked = listOf("720p", "1080p", "2160p", "1080pHD", "540p", "Download").sortedByDescending(MkvDramaDecoder::rank)
        assertEquals(listOf("1080pHD", "2160p", "1080p", "720p", "540p", "Download"), ranked)
    }

    @Test fun pathTiersOrderOuoContainersShortenersThenHosts() {
        val urls = listOf(
            "https://ouo.io/a", "https://mkvdrama.net/c/native1", "https://viewcrate.cc/c/crate1",
            "https://www.filecrypt.cc/Container/ABC.html", "https://oii.la/b", "https://pixeldrain.com/u/direct",
            "https://gofile.io/d/x", "https://mega.nz/file/x",
        )
        val tiers = urls.map { MkvDramaDecoder.tier(it)!! }
        assertEquals(tiers.sorted(), tiers)
        assertEquals(urls.size, tiers.toSet().size)
        assertNull(MkvDramaDecoder.tier("https://unknown.example/x"))
        assertNull(MkvDramaDecoder.tier("https://mkvdrama.net/_c/abc"))
    }

    @Test fun hostsArePixeldrainThenGofileThenOthersWithOfflineRowsLast() {
        val rows = listOf(
            MkvFile("https://filecrypt.cc/Link/1.html", host = "mega.nz"),
            MkvFile("https://filecrypt.cc/Link/2.html", host = "gofile.io"),
            MkvFile("https://filecrypt.cc/Link/3.html", host = "pixeldrain.com", offline = true),
            MkvFile("https://krakenfiles.com/view/5"),
        )
        assertEquals(listOf("2", "1", "5", "3"), MkvDramaDecoder.byHost(rows).map { it.single().url.filter(Char::isDigit) })
        // A resolved Pixeldrain row joins online display-name rows of the same host.
        val resolved = MkvDramaDecoder.byHost(listOf(MkvFile("https://gofile.io/d/x"), MkvFile("https://pixeldrain.com/u/a"),
            MkvFile("https://viewcrate.cc/open/b", host = "pixeldrain.com")))
        assertEquals(listOf("pixeldrain.com", "gofile.io"), resolved.map { MkvDramaDecoder.host(it[0]) })
        assertEquals(2, resolved[0].size)
    }

    @Test fun releasesCoveringTheSameEpisodesShareABucket() {
        assertEquals(MkvDramaDecoder.bucket("iQ - Episode 01-40"), MkvDramaDecoder.bucket("NF - Episode 01 - 40"))
        assertNotEquals(MkvDramaDecoder.bucket("Episode 01-10"), MkvDramaDecoder.bucket("Episode 11-20"))
        assertNotEquals(MkvDramaDecoder.bucket("Episode 01-03"), MkvDramaDecoder.bucket("Extra - EP 01 - 03"))
    }

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())

    private fun dlcVector(files: List<Pair<String, String>>, version: String = "20_02_2008"): Pair<String, String> {
        val xml = "<dlc><header><dlcxmlversion>${b64(version)}</dlcxmlversion></header><content><package>" +
            files.joinToString("") { (url, name) -> "<file><filename>${b64(name)}</filename><url>${b64(url)}</url></file>" } +
            "</package></content></dlc>"
        val key = "fixture-key-1234".toByteArray()
        val plain = b64(xml).toByteArray().let { bytes -> val pad = 16 - bytes.size % 16; bytes + ByteArray(pad) { pad.toByte() } }
        val ciphertext = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            doFinal(plain)
        }
        val container = Base64.getEncoder().encodeToString(ciphertext) + Base64.getEncoder().encodeToString(ByteArray(64) { 65 })
        val serviceKey = MkvDramaDecoder.hex("447e787351e60e2c6a96b3964be0c9bd")
        val wrapped = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(serviceKey, "AES"))
            doFinal(Base64.getEncoder().encode(key).copyOf(32))
        }
        return container to "<rc>${Base64.getEncoder().encodeToString(wrapped)}</rc>"
    }

    @Test fun dlcExportsEveryFileLocallyAndSendsOnlyItsToken() {
        val (container, service) = dlcVector(listOf(
            "https://pixeldrain.com/u/one" to "Show.S01E01.mkv 1.2 GB",
            "https://gofile.io/d/two" to "Show.S01E02 & 日本語.mkv 1.1 GB",
        ))
        assertEquals(Base64.getEncoder().encodeToString(ByteArray(64) { 65 }), MkvDramaDecoder.dlcToken(container))
        val files = MkvDramaDecoder.dlc(container, service)
        assertEquals(listOf("https://pixeldrain.com/u/one", "https://gofile.io/d/two"), files.map { it.url })
        assertEquals("Show.S01E02 & 日本語.mkv 1.1 GB", files[1].filename)
        assertTrue(MkvDramaDecoder.matches(files[1], 2))
    }

    @Test fun dlcRejectsBadTokensKeysVersionsAndUrls() {
        val (container, service) = dlcVector(listOf("https://pixeldrain.com/u/one" to "One.mkv"))
        assertThrows(Exception::class.java) { MkvDramaDecoder.dlcToken("A".repeat(88)) }
        assertThrows(Exception::class.java) { MkvDramaDecoder.dlc(container, "<rc>rate limited</rc>") }
        assertThrows(Exception::class.java) { MkvDramaDecoder.dlc(container, service.replace("<rc>", "<script>")) }
        val (old, oldService) = dlcVector(listOf("https://pixeldrain.com/u/one" to "One.mkv"), version = "1_0")
        assertThrows(Exception::class.java) { MkvDramaDecoder.dlc(old, oldService) }
        val (unsafe, unsafeService) = dlcVector(listOf("file:///etc/passwd" to "One.mkv"))
        assertThrows(Exception::class.java) { MkvDramaDecoder.dlc(unsafe, unsafeService) }
    }

    @Test fun dlcEntriesTakeTheHeadingOfTheirOneMatchingCard() {
        val cards = listOf(
            MkvFile("https://viewcrate.cc/open/a", "Show.720p[MkvDrama.Org].mkv", "Episode 05", host = "Pixeldrain"),
            MkvFile("https://viewcrate.cc/open/b", "Show.720p[MkvDrama.Org].mkv", "Episode 05", host = "gofile.io"),
        )
        val entries = MkvDramaDecoder.withHeadings(listOf(
            MkvFile("https://pixeldrain.com/u/x", "Show.720p.mkv 500 MB"),
            MkvFile("https://mega.nz/file/y", "Show.720p.mkv 500 MB"),
        ), cards)
        assertEquals(listOf("Episode 05", ""), entries.map { it.episode })
        assertTrue(MkvDramaDecoder.matches(entries[0], 5))
    }

    @Test fun filecryptDlcIdComesFromTheAttributeItsHandlerReads() {
        val html = """<button class="dlcdownload" data-mnizl="49B96F90AB" onclick="DownloadDLC(this.getAttribute('data-MnIzL'));"></button>"""
        assertEquals("https://www.filecrypt.cc/DLC/49B96F90AB.dlc", MkvDramaDecoder.filecryptDlc(html, "https://www.filecrypt.cc/Container/X.html"))
        assertNull(MkvDramaDecoder.filecryptDlc("""<button class="dlcdownload" onclick="DownloadDLC('../x')"></button>""", "https://www.filecrypt.cc/Container/X.html"))
    }

    @Test fun cacheKeepsContainerRowsAcrossRestartsAndRecordsResolvedLinks() = runBlocking {
        val store = mutableMapOf<String, String>()
        var clock = 1_000L
        fun cache() = MkvDramaCache(store::get, { key, value -> store[key] = value }, { clock })
        val series = "https://mkvdrama.net/pursuit-of-jade"
        val rows = listOf(MkvFile("https://viewcrate.cc/open/a", "Show.S01E01.mkv", host = "pixeldrain.com"))
        cache().put(series, MkvCachedRelease("NF - Episode 01 - 40", "1080pHD", 1, 40, "https://viewcrate.cc/c/x", rows, clock))
        cache().put(series, MkvCachedRelease("Extra - EP 01", "720p", 1, 1, "https://ouo.io/e", rows, clock))
        // The same episodes from another source replace the earlier choice.
        val restarted = cache()
        restarted.put(series, MkvCachedRelease("iQ - Episode 01-40", "2160p", 1, 40, "https://viewcrate.cc/c/y", rows, clock))
        restarted.resolved(series, "https://viewcrate.cc/open/a", "https://pixeldrain.com/u/a")
        val releases = cache().get(series)
        assertEquals(listOf("Extra - EP 01", "iQ - Episode 01-40"), releases.map { it.group })
        assertTrue(releases.all { it.files.single().url == "https://pixeldrain.com/u/a" })
        assertEquals("pixeldrain.com", MkvDramaDecoder.host(releases[1].files.single()))
        clock += MkvDramaCache.MAX_AGE
        assertTrue(cache().get(series).isEmpty())
        store[store.keys.single()] = "{not json"
        assertTrue(MkvDramaCache(store::get, { _, _ -> }).get(series).isEmpty())
    }
}
