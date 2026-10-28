package com.nropvox

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class SubtitleCat : ExtractorApi() {
    override val name = "SubtitleCat"
    override val mainUrl = "https://subtitlecat.com"
    override val requiresReferer = false

    private fun String.normalize(): String {
        return this.filter { c -> c.isLetterOrDigit() }.lowercase()
    }

    private val codeRegex = Regex("""[a-z]+-\d+""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = url.substringAfter("query=").let { codeRegex.find(it)?.value } ?: return
        subtitleUrls(query).forEach { subtitleCallback(newSubtitleFile("English", it)) }
    }

    // JavStream looks a code up here and then again through every page extractor it resolves, so the first
    // lookup of a code is shared with the calls that overlap or follow it.
    private suspend fun subtitleUrls(query: String): List<String> {
        val key = query.lowercase()
        val pending = CompletableDeferred<List<String>>()
        lookups.putIfAbsent(key, pending)?.let { return it.await() }
        return try {
            findSubtitleUrls(query).also { pending.complete(it) }
        } catch (e: Throwable) {
            lookups.remove(key)
            pending.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun findSubtitleUrls(query: String): List<String> {
        val queryUrl = "${mainUrl}/index.php?search=$query"
        val doc = app.get(queryUrl).document
        val subs = doc.select(".sub-table a")
            .map { URI("$mainUrl/").resolve(it.attr("href")).toString() }
            .filter {
                it.normalize().contains(query.normalize())
            }
            .distinct()
            .take(3)

        return coroutineScope {
            subs.map { subUrl ->
                async(Dispatchers.IO) {
                    try {
                        val subPageDoc = app.get(subUrl).document
                        val href = subPageDoc.getElementById("download_en")?.attr("href")
                            ?.takeIf { it.isNotBlank() } ?: return@async null
                        URI(subUrl).resolve(href).toString()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SubtitleCat", "Subtitle request failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        private val lookups = ConcurrentHashMap<String, Deferred<List<String>>>()
    }
}
