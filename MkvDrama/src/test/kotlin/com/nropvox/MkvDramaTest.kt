package com.nropvox

import kotlinx.coroutines.runBlocking
import java.util.Base64
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

class MkvDramaTest {
    @Test fun base64HandlesStandardAndUrlSafePayloadsWithoutOptionalCryptoLibraries() {
        assertArrayEquals("foobar".toByteArray(), MkvDramaDecoder.decodeBase64("Zm9vYmFy"))
        assertArrayEquals(byteArrayOf(-5, -1), MkvDramaDecoder.decodeBase64("+/8="))
        assertArrayEquals(byteArrayOf(-5, -1), MkvDramaDecoder.decodeBase64("-_8"))
        assertArrayEquals("f".toByteArray(), MkvDramaDecoder.decodeBase64(" Zg==\n"))
        assertThrows(IllegalArgumentException::class.java) { MkvDramaDecoder.decodeBase64("%%%") }
    }

    @Test fun batchFilesAreNeverReturnedAsIndividualEpisodes() {
        assertEquals(MkvSpan(1, 40), MkvDramaDecoder.span("NF - Episode 01 - 40"))
        assertEquals(MkvSpan(5, 5, 2), MkvDramaDecoder.span("Show.S02E05.1080p.mkv"))
        assertFalse(MkvDramaDecoder.matches(MkvFile("", "", "Episode 01-40"), 1))
        assertTrue(MkvDramaDecoder.matches(MkvFile("", "Show.S01E03.mkv", "Episode 01-40"), 3))
        assertFalse(MkvDramaDecoder.matches(MkvFile("", "Show.S01E03.mkv", "Episode 01-40"), 4))
        assertFalse(MkvDramaDecoder.matches(MkvFile("", "", "Extra - EP 01"), 1))
        assertFalse(MkvDramaDecoder.matches(MkvFile("", "Unnumbered.mkv", ""), 1))
    }

    @Test fun authoritativePanelPreservesSourcesQualitiesAndEveryMirror() {
        val html = """<div class="soraddlx"><div class="soraurlx"><a href="/ad">Ad</a></div></div>
            <div id="mlx-root"><div class="soraddlx"><div class="sorattlx">NF - Episode 01-40</div>
            <div class="soraurlx"><strong>1080pHD</strong><a href="/a">Link 1</a><a href="/b">Link 2</a>
            <a href="javascript:alert(1)">Bad</a></div></div></div>"""
        val links = MkvDramaDecoder.releases(html, "https://mkvdrama.net/title")
        assertEquals(listOf("https://mkvdrama.net/a", "https://mkvdrama.net/b"), links.map { it.url })
        assertEquals(listOf("1080pHD", "1080pHD"), links.map { it.quality })
        assertTrue(links.all { it.group == "NF - Episode 01-40" })
    }

    @Test fun gcmAuthenticatesCookieAndPanel() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 20).toByte() }
        val plaintext = """{"n":"dynamic","v":"fixture"}"""
        val bytes = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext.toByteArray())
        }
        val payload = mkvJson.createObjectNode().put("d", Base64.getEncoder().encodeToString(bytes))
            .put("s", nonce.joinToString("") { "%02x".format(it) })
        assertEquals(plaintext, MkvDramaDecoder.access(payload, key))
        val cookie = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        assertEquals("dynamic" to "fixture", MkvDramaDecoder.dynamicField(cookie, key.joinToString("") { "%02x".format(it) }))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        payload.put("d", Base64.getEncoder().encodeToString(bytes))
        assertThrows(Exception::class.java) { MkvDramaDecoder.access(payload, key) }
    }

    @Test fun seedPathsAndMaskedBytesAreValidated() {
        for (path in listOf("//evil.example/gate", "https://evil.example/gate", "/\\evil", "/gate#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { MkvDramaDecoder.sameSitePath("https://mkvdrama.net/title", path) }
        }
        assertEquals("https://mkvdrama.net/title/gate", MkvDramaDecoder.sameSitePath("https://mkvdrama.net/title", "/title/gate"))
        assertArrayEquals(byteArrayOf(3, 7), MkvDramaDecoder.unmask(mkvJson.readTree("[[1,3],[2,4]]")))
        assertThrows(IllegalArgumentException::class.java) { MkvDramaDecoder.unmask(mkvJson.readTree("[[999],[1]]")) }
        assertThrows(IllegalArgumentException::class.java) { MkvDramaDecoder.unmask(mkvJson.readTree("[[1,2],[1]]")) }
    }

    @Test fun clickNLoadAcceptsLiteralKeysAndRejectsExecutableExpressions() {
        val key = ByteArray(16) { it.toByte() }
        val text = "https://pixeldrain.com/u/fixture\n"
        val data = text.toByteArray().let { it.copyOf((it.size + 15) / 16 * 16) }
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            doFinal(data)
        }
        val export = mkvJson.createObjectNode().put("jk", "function f(){return '000102030405060708090a0b0c0d0e0f';}")
            .put("crypted", Base64.getEncoder().encodeToString(encrypted))
        assertEquals(listOf(text.trim()), MkvDramaDecoder.cnl(export))
        export.put("jk", "function f(){return fetch('https://evil.example');}")
        assertThrows(IllegalStateException::class.java) { MkvDramaDecoder.cnl(export) }
    }

    @Test fun randomizedViewcrateButtonsRetainEpisodeAndFilename() {
        val html = """<div data-open-base-url="/open"><section><h2>S01E02</h2><div><div>
            <span>Show.S01E02.mkv</span><div><span>pixeldrain.com</span>
            <button aria-label="Download link" data-random="MDAwMDAwMDAwMDAwMDAwMQ" data-mode="noreferrer"></button>
            </div></div></div></section></div>"""
        val files = MkvDramaDecoder.viewcrateCards(html, "https://viewcrate.cc/c/example")
        assertEquals(1, files.size)
        assertEquals("https://viewcrate.cc/open/0000000000000001?privacy=noreferrer", files.single().url)
        assertTrue(MkvDramaDecoder.matches(files.single(), 2))
        assertFalse(MkvDramaDecoder.matches(files.single(), 1))
    }

    @Test fun realHttpProtocolAgainstPostman() = runBlocking {
        val base = System.getenv("MKVDRAMA_SIMULATION_URL")
        assumeTrue("Start the local Postman simulation to exercise HTTP", !base.isNullOrBlank())
        val client = MkvDramaSession(browserVerification = false)
        val url = "$base/series/title"
        val links = MkvDramaDecoder.releases(client.panel(url), url)
        assertEquals(1, links.size)
        assertEquals("1080pHD", links.single().quality)
        val redirect = client.request(links.single().url, url)
        assertEquals(307, redirect.code)
        assertEquals("https://mega.nz/file/invented#fixture-key", redirect.location)
        assertEquals("", redirect.text)
        for (fixture in listOf("tampered", "bad-seed", "bad-cookie", "blocked")) {
            var rejected = false
            try { MkvDramaSession(false).panel("$base/series/$fixture") } catch (_: Exception) { rejected = true }
            assertTrue("$fixture must be rejected", rejected)
        }
    }
}
