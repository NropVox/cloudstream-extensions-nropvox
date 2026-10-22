package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Plays one episode through a single path, as the bypass client's single-path export does: cached container rows
 * first, then the best release quality (1080pHD, then the greatest height), trying the next path only when one fails.
 */
internal class MkvDramaLinks(
    private val cache: MkvDramaCache,
    private val target: MkvEpisodeData,
    pixeldrainApiKey: String,
    private val subtitleCallback: (SubtitleFile) -> Unit,
    private val callback: (ExtractorLink) -> Unit,
) {
    private val session = MkvDramaSession()
    private val resolver = MkvDramaResolver(session, pixeldrainApiKey)
    // A container read once in this run is not read again by a later path.
    private val exported = mutableSetOf<String>()

    private data class Path(val release: MkvRelease, val url: String, val tier: Int)

    // Better destination first; among equals, the wider release, whose container serves more episodes from the cache.
    private val order = compareBy<Path>({ it.tier }, { path -> MkvDramaDecoder.span(path.release.group)?.let { it.first - it.last } ?: 0 })

    suspend fun play(): Boolean = cached() || released()

    /** Whether a release heading can hold the target: its own extra group, a movie, or a span containing the episode. */
    private fun holds(group: String, first: Int?, last: Int?): Boolean = when {
        target.group != null -> group == target.group
        MkvDramaDecoder.isExtra(group) -> false
        target.episode == null -> true
        else -> first != null && last != null && target.episode in first..last
    }

    private suspend fun cached(): Boolean {
        val releases = cache.get(target.url).sortedByDescending { MkvDramaDecoder.rank(it.quality) }
        for (release in releases) {
            val fits = if (target.group != null) release.group == target.group else !MkvDramaDecoder.isExtra(release.group)
            if (fits && release.files.any { MkvDramaDecoder.matches(it, target.episode) }) {
                Log.d("MkvDrama", "Cached ${release.group} ${release.quality}")
                if (play(release)) return true
            }
        }
        // A container may list the episode only now: its links died, or an ongoing batch grew past its heading.
        for (release in releases) {
            val growing = target.episode != null && target.group == null && !MkvDramaDecoder.isExtra(release.group) &&
                release.first != null && release.last != null && release.first < release.last && target.episode > release.last
            if (release.container.isBlank() || !(holds(release.group, release.first, release.last) || growing)) continue
            Log.d("MkvDrama", "Re-reading cached container of ${release.group}")
            if (attempt("Cached container") { read(release.container, release) } == true) return true
        }
        return false
    }

    private suspend fun released(): Boolean {
        val releases = MkvDramaDecoder.releases(session.panel(target.url), target.url)
            .filter { item -> MkvDramaDecoder.span(item.group).let { holds(item.group, it?.first, it?.last) } }
        // Lower qualities are tried only when every path of a better one fails.
        for (level in releases.groupBy { MkvDramaDecoder.rank(it.quality) }.toSortedMap(reverseOrder()).values) {
            val paths = coroutineScope {
                level.map { release ->
                    async { attempt("${release.quality} ${release.mirror}") { resolver.hop(release.url, target.url) }?.let { url ->
                        MkvDramaDecoder.tier(url)?.let { Path(release, url, it) }
                    } }
                }.awaitAll()
            }.filterNotNull()
            // All ouo mirrors are followed together: only landing reveals which container each holds.
            val (ouo, others) = paths.partition { it.tier == 0 }
            val landed = coroutineScope {
                ouo.map { path ->
                    async { attempt("${path.release.quality} ${path.release.mirror}") { resolver.land(path.url, target.url) }?.let { url ->
                        MkvDramaDecoder.tier(url)?.let { path.copy(url = url, tier = it) }
                    } }
                }.awaitAll()
            }.filterNotNull()
            for (path in landed.sortedWith(order) + others.sortedWith(order)) if (follow(path)) return true
        }
        return false
    }

    private suspend fun follow(path: Path): Boolean = attempt("${path.release.quality} ${MkvDramaDecoder.bareHost(path.url)}") {
        Log.d("MkvDrama", "Following ${path.release.group} ${path.release.quality} via ${MkvDramaDecoder.bareHost(path.url)}")
        val url = if (MkvDramaDecoder.bareHost(path.url) in MkvDramaDecoder.shorteners)
            resolver.land(path.url, target.url) ?: return@attempt false else path.url
        val span = MkvDramaDecoder.span(path.release.group)
        val release = MkvCachedRelease(path.release.group, path.release.quality, span?.first, span?.last, saved = System.currentTimeMillis())
        if (MkvDramaDecoder.isContainer(url)) return@attempt read(url, release)
        // The row keeps its release heading, so a batch never assigns a bare file-host link to one episode.
        val direct = release.copy(files = listOf(MkvFile(url, episode = path.release.group, quality = path.release.quality)))
        play(direct).also { if (it) cache.put(target.url, direct) }
    } == true

    /** Read a container, cache every row it lists for the series' later episodes, then play the target. */
    private suspend fun read(container: String, release: MkvCachedRelease): Boolean {
        if (!exported.add(container)) return false
        val files = resolver.export(container)
        Log.d("MkvDrama", "${MkvDramaDecoder.bareHost(container)} container lists ${files.size} files")
        if (files.isEmpty()) return false
        val listed = release.copy(container = container, files = files)
        cache.put(target.url, listed)
        return play(listed)
    }

    /** Play the target's rows from one host: Pixeldrain, else Gofile, else another; the next host only if one fails. */
    private suspend fun play(release: MkvCachedRelease): Boolean {
        val label = listOf(release.group, release.quality).filter { it.isNotBlank() }.joinToString(" · ")
        val referer = release.container.ifBlank { target.url }
        for (rows in MkvDramaDecoder.byHost(release.files.filter { MkvDramaDecoder.matches(it, target.episode) })) {
            var found = false
            for (row in rows) {
                found = attempt("${MkvDramaDecoder.host(row)} file") {
                    val url = resolver.destination(row.url, referer) ?: return@attempt false
                    if (url != row.url) cache.resolved(target.url, row.url, url)
                    resolver.emit(row.copy(url = url), target.episode, label, release.quality, referer, subtitleCallback, callback)
                } == true || found
            }
            if (found) return true
        }
        return false
    }

    private inline fun <T> attempt(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        Log.w("MkvDrama", "$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        null
    }
}
