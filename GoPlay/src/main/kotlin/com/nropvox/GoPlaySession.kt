package com.nropvox

import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * GoPlay answers every page request with an HTTP 404 Turnstile form until the client holds
 * its `d`/`locl` cookies. A browser that passes Turnstile POSTs the form back and receives
 * them; plain HTTP requests then work with those cookies and the same user agent.
 */
object GoPlaySession {
    private const val TAG = "GoPlay"
    private const val COOKIE_KEY = "goplay_cookies"
    private const val USER_AGENT_KEY = "goplay_user_agent"
    private const val SESSION_COOKIE = "d"
    private const val MAX_ATTEMPTS = 3
    private const val VERIFY_TIMEOUT = 60_000L

    // Real pages also load Turnstile's api.js, so match the access form itself.
    private val gateRegex = Regex(
        """\bid\s*=\s*["']?form-(?:ts|gr)\b|Generating Access Token""",
        RegexOption.IGNORE_CASE
    )

    private val refreshLock = Mutex()

    @Volatile
    private var cookies: Map<String, String> =
        CloudStreamApp.getKey<String>(COOKIE_KEY)?.let(::parseCookies).orEmpty()

    @Volatile
    private var userAgent: String? = CloudStreamApp.getKey<String>(USER_AGENT_KEY)

    val currentUserAgent: String get() = userAgent ?: USER_AGENT

    fun isGate(html: String) = gateRegex.containsMatchIn(html)

    suspend fun get(url: String, referer: String? = null): NiceResponse {
        repeat(MAX_ATTEMPTS) {
            val used = cookies
            val response = app.get(
                url,
                headers = mapOf("User-Agent" to currentUserAgent),
                referer = referer,
                cookies = used
            )
            if (!isGate(response.text)) {
                val updated = response.cookies.filterValues { it.isNotBlank() }
                if (updated.isNotEmpty() && used.isNotEmpty()) save(used + updated, currentUserAgent)
                return response
            }
            Log.d(TAG, "Access check returned for $url")
            refresh(used, url)
        }
        throw ErrorLoadingException("GoPlay verification failed. Open GoPlay in WebView, then retry.")
    }

    private suspend fun refresh(stale: Map<String, String>, url: String) = refreshLock.withLock {
        // Another request already replaced the cookies while this one waited.
        if (cookies != stale) return@withLock

        val origin = URI(url).let { "${it.scheme}://${it.host}" }

        // CloudStream's own WebView screen browses with USER_AGENT; reuse cookies it obtained.
        webViewCookies(origin).takeIf { SESSION_COOKIE in it && it != stale }?.let {
            Log.d(TAG, "Using cookies from WebView storage")
            save(it, USER_AGENT)
            return@withLock
        }

        expireWebViewCookies(origin)
        Log.d(TAG, "Solving access check in WebView")
        WebViewResolver(
            // Never exit based on url; finish once the session cookie exists.
            interceptUrl = Regex(".^"),
            additionalUrls = listOf(Regex(".")),
            // Turnstile needs the WebView's real user agent.
            userAgent = null,
            useOkhttp = false,
            timeout = VERIFY_TIMEOUT
        ).resolveUsingWebView("$origin/") {
            SESSION_COOKIE in webViewCookies(origin)
        }

        val solved = webViewCookies(origin)
        if (SESSION_COOKIE in solved) {
            save(solved, WebViewResolver.webViewUserAgent ?: USER_AGENT)
        } else {
            Log.w(TAG, "WebView did not receive GoPlay session cookies")
        }
    }

    private fun save(newCookies: Map<String, String>, newUserAgent: String) {
        cookies = newCookies
        userAgent = newUserAgent
        CloudStreamApp.setKey(COOKIE_KEY, newCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        CloudStreamApp.setKey(USER_AGENT_KEY, newUserAgent)
    }

    private fun webViewCookies(origin: String): Map<String, String> = try {
        CookieManager.getInstance().getCookie(origin)?.let(::parseCookies).orEmpty()
    } catch (e: Exception) {
        // CookieManager can throw on devices without a WebView implementation.
        emptyMap()
    }

    /** Stale GoPlay cookies would otherwise make a fresh solve look successful. */
    private fun expireWebViewCookies(origin: String) {
        try {
            val manager = CookieManager.getInstance()
            val domain = "." + URI(origin).host
            webViewCookies(origin).keys.forEach { name ->
                manager.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=$domain; Secure")
                manager.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure")
            }
            manager.flush()
        } catch (_: Exception) {
        }
    }

    private fun parseCookies(header: String): Map<String, String> =
        header.split(";").mapNotNull { part ->
            val name = part.substringBefore("=").trim()
            val value = part.substringAfter("=", "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
}
