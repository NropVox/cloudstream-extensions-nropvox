package com.nropvox

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs streamcorner.st's own code in hidden WebViews. The data API's request and reply are sealed by an obfuscated
 * worker (JS + WASM) that changes per build, and embeds decode their `?p=` token in obfuscated page script, so the
 * plugin lets the site's code do both and only speaks the worker's plain message protocol.
 *
 * Pages are served from `<origin>/__cs/…` URLs answered in [WebViewClient.shouldInterceptRequest], so they run on
 * the site's real origin: the worker loads same-origin and embeds that check `frame-ancestors` accept the frame.
 */
internal class StreamCornerBridge(private val context: Context, private val origin: String) {
    /** A playlist request seen inside an embed, with the headers the player sent for it. */
    data class Manifest(val url: String, val headers: Map<String, String>, val userAgent: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val setup = Mutex()
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    @Volatile private var worker: WebView? = null
    @Volatile private var workerPath: String? = null
    private var idle: Job? = null

    private val bridgeUrl get() = "$origin/__cs/bridge.html"
    private val playerUrl get() = "$origin/__cs/player.html"

    /** Posts one message (JSON object text without `requestId`) to the site's worker and returns its raw reply. */
    suspend fun call(path: String, message: String): String {
        val view = worker(path)
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<String>().also { pending[id] = it }
        try {
            withContext(Dispatchers.Main) {
                view.evaluateJavascript("csCall($id,$message)", null)
                keepAlive()
            }
            return withTimeout(CALL_TIMEOUT_MS) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun worker(path: String): WebView = setup.withLock {
        worker?.takeIf { workerPath == path }?.let { return it }
        withContext(Dispatchers.Main) { dropWorker() }
        val ready = CompletableDeferred<Unit>()
        val html = StreamCornerParser.bridgeHtml(path)
        val view = withContext(Dispatchers.Main) {
            newView(bridgeUrl, html, onRequest = null).apply {
                addJavascriptInterface(Js(ready), "CsBridge")
                loadUrl(bridgeUrl)
            }
        }
        try {
            withTimeout(LOAD_TIMEOUT_MS) { ready.await() }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.Main) { view.destroy() }
            throw IllegalStateException("StreamCorner's decoder didn't start: ${e.message}", e)
        }
        worker = view
        workerPath = path
        view
    }

    /** Called from the WebView's JavaBridge thread. */
    private inner class Js(private val ready: CompletableDeferred<Unit>) {
        @JavascriptInterface fun ready() {
            ready.complete(Unit)
        }

        @JavascriptInterface fun reply(id: Int, json: String) {
            pending.remove(id)?.complete(json)
        }

        @JavascriptInterface fun failed(message: String) {
            Log.w(TAG, "worker: $message")
            val error = IllegalStateException("StreamCorner's decoder failed: $message")
            ready.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            scope.launch { setup.withLock { dropWorker() } }
        }
    }

    /** The idle worker WebView holds tens of MB; let it go a few minutes after the last call. */
    private fun keepAlive() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            setup.withLock { dropWorker() }
        }
    }

    private fun dropWorker() {
        worker?.destroy()
        worker = null
        workerPath = null
    }

    /** A separate short-lived WebView executes the site's two pure WASM decoders. */
    suspend fun decode(payload: StreamCornerDrm.Module, keys: StreamCornerDrm.Module, token: String): SCDrmStream {
        val result = CompletableDeferred<String>()
        val url = "$origin/__cs/decoder.html"
        val html = StreamCornerParser.drmHtml(payload, keys, token)
        var view: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                val decoder = newView(url, html, onRequest = { _, _, _ ->
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                })
                view = decoder
                decoder.addJavascriptInterface(object {
                    @JavascriptInterface fun reply(json: String) { result.complete(json) }
                    @JavascriptInterface fun failed(message: String) {
                        result.completeExceptionally(IllegalStateException(message))
                    }
                }, "CsDecoder")
                decoder.loadUrl(url)
            }
            return StreamCornerParser.drmStream(withTimeout(LOAD_TIMEOUT_MS) { result.await() })
        } finally {
            view?.let { withContext(NonCancellable + Dispatchers.Main) { it.stopLoading(); it.destroy() } }
        }
    }

    /**
     * Frames [embedUrl] in a site-origin page and returns the first HLS playlist its player requests, answered with
     * a 404 so nothing plays in the background. Null when the player loads a DASH manifest instead: those are
     * DRM-protected feeds (Amazon CENC) the app can't play. The WebView is destroyed right after.
     */
    suspend fun manifest(embedUrl: String, timeoutMs: Long): Manifest? {
        val found = CompletableDeferred<Manifest?>()
        var view: WebView? = null
        try {
            view = withContext(Dispatchers.Main) {
                newView(playerUrl, StreamCornerParser.playerHtml(embedUrl)) { request, url, userAgent ->
                    when {
                        StreamCornerParser.isManifest(url) -> {
                            found.complete(Manifest(url, request.requestHeaders.orEmpty(), userAgent))
                            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        StreamCornerParser.isDash(url) -> {
                            if (found.complete(null)) Log.w(TAG, "DRM DASH from $embedUrl: $url")
                            null
                        }
                        else -> null
                    }
                }.apply { loadUrl(playerUrl) }
            }
            return withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            view?.let { withContext(NonCancellable + Dispatchers.Main) { it.stopLoading(); it.destroy() } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newView(
        pageUrl: String,
        html: String,
        onRequest: ((WebResourceRequest, String, String) -> WebResourceResponse?)?,
    ): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Unattached views have no size; give the framed players a viewport to lay out in.
        layout(0, 0, 1280, 720)
        val userAgent = settings.userAgentString
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url == pageUrl) return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
                return onRequest?.invoke(request, url, userAgent)
            }

            // Pop-unders and the devtools trap navigate the top frame away; the page we served must stay.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && request.url.toString() != pageUrl
        }
    }

    companion object {
        private const val TAG = "StreamCorner"
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val CALL_TIMEOUT_MS = 25_000L
        private const val IDLE_MS = 3 * 60_000L
    }
}
