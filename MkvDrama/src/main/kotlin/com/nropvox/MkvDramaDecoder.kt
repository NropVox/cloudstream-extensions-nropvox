package com.nropvox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val mkvJson = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
internal fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

internal data class MkvRelease(val group: String, val quality: String, val mirror: String, val url: String)
/** A container row. [url] is a file-host URL, or a container's open URL until resolved; [host] is the row's displayed host. */
internal data class MkvFile(
    val url: String, val filename: String = "", val episode: String = "", val quality: String = "",
    val host: String = "", val offline: Boolean = false,
)
internal data class MkvEpisodeData(val url: String = "", val episode: Int? = null, val group: String? = null)
internal data class MkvSpan(val first: Int, val last: Int, val season: Int? = null)

/** Pure parsers shared by the provider and the fixture checks. */
internal object MkvDramaDecoder {
    // Okio ships with Cloudstream's HTTP stack; extension dependencies are not bundled in .cs3 files.
    fun decodeBase64(value: String): ByteArray = requireNotNull(value.decodeBase64()) { "Invalid Base64 payload" }.toByteArray()

    private val hostAliases = mapOf(
        "pixeldrain" to "pixeldrain.com", "gofile" to "gofile.io", "mega" to "mega.nz", "datanodes" to "datanodes.to",
        "send" to "send.now", "krakenfiles" to "krakenfiles.com", "mediafire" to "mediafire.com", "1fichier" to "1fichier.com",
        "filekeeper" to "filekeeper.net", "fileditch" to "fileditchvids.st", "vikingfile" to "vikingfile.com",
    )
    private val fileHosts = hostAliases.values.toSet()
    private val preferredHosts = listOf("pixeldrain.com", "gofile.io")
    val ouo = setOf("ouo.io", "ouo.press")
    val shorteners = ouo + setOf("oii.la", "clksz.com", "clk.sh", "exe.io", "exeygo.com", "exey.io", "exey.app")
    val viewcrate = setOf("viewcrate.cc")
    val filecrypt = setOf("filecrypt.cc", "filecrypt.co", "filecrypt.to")

    fun bareHost(url: String): String = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()
    fun isMedia(url: String) = runCatching { Regex("(?i)\\.(mkv|mp4|m3u8)$").containsMatchIn(URI(url).path) }.getOrDefault(false)
    fun isFileHost(url: String) = bareHost(url) in fileHosts || isMedia(url)
    fun isNative(url: String) = bareHost(url) in setOf("mkvdrama.net", "mkvdrama.org") &&
        runCatching { URI(url).path.matches(Regex("/c/[A-Za-z0-9_-]+/?")) }.getOrDefault(false)
    fun isContainer(url: String) = isNative(url) || bareHost(url) in filecrypt ||
        (bareHost(url) in viewcrate && runCatching { URI(url).path.startsWith("/c/") }.getOrDefault(false))

    /** The host serving a row: its URL's host once resolved, else its displayed host name. */
    fun host(file: MkvFile): String = bareHost(file.url).takeIf { it in fileHosts }
        ?: file.host.trim().lowercase().removePrefix("www.").let { hostAliases[it] ?: it }

    /** Release quality: 1080pHD beats any height, then the larger height (1080pHD > 2160p > 1080p > 720p). */
    fun rank(quality: String): Int {
        if (quality.trim().equals("1080pHD", true)) return Int.MAX_VALUE
        return Regex("""(?i)\s*(\d{3,4})p(?:HD)?\s*""").matchEntire(quality)?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** Rank a destination as a way to reach files; lower is better, null cannot be followed. */
    fun tier(url: String): Int? {
        val host = bareHost(url)
        return when {
            host in ouo -> 0
            isNative(url) -> 10
            host in viewcrate && isContainer(url) -> 11
            host in filecrypt -> 12
            host in shorteners -> 20
            isFileHost(url) -> 30 + preferredHosts.indexOf(host).let { if (it < 0) preferredHosts.size else it }
            else -> null
        }
    }

    /** Rows of one episode grouped by host: Pixeldrain, then Gofile, then others; offline rows last. */
    fun byHost(files: List<MkvFile>): List<List<MkvFile>> = files.groupBy(::host).values.sortedWith(
        compareBy({ group -> group.all { it.offline } }, { group -> preferredHosts.indexOf(host(group[0])).let { if (it < 0) preferredHosts.size else it } })
    )

    /** Release rows that cover the same episodes are alternatives, whichever source or quality. */
    fun bucket(group: String): String = (if (isExtra(group)) "extra:" else "") +
        (span(group)?.let { "${it.season ?: 1}:${it.first}-${it.last}" } ?: group.trim().lowercase())

    private val seasoned = Regex("""(?i)(?<![a-z0-9])S(\d+)E(\d+)(?:\s*[-–]\s*(?:S\d+)?E?(\d+))?(?!\d)""")
    private val plain = Regex("""(?i)(?<![a-z0-9])(?:episodes?|ep|e)[\s._-]*(\d+)(?:\s*[-–]\s*(?:ep?\s*)?(\d+))?(?!\d)""")
    fun isExtra(value: String) = Regex("(?i)\\b(extra|special|trailer|ova)s?\\b").containsMatchIn(value)

    fun span(value: String): MkvSpan? {
        seasoned.find(value)?.let {
            val first = it.groupValues[2].toInt()
            val last = it.groupValues[3].toIntOrNull() ?: first
            return MkvSpan(first, last, it.groupValues[1].toInt()).takeIf { first > 0 && last >= first }
        }
        val match = plain.find(value) ?: return null
        val first = match.groupValues[1].toInt()
        val last = match.groupValues[2].toIntOrNull() ?: first
        return MkvSpan(first, last).takeIf { first > 0 && last >= first }
    }

    fun matches(file: MkvFile, episode: Int?): Boolean {
        if (episode == null) return true
        if (isExtra(file.episode) || isExtra(file.filename)) return false
        // A filename is more specific than a collection's batch heading.
        val range = span(file.filename) ?: span(file.episode) ?: return false
        return range.first == episode && range.last == episode
    }

    fun releases(html: String, url: String): List<MkvRelease> {
        val document = Jsoup.parse(html, url)
        val root = document.selectFirst("#mlx-root") ?: document
        return root.select(".soraddlx").flatMap { group ->
            val heading = group.selectFirst(".sorattlx")?.text().orEmpty()
            group.select(".soraurlx").flatMap { row ->
                row.select("a[href]").mapNotNull { anchor ->
                    val href = anchor.absUrl("href")
                    if (isHttp(href)) MkvRelease(heading, row.selectFirst("strong")?.text().orEmpty(), anchor.text(), href) else null
                }
            }
        }
    }

    fun isHttp(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun sameSitePath(base: String, path: String): String {
        require(path.startsWith("/") && !path.startsWith("//") && '\\' !in path && '#' !in path) { "Invalid access endpoint" }
        return URI(base).resolve(path).toString().also { require(URI(it).authority == URI(base).authority) }
    }

    fun hex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[a-fA-F0-9]+")))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun gcm(ciphertext: String, key: ByteArray, nonce: ByteArray): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        String(doFinal(decodeBase64(ciphertext)), Charsets.UTF_8)
    }

    fun access(payload: JsonNode, key: ByteArray) = gcm(payload.path("d").asText(), key, hex(payload.path("s").asText()))
    fun panelKey(gatePath: String): ByteArray = MessageDigest.getInstance("SHA-256").digest("access-payload:$gatePath".toByteArray())

    fun dynamicField(cookie: String, key: String): Pair<String, String> {
        val normalized = cookie.replace('-', '+').replace('_', '/')
        val envelope = mkvJson.readTree(decodeBase64(normalized.padEnd((normalized.length + 3) / 4 * 4, '=')))
        val value = mkvJson.readTree(access(envelope, hex(key)))
        val name = value.path("n").asText()
        val field = value.path("v").asText()
        require(name.isNotBlank() && name !in setOf("r", "i", "w") && field.isNotBlank()) { "Invalid access cookie" }
        return name to field
    }

    private fun aes(mode: String, key: ByteArray, data: ByteArray, iv: ByteArray? = null): ByteArray = Cipher.getInstance(mode).run {
        if (iv == null) init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        else init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }

    /** Remove an optional PKCS#7 layer, then any NUL padding beneath it. */
    private fun unpad(bytes: ByteArray): ByteArray {
        val padding = bytes.lastOrNull()?.toInt()?.and(255) ?: 0
        val plain = if (padding in 1..16 && bytes.size >= padding && bytes.takeLast(padding).all { it.toInt().and(255) == padding })
            bytes.copyOf(bytes.size - padding) else bytes
        return plain.copyOf(plain.indexOfLast { it != 0.toByte() } + 1)
    }

    fun cnl(payload: JsonNode): List<String> {
        val match = Regex("""\s*function\s+f\s*\(\s*\)\s*\{\s*return\s+['"]([a-fA-F0-9]{32})['"]\s*;?\s*}\s*;?\s*""")
            .matchEntire(payload.path("jk").asText()) ?: error("Unsupported Click'n'Load key")
        val key = hex(match.groupValues[1])
        val plain = unpad(aes("AES/CBC/NoPadding", key, decodeBase64(payload.path("crypted").asText()), key))
        return String(plain, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { it.trim() }.toList()
            .also { require(it.isNotEmpty() && it.all(::isHttp)) { "Invalid exported URLs" } }
    }

    const val DLC_SERVICE = "https://service.jdownloader.org/dlcrypt/service.php"
    private val dlcServiceKey = hex("447e787351e60e2c6a96b3964be0c9bd")

    /** The trailing 88 characters are an opaque key token; only it goes to the key service. */
    fun dlcToken(container: String): String {
        val text = container.trim()
        require(text.length in 89..5_000_000) { "Invalid DLC container" }
        val token = text.takeLast(88)
        require(decodeBase64(token).size == 64) { "Invalid DLC key token" }
        return token
    }

    /** Decode a JDownloader DLC (format 20_02_2008, as served by ViewCrate and Filecrypt) with the key service's reply. */
    fun dlc(container: String, service: String): List<MkvFile> {
        val text = container.trim()
        dlcToken(text)
        val ciphertext = decodeBase64(text.dropLast(88).filterNot(Char::isWhitespace))
        require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0) { "Invalid DLC ciphertext" }
        val wrapped = Regex("""\s*<rc>([A-Za-z0-9+/=]+)</rc>\s*""").matchEntire(service)?.groupValues?.get(1)
            ?: error("DLC key service did not return a key")
        val key = decodeBase64(String(unpad(aes("AES/ECB/NoPadding", dlcServiceKey, decodeBase64(wrapped))), Charsets.US_ASCII))
        require(key.size >= 16) { "Invalid DLC content key" }
        val content = key.copyOf(16)
        val xml = String(decodeBase64(String(unpad(aes("AES/CBC/NoPadding", content, ciphertext, content)), Charsets.US_ASCII)
            .filterNot(Char::isWhitespace)), Charsets.UTF_8)
        require("<!DOCTYPE" !in xml.uppercase() && "<!ENTITY" !in xml.uppercase()) { "Unsupported DLC XML declaration" }
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        fun field(node: Element, name: String) = String(decodeBase64(node.selectFirst(name)?.text().orEmpty()), Charsets.UTF_8)
        val header = document.selectFirst("dlc > header") ?: error("Unsupported DLC XML")
        require(field(header, "dlcxmlversion") == "20_02_2008") { "Unsupported DLC XML version" }
        return document.select("dlc > content > package > file").map { node ->
            MkvFile(field(node, "url").also { require(isHttp(it)) { "Invalid DLC file URL" } }, field(node, "filename"))
        }.also { require(it.isNotEmpty()) { "DLC contains no files" } }
    }

    private fun cardName(value: String) = value.replace(Regex("(?i)\\d+(?:\\.\\d+)?\\s*(?:GB|MB)"), "")
        .replace(Regex("(?i)\\[mkvdrama\\.(?:net|org)]"), "").trim().lowercase()

    /** Give DLC entries the episode heading of the one card showing the same file name and host. */
    fun withHeadings(entries: List<MkvFile>, cards: List<MkvFile>): List<MkvFile> {
        val headings = cards.groupBy({ cardName(it.filename) to host(it) }, { it.episode })
        return entries.map { file ->
            headings[cardName(file.filename) to host(file)]?.distinct()?.singleOrNull()?.let { file.copy(episode = it) } ?: file
        }
    }

    /** Filecrypt's DLC download, named by the attribute its button's handler reads. */
    fun filecryptDlc(html: String, url: String): String? {
        val button = Jsoup.parse(html, url).selectFirst(".dlcdownload[onclick]") ?: return null
        val handler = button.attr("onclick")
        val id = Regex("""getAttribute\(\s*['"]([\w-]+)['"]""").find(handler)?.groupValues?.get(1)?.let(button::attr)
            ?: Regex("""DownloadDLC\(\s*['"]([A-Za-z0-9]+)['"]""").find(handler)?.groupValues?.get(1)
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,64}")) }?.let { URI(url).resolve("/DLC/$it.dlc").toString() }
    }

    fun unmask(config: JsonNode): ByteArray {
        require(config.isArray && config.size() == 2 && config[0].isArray && config[1].isArray && config[0].size() == config[1].size())
        return config[0].mapIndexed { i, left ->
            val right = config[1][i]
            require(left.isIntegralNumber && right.isIntegralNumber && left.asInt() in 0..255 && right.asInt() in 0..255)
            (left.asInt() xor right.asInt()).toByte()
        }.toByteArray()
    }

    fun viewcrateCards(html: String, url: String): List<MkvFile> {
        val root = Jsoup.parse(html, url).selectFirst("[data-open-base-url]") ?: return emptyList()
        val base = URI(url).resolve(root.attr("data-open-base-url")).toString().trimEnd('/')
        require(URI(base).authority == URI(url).authority && isHttp(base))
        return root.select("button[aria-label=Download link]").mapNotNull { button ->
            val values = button.attributes().filter { it.key.startsWith("data-") }.map { it.value }
            val id = values.firstNotNullOfOrNull { raw -> runCatching {
                val encoded = raw.replace('-', '+').replace('_', '/')
                String(decodeBase64(encoded.padEnd((encoded.length + 3) / 4 * 4, '=')), Charsets.US_ASCII)
                    .takeIf { it.matches(Regex("[a-f0-9]{16}")) }
            }.getOrNull() } ?: return@mapNotNull null
            val privacy = values.firstOrNull { it in setOf("noreferrer", "allow-referrer") } ?: "noreferrer"
            val row = button.parent()?.parent() ?: return@mapNotNull null
            val heading = row.parents().firstOrNull { it != root && it.selectFirst("h2") != null }?.selectFirst("h2")?.text().orEmpty()
            val spans = row.select("span")
            MkvFile("$base/$id?privacy=$privacy", spans.firstOrNull()?.text().orEmpty(), heading, host = spans.getOrNull(1)?.text().orEmpty())
        }
    }
}
