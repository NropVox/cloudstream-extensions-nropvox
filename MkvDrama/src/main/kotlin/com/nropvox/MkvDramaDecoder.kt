package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val mkvJson = jacksonObjectMapper()
internal fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }

internal data class MkvRelease(val group: String, val quality: String, val mirror: String, val url: String)
internal data class MkvFile(val url: String, val filename: String = "", val episode: String = "", val quality: String = "")
internal data class MkvEpisodeData(val url: String = "", val episode: Int? = null, val group: String? = null)
internal data class MkvSpan(val first: Int, val last: Int, val season: Int? = null)

/** Pure parsers shared by the provider and the fixture checks. */
internal object MkvDramaDecoder {
    // Okio ships with Cloudstream's HTTP stack; extension dependencies are not bundled in .cs3 files.
    fun decodeBase64(value: String): ByteArray = requireNotNull(value.decodeBase64()) { "Invalid Base64 payload" }.toByteArray()

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

    fun cnl(payload: JsonNode): List<String> {
        val match = Regex("""\s*function\s+f\s*\(\s*\)\s*\{\s*return\s+['"]([a-fA-F0-9]{32})['"]\s*;?\s*}\s*;?\s*""")
            .matchEntire(payload.path("jk").asText()) ?: error("Unsupported Click'n'Load key")
        val key = hex(match.groupValues[1])
        val bytes = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            doFinal(decodeBase64(payload.path("crypted").asText()))
        }
        val padding = bytes.lastOrNull()?.toInt()?.and(255) ?: 0
        val plain = if (padding in 1..16 && bytes.takeLast(padding).all { it.toInt().and(255) == padding }) bytes.dropLast(padding).toByteArray() else bytes
        return String(plain, Charsets.UTF_8).trimEnd('\u0000').lineSequence().filter { it.isNotBlank() }.map { it.trim() }.toList()
            .also { require(it.isNotEmpty() && it.all(::isHttp)) { "Invalid exported URLs" } }
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
            MkvFile("$base/$id?privacy=$privacy", row.select("span").firstOrNull()?.text().orEmpty(), heading)
        }
    }
}
