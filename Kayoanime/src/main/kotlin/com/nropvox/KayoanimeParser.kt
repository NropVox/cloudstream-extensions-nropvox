package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure helpers for kayoanime.com posts and the Drive file names behind them. A post is a whole series (or season
 * batch); its Drive buttons are the only episode source, and the file names are hand-written, so everything that can
 * guess wrong lives here where the tests can pin it down.
 */
object KayoanimeParser {
    /** A Drive link in a post. [label] is the button text (`Season 2`, `1080p [Private Drive]`, `Movie`). */
    data class DriveRef(val id: String, val isFolder: Boolean, val label: String)

    /**
     * A file found under a [DriveRef]; [label] is that button's text and [folders] the names of the folders it sits
     * in, nearest first. Both carry the season when the file name does not (`Oregairu 2 - 01.mkv` in `… S2`).
     */
    data class DriveFile(val id: String, val name: String, val label: String, val folders: List<String> = emptyList())

    /** One playable entry. Several files in [files] are alternative copies of the same episode. */
    data class Entry(val season: Int, val episode: Int, val title: String?, val files: List<DriveFile>)

    data class Details(val plot: String?, val tags: List<String>, val year: Int?, val minutes: Int?)

    private val driveId = Regex("""drive\.google\.com/(?:drive/(?:u/\d+/)?folders|file/d)/([\w-]+)""")
    private val postParam = Regex("""[?&]p=(\d+)""")
    private val extension = Regex("""\.([A-Za-z0-9]{2,4})$""")
    private val brackets = Regex("""\[[^\]]*\]|\([^)]*\)""")
    private val privateDrive = Regex("""\s*\[?\s*(?:private|drivate|provide)\s+drive\s*\]?""", RegexOption.IGNORE_CASE)

    // `Show Episode 3 English Subbed` / `Show 1080p Dual Audio HEVC | Episode 11` / `Show (Seasons 1-3 + OVAs) 1080p …`
    private val titleSuffixes = listOf(
        Regex("""\s*\|.*$"""),
        Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(\s*(?:Complete\s+Series|Seasons?\b)[^)]*\)"""),
        Regex("""\s+\d{3,4}p\b.*$"""),
    )

    fun cleanTitle(raw: String): String {
        var title = Parser.unescapeEntities(raw, false).trim()
        for (suffix in titleSuffixes) title = title.replace(suffix, "").trim()
        return title.ifEmpty { Parser.unescapeEntities(raw, false).trim() }
    }

    /** `https://kayoanime.com/?p=2941` -> 2941. The id is the only stable key; slugs are renamed with each episode. */
    fun postId(url: String): Int? = postParam.find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** `…/some-slug/?x=1` -> `some-slug`, for links that predate the `?p=` form. */
    fun slugOf(url: String): String? =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() && '.' !in it }

    /** The Drive buttons of a post, each folder or file once (several buttons often share one folder). */
    fun driveRefs(contentHtml: String): List<DriveRef> =
        Jsoup.parse(contentHtml).select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val id = driveId.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            DriveRef(id, "/folders/" in href, a.text().trim())
        }.distinctBy { it.id }

    fun isVideo(name: String): Boolean {
        val ext = extension.find(name)?.groupValues?.get(1)?.lowercase() ?: return true
        return ext !in NOT_VIDEO
    }

    private val NOT_VIDEO = setOf(
        "zip", "rar", "7z", "gz", "tar", "srt", "ass", "ssa", "vtt", "sub", "idx", "txt", "nfo", "md5", "sfv",
        "jpg", "jpeg", "png", "webp", "gif", "torrent", "pdf", "exe", "url", "html",
    )

    // ---- post body -----------------------------------------------------------------------------------------

    fun details(contentHtml: String): Details {
        val doc = Jsoup.parse(contentHtml)
        val info = doc.select("li").mapNotNull { li ->
            val text = li.text()
            val key = text.substringBefore(':', "").trim()
            if (key.isEmpty() || key.length > 16 || ':' !in text) null else key.lowercase() to text.substringAfter(':').trim()
        }.toMap()
        val tags = (info["genres"] ?: info["genre"]).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length < 30 }
        val year = (info["premiered"]?.let(::yearOf)) ?: info["aired"]?.let(::yearOf)
        return Details(plot(doc), tags, year, info["duration"]?.let(::minutes))
    }

    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")
    private fun yearOf(text: String): Int? = yearRegex.find(text)?.value?.toIntOrNull()

    private val hours = Regex("""(\d+)\s*hr""", RegexOption.IGNORE_CASE)
    private val mins = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)

    /** `23 min. per ep.` -> 23, `1 hr. 38 min.` -> 98, `Unknown` -> null. */
    fun minutes(text: String): Int? {
        val h = hours.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = mins.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    private val synopsisNoise = Regex("""\s*[\[(](?:Written by MAL Rewrite|Source: [^\])]*)[\])]""", RegexOption.IGNORE_CASE)
    private val episodeBlurb = Regex("""Episode\s+\d+\s+English\s+Subbed""", RegexOption.IGNORE_CASE)

    /** The synopsis is either the lead paragraph or a toggle named after the show; the toggles are not consistent. */
    private fun plot(doc: org.jsoup.nodes.Document): String? {
        val toggles = doc.select("div.toggle").filter { toggle ->
            val head = toggle.selectFirst("h3.toggle-head")?.text().orEmpty()
            val body = toggle.selectFirst("div.toggle-content")
            body != null && body.select("a, iframe, li").isEmpty() && !head.startsWith("Information", true) &&
                body.text().length > 80
        }
        val fromToggle = toggles.maxByOrNull { it.selectFirst("div.toggle-content")!!.text().length }
            ?.selectFirst("div.toggle-content")?.text()
        val lead = doc.select("body > p").firstOrNull()?.text()?.takeIf { it.length > 60 && !episodeBlurb.containsMatchIn(it) }
        return (fromToggle ?: lead)?.replace(synopsisNoise, "")?.trim()?.ifEmpty { null }
    }

    // ---- file names ----------------------------------------------------------------------------------------

    private val seasonEpisode = Regex("""(?<![A-Za-z0-9])[Ss]?(\d{1,2})[Ee](\d{1,3})(?![0-9])""")
    private val seasonWord = Regex("""(?:\bseason\s*|\bS)(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val ordinalSeason = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
    private val episodeWord = Regex("""\bep(?:isode)?\.?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val dashNumber = Regex("""[-–]\s*(\d{1,4})(?:v\d+)?(?=\s|\.|$|-)""")
    private val specialWord = Regex(
        """(?<![A-Za-z])(?:OVAs?|OADs?|ONAs?|NCOP|NCED|SPECIALS?|MOVIES?|RECAP|OMAKE|EXTRAS?|TRAILER|PV|BONUS)(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val batchName = Regex("""\bseasons?\s*\d""", RegexOption.IGNORE_CASE)
    private val specialNumber = Regex("""(?:OVA|OAD|ONA|Movie|Special|SP)s?\s*#?(\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /** What a file name says about itself; [season] and [episode] are null where it says nothing. */
    data class Slot(val season: Int?, val episode: Int?, val special: Boolean)

    fun slot(fileName: String, label: String, folders: List<String> = emptyList()): Slot {
        val base = fileName.replace(extension, "")
        val plain = base.replace(brackets, " ").replace(Regex("""\s+"""), " ").trim()
        seasonEpisode.find(plain)?.let { return Slot(it.groupValues[1].toInt(), it.groupValues[2].toInt(), false) }

        val context = folders + label
        // Only a short name that is not a batch title (`Season 1 + OVAs`, `Seasons 1-3 + OVAs`) makes its files special.
        val special = specialWord.containsMatchIn(base) ||
            context.any { it.length <= 30 && specialWord.containsMatchIn(it) && !batchName.containsMatchIn(it) }
        if (special) {
            val number = specialNumber.find(base)?.groupValues?.get(1)?.toIntOrNull() ?: lastDashNumber(plain)
            return Slot(0, number, true)
        }
        val season = seasonNumber(plain) ?: context.firstNotNullOfOrNull(::seasonNumber)
        val episode = episodeWord.find(plain)?.groupValues?.get(1)?.toIntOrNull() ?: lastDashNumber(plain)
        return Slot(season, episode, false)
    }

    private fun lastDashNumber(text: String): Int? = dashNumber.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private fun seasonNumber(text: String): Int? =
        (seasonWord.find(text) ?: ordinalSeason.find(text))?.groupValues?.get(1)?.toIntOrNull()

    /** `[Kayoanime] Blue Lock - S01E01 [1080p]….mkv` -> `Blue Lock - S01E01`; names specials in the episode list. */
    fun displayName(fileName: String): String =
        fileName.replace(extension, "").replace(brackets, " ").replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

    /**
     * Turns the files of a post into numbered entries. Files that name no season take the season of their button
     * (`Season 2`), else 1; specials, movies and OVAs go to season 0. Two files claiming the same slot are kept
     * together as alternative sources, because they are usually two releases of one episode.
     */
    fun entries(files: List<DriveFile>): List<Entry> {
        val placed = files.filter { isVideo(it.name) }.map { file ->
            val slot = slot(file.name, file.label, file.folders)
            file to Slot(slot.season ?: if (slot.special) 0 else 1, slot.episode, slot.special)
        }
        val nextFree = HashMap<Int, Int>()
        placed.forEach { (_, s) -> if (s.episode != null) nextFree.merge(s.season!!, s.episode + 1, ::maxOf) }

        val bySlot = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<DriveFile, Slot>>>()
        for ((file, s) in placed) {
            val number = s.episode ?: (nextFree[s.season!!] ?: 1).also { nextFree[s.season] = it + 1 }
            bySlot.getOrPut(s.season!! to number) { mutableListOf() }.add(file to s)
        }
        return bySlot.map { (key, group) ->
            val (season, number) = key
            val title = if (season == 0) displayName(group.first().first.name) else null
            Entry(season, number, title, group.map { it.first })
        }.sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
    }

    // ---- links ---------------------------------------------------------------------------------------------

    /** Episode `data`: `https://drive.google.com/file/d/<id>/view#<name>&<label>`, copies separated by `|`. */
    fun encodeSources(files: List<DriveFile>): String = files.joinToString("|") {
        "https://drive.google.com/file/d/${it.id}/view#${enc(it.name)}&${enc(it.label)}"
    }

    fun decodeSources(data: String): List<DriveFile> = data.split('|').mapNotNull { part ->
        val id = driveId.find(part)?.groupValues?.get(1) ?: return@mapNotNull null
        val fragment = part.substringAfter('#', "")
        DriveFile(id, dec(fragment.substringBefore('&')), dec(fragment.substringAfter('&', "")))
    }

    private val resolution = Regex("""(?<![0-9])(2160|1440|1080|720|576|480|360)p""", RegexOption.IGNORE_CASE)

    /** Height from the file name, else from the button label (`1080p [Private Drive]`); null when neither says. */
    fun quality(fileName: String, label: String): Int? =
        (resolution.find(fileName) ?: resolution.find(label))?.groupValues?.get(1)?.toIntOrNull()
            ?: if (Regex("""\b4k\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) 2160 else null

    /** `1080p · HEVC 10bit · Dual Audio` style tags, in a fixed order. */
    fun tags(fileName: String): List<String> {
        val codec = when {
            Regex("""hevc|x265|h\.?265""", RegexOption.IGNORE_CASE).containsMatchIn(fileName) -> "HEVC"
            Regex("""x264|h\.?264|avc""", RegexOption.IGNORE_CASE).containsMatchIn(fileName) -> "H.264"
            else -> null
        }
        val tenBit = Regex("""10\s*-?\s*bit""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)
        val audio = when {
            Regex("""dual[\s._-]*audio""", RegexOption.IGNORE_CASE).containsMatchIn(fileName) -> "Dual Audio"
            Regex("""\bdual\b""", RegexOption.IGNORE_CASE).containsMatchIn(fileName) -> "Dual Audio"
            else -> null
        }
        val subs = if (Regex("""multi[\s._-]*subs?""", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) "Multi Subs" else null
        return listOfNotNull(codec?.let { if (tenBit) "$it 10bit" else it }, audio, subs)
    }

    /** The button text without the `[Private Drive]` marker (and its typos), for naming alternative copies. */
    fun shortLabel(label: String): String = label.replace(privateDrive, "").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
