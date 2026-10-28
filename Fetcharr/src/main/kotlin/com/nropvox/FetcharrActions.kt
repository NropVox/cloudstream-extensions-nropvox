package com.nropvox

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Fetcharr"

private val RESULT_FRAGMENTS = setOf(
    "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
    "com.lagradost.cloudstream3.ui.result.ResultFragmentTv",
)

/** Direct files, HLS and DASH; torrents and magnets are never resolved for these actions. */
private val SENDABLE_TYPES = setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)

/** A link Fetcharr can download: one http(s) URL plus one header map, no DRM and no separate audio. */
private fun ExtractorLink.isSendable(): Boolean =
    this !is DrmExtractorLink && audioTracks.isEmpty() && type in SENDABLE_TYPES &&
        FetcharrPayloadBuilder.isHttpUrl(url)

private fun ExtractorLink.toInfo() = LinkInfo(source, name, quality, url, referer, headers)

private fun SubtitleData.toInfo() = SubtitleInfo(url, headers, getIETF_tag(), origin == SubtitleOrigin.URL)

private fun ResultEpisode.meta(year: Int?) = MediaMeta(
    showName = headerName,
    episodeName = name,
    season = season,
    episode = episode,
    isMovie = tvType.isMovieType(),
    year = year,
)

/**
 * Base for both actions: finds the open result screen behind the clicked episode and launches Fetcharr.
 * The result screen isn't part of the plugin API, so everything read from it is optional.
 */
abstract class FetcharrAction : VideoClickAction() {
    override val oneSource = true
    override val sourceTypes = SENDABLE_TYPES

    /** The view model of the result screen showing [video], or null if it has been closed. */
    protected suspend fun resultViewModel(video: ResultEpisode): ResultViewModel2? = uiThread {
        val activity = CommonActivity.activity as? FragmentActivity ?: return@uiThread null
        resultFragments(activity.supportFragmentManager)
            .map { ViewModelProvider(it)[ResultViewModel2::class.java] }
            .firstOrNull { vm -> vm.showsEpisode(video) }
    }

    private fun resultFragments(fm: FragmentManager): List<Fragment> = fm.fragments.flatMap { fragment ->
        val self = if (fragment.isResultScreen() && fragment.isAdded) listOf(fragment) else emptyList()
        self + resultFragments(fragment.childFragmentManager)
    }

    /** By name: referencing the fragment classes would need their ViewBinding supertypes on the classpath. */
    private fun Fragment.isResultScreen(): Boolean =
        generateSequence<Class<*>>(javaClass) { it.superclass }.any { it.name in RESULT_FRAGMENTS }

    private fun ResultViewModel2.showsEpisode(video: ResultEpisode): Boolean {
        val episodes = (episodes.value as? Resource.Success)?.value.orEmpty()
        val movie = (movie.value as? Resource.Success)?.value?.second
        return movie?.id == video.id || episodes.any { it.id == video.id }
    }

    protected fun ResultViewModel2.year(context: Context?): Int? =
        FetcharrPayloadBuilder.yearFrom((page.value as? Resource.Success)?.value?.yearText?.asStringNull(context))

    protected fun selectedLink(result: LinkLoadingResult, index: Int?): ExtractorLink {
        val link = index?.let { result.links.getOrNull(it) } ?: throw ErrorLoadingException("No link selected")
        if (!link.isSendable()) {
            throw ErrorLoadingException("${link.name} can't be sent to Fetcharr (DRM, separate audio or not http)")
        }
        return link
    }

    protected suspend fun send(payload: FetcharrPayload) {
        val json = FetcharrPayloadBuilder.toJson(payload)
        val intent = Intent(Intent.ACTION_VIEW, FetcharrPayloadBuilder.URI.toUri()).apply {
            setPackage(FetcharrPayloadBuilder.PACKAGE)
            putExtra(FetcharrPayloadBuilder.EXTRA, Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
        // ActivityNotFoundException (Fetcharr not installed) is turned into a toast by runActionSafe.
        launch(intent)
    }
}

/** Sends the one movie/episode and the source the user picked. */
class SendToFetcharrAction : FetcharrAction() {
    override val name = txt("Send to Fetcharr")

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean = video != null

    override suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?) {
        val link = selectedLink(result, index)
        val year = resultViewModel(video)?.year(context)
        send(
            FetcharrPayloadBuilder.single(
                video.meta(year),
                link.toInfo(),
                result.subs.map { it.toInfo() },
                USER_AGENT,
            )
        )
    }
}

/**
 * Sends every episode of the clicked episode's season that the result screen lists (the selected season and
 * episode range, like Cloudstream's own "Download all"). The user picks a source on the clicked episode; the
 * other episodes are resolved here and get the closest matching source. Fetcharr's sheet lets the user
 * deselect episodes before anything is queued.
 */
class SendSeasonToFetcharrAction : FetcharrAction() {
    override val name = txt("Send season to Fetcharr")

    private val running = AtomicBoolean(false)

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean =
        video != null && !video.tvType.isMovieType()

    override suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?) {
        val template = selectedLink(result, index)
        if (!running.compareAndSet(false, true)) {
            CommonActivity.showToast("Fetcharr: still resolving the previous season", Toast.LENGTH_SHORT)
            return
        }
        try {
            sendSeason(context, video, result, template)
        } finally {
            running.set(false)
        }
    }

    private suspend fun sendSeason(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        template: ExtractorLink,
    ) {
        val viewModel = resultViewModel(video)
        val listed = (viewModel?.episodes?.value as? Resource.Success)?.value.orEmpty()
            .filter { it.parentId == video.parentId && it.season == video.season && it.id != video.id }
        val others = listed.distinctBy { it.episode }.filter { it.episode != video.episode }

        if (others.isNotEmpty()) {
            CommonActivity.showToast("Fetcharr: resolving links for ${others.size + 1} episodes…", Toast.LENGTH_SHORT)
        }
        val templateInfo = template.toInfo()
        val resolved = resolveAll(others)

        val entries = mutableListOf(SeasonEntry(video.episode, video.name, templateInfo, result.subs.map { it.toInfo() }))
        val missing = mutableListOf<Int>()
        for ((episode, links) in resolved) {
            val sendable = links.links.filter { it.isSendable() }
            val match = FetcharrPayloadBuilder.pickMatching(templateInfo, sendable) { it.toInfo() }
            if (match == null) {
                missing += episode.episode
            } else {
                entries += SeasonEntry(episode.episode, episode.name, match.toInfo(), links.subs.map { it.toInfo() })
            }
        }

        // Navigated away while resolving: don't pop Fetcharr over whatever the user is doing now.
        if (others.isNotEmpty() && resultViewModel(video) == null) {
            Log.i(TAG, "Result screen closed while resolving; not sending")
            return
        }

        send(
            FetcharrPayloadBuilder.batch(
                video.headerName,
                video.season,
                viewModel?.year(context),
                entries,
                USER_AGENT,
            ),
        )
        if (missing.isNotEmpty()) {
            CommonActivity.showToast(
                "Fetcharr: no sendable link for episode ${missing.sorted().joinToString()}",
                Toast.LENGTH_LONG,
            )
        }
    }

    /** Resolves links like the app's own loader, two episodes at a time (more stalls Cloudflare-guarded hosts). */
    private suspend fun resolveAll(episodes: List<ResultEpisode>): List<Pair<ResultEpisode, LinkLoadingResult>> {
        val permits = Semaphore(2)
        return coroutineScope {
            episodes.map { episode ->
                async { permits.withPermit { episode to resolve(episode) } }
            }.awaitAll()
        }
    }

    private suspend fun resolve(episode: ResultEpisode): LinkLoadingResult {
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleData>()
        // Keep whatever arrived if an extractor hangs; the player would let the user skip loading too.
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            try {
                RepoLinkGenerator(listOf(episode)).generateLinks(
                    clearCache = false,
                    sourceTypes = SENDABLE_TYPES,
                    callback = { (link, _) -> if (link != null) synchronized(links) { links += link } },
                    subtitleCallback = { sub -> synchronized(subs) { subs += sub } },
                    offset = 0,
                    isCasting = false,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Episode ${episode.episode}: ${e.message}")
            }
        }
        return LinkLoadingResult(synchronized(links) { links.toList() }, synchronized(subs) { subs.toList() }, hashMapOf())
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 60_000L
    }
}
