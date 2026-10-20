package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class MkvResponse(val url: String, val text: String, val location: String?, val code: Int) {
    fun json(): JsonNode = mkvJson.readTree(text)
}

/** One extraction owns its cookies. Concurrent titles cannot overwrite each other's _akx. */
internal class MkvDramaSession(browserVerification: Boolean = true) {
    private val cookies = mutableListOf<Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(this@MkvDramaSession.cookies) {
            cookies.forEach { cookie ->
                this@MkvDramaSession.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this@MkvDramaSession.cookies.add(cookie)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        }
    }
    private val client = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .apply { if (browserVerification) addInterceptor(CloudflareKiller()) }.build()

    fun cookie(url: String, name: String): String? = synchronized(cookies) {
        cookies.lastOrNull { it.name == name && it.expiresAt > System.currentTimeMillis() &&
            URI(url).host.let { host -> host == it.domain || (!it.hostOnly && host.endsWith(".${it.domain}")) } &&
            URI(url).path.startsWith(it.path) }?.value
    }

    suspend fun request(
        url: String, referer: String? = null, json: JsonNode? = null,
        form: Map<String, String>? = null, post: Boolean = false, acceptJson: Boolean = false,
    ): MkvResponse = withContext(Dispatchers.IO) {
        require(MkvDramaDecoder.isHttp(url)) { "Invalid request URL" }
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept", if (acceptJson) "application/json" else "text/html,application/xhtml+xml")
            .header("Accept-Language", "*")
            .header("Sec-Fetch-Mode", "cors")
        referer?.let { builder.header("Referer", it) }
        if (post) {
            referer?.let { builder.header("Origin", URI(it).let { uri -> "${uri.scheme}://${uri.authority}" }) }
            val body = when {
                json != null -> json.toString().toRequestBody("application/json".toMediaType())
                form != null -> FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
                else -> ByteArray(0).toRequestBody(null)
            }
            builder.post(body)
        }
        client.newCall(builder.build()).execute().use { response ->
            val location = response.header("Location")?.let { URI(url).resolve(it).toString() }
            if (response.code in listOf(301, 302, 303, 307, 308) && location != null) {
                return@withContext MkvResponse(url, "", location, response.code)
            }
            check(response.code != 429) { "MKVDrama rate limited. Retry after ${response.header("Retry-After") ?: "60"} seconds." }
            check(response.isSuccessful) { "HTTP ${response.code}: browser verification or an unavailable mirror at ${URI(url).host}" }
            val type = response.header("Content-Type").orEmpty()
            check(!type.startsWith("video/") && !type.startsWith("application/octet-stream")) { "Unexpected media response" }
            val body = response.body
            // Never fetch an entire media file if a supposed HTML endpoint changes behavior.
            val source = body.source()
            val buffer = okio.Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, minOf(65536L, 5_000_001L - total))
                if (read == -1L) break
                total += read
                check(total <= 5_000_000L) { "Response exceeds the page size limit" }
            }
            MkvResponse(url, buffer.readUtf8(), null, response.code)
        }
    }

    suspend fun get(url: String, referer: String? = null, acceptJson: Boolean = false): MkvResponse {
        var next = url
        repeat(6) {
            val response = request(next, referer, acceptJson = acceptJson)
            if (response.location == null) return response
            next = response.location
        }
        error("Too many page redirects")
    }

    suspend fun panel(pageUrl: String): String {
        val response = get(pageUrl)
        val started = System.nanoTime()
        val document = Jsoup.parse(response.text, response.url)
        val root = document.selectFirst("#mlx-root") ?: return response.text
        if (root.selectFirst(".soraurlx a[href]") != null) return response.text
        val seed = request(response.url.trimEnd('/') + "/_vb3k_mnxr_w", response.url, post = true, acceptJson = true).json()
        val gatePath = seed.path("gate_path").asText()
        val gate = MkvDramaDecoder.sameSitePath(response.url, gatePath)
        val pass = MkvDramaDecoder.sameSitePath(response.url, seed.path("pass_path").asText())
        val field = MkvDramaDecoder.dynamicField(cookie(response.url, "_akx") ?: error("Missing download access cookie"), seed.path("dec_key").asText())
        val minimum = maxOf(2500L, root.attr("data-min-age").toLongOrNull() ?: 2500L)
        check(minimum <= 30_000L) { "Unexpected download verification delay" }
        delay(maxOf(0L, minimum - (System.nanoTime() - started) / 1_000_000L))
        val body = mkvJson.createObjectNode().putNull("r").put("i", true).put("w", false).put(field.first, field.second)
        request(gate, response.url, json = body, post = true, acceptJson = true)
        body.remove("i")
        val payload = request(pass, response.url, json = body, post = true, acceptJson = true).json()
        root.html(MkvDramaDecoder.access(payload, MkvDramaDecoder.panelKey(gatePath)))
        return document.outerHtml()
    }
}
