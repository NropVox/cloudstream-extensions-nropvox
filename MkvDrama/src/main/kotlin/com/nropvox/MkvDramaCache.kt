package com.nropvox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The path chosen for one release of a series: its container (blank for a direct file-host link) and every row it
 * listed. [first]..[last] is the release heading's episode span; [saved] is when the path was chosen.
 */
internal data class MkvCachedRelease(
    val group: String = "", val quality: String = "", val first: Int? = null, val last: Int? = null,
    val container: String = "", val files: List<MkvFile> = emptyList(), val saved: Long = 0,
)

internal data class MkvSeriesLinks(val releases: List<MkvCachedRelease> = emptyList())

/** Container paths and their file links per series, kept across app restarts so later episodes skip the panel and shorteners. */
internal class MkvDramaCache(
    private val read: (String) -> String? = { CloudStreamApp.getKey<String>(it) },
    private val write: (String, String) -> Unit = { key, value -> CloudStreamApp.setKey(key, value) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val memory = ConcurrentHashMap<String, List<MkvCachedRelease>>()
    private val lock = Mutex()

    private fun key(series: String) = "mkvdrama_links:" + URI(series).path.trim('/')

    private fun stored(series: String): List<MkvCachedRelease> = memory.getOrPut(key(series)) {
        runCatching { read(key(series))?.let { mkvJson.readValue(it, MkvSeriesLinks::class.java).releases } }.getOrNull().orEmpty()
    }

    /**
     * Releases chosen within [MAX_AGE]. Older choices are dropped so a better quality uploaded later is picked up; until
     * then a container is re-read rather than re-chosen when it lacks an episode.
     */
    fun get(series: String): List<MkvCachedRelease> = stored(series).filter { now() - it.saved < MAX_AGE }

    /** Replace the release covering the same episodes. */
    suspend fun put(series: String, release: MkvCachedRelease) = update(series) { releases ->
        releases.filterNot { MkvDramaDecoder.bucket(it.group) == MkvDramaDecoder.bucket(release.group) } + release
    }

    /** Record where a container row's open URL leads, so it is not requested again. */
    suspend fun resolved(series: String, open: String, url: String) = update(series) { releases ->
        releases.map { release -> release.copy(files = release.files.map { if (it.url == open) it.copy(url = url) else it }) }
    }

    private suspend fun update(series: String, change: (List<MkvCachedRelease>) -> List<MkvCachedRelease>) = lock.withLock {
        val releases = change(stored(series).filter { now() - it.saved < MAX_AGE })
        memory[key(series)] = releases
        try {
            write(key(series), mkvJson.writeValueAsString(MkvSeriesLinks(releases)))
        } catch (e: Exception) {
            Log.w("MkvDrama", "Link cache not saved: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
    }
}
