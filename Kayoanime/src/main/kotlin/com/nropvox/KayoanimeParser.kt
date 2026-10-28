package com.nropvox

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure helpers for kayoanime.com posts and the file names behind them. A post is a whole series (or season batch, or a
 * hub of several shows); its buttons are the only episode source, and the file names are hand-written, so everything
 * that can guess wrong lives here where the tests can pin it down.
 */
object KayoanimeParser {
    /**
     * Where a button points. [DRIVE] targets are Drive ids (file or folder), [INDEX] targets are URLs on a "Google
     * Drive Index" worker (`https://<host>/0:/<path>/`), and [SHORT] targets are tinyurl links still to be resolved.
     */
    enum class Kind { DRIVE, INDEX, SHORT }

    /**
     * A source link in a post. [label] is the button text (`Season 2`, `1080p [Private Drive]`, `Movie`); [group] is the
     * heading of its toggle when a post has several link toggles, which is how compilation posts name each title.
     */
    data class Ref(val kind: Kind, val target: String, val label: String, val group: String? = null)

    /**
     * A file found under a [Ref]. [id] is a Drive id, or for index files the file's URL. [folders] are the names of the
     * folders it sits in, nearest first; with [label] they carry the season and show the file name often leaves out.
     */
    data class SourceFile(
        val id: String,
        val name: String,
        val label: String,
        val folders: List<String> = emptyList(),
        val group: String? = null,
    ) {
        val isIndex get() = id.startsWith("http")
    }

    /** One playable entry. Several files in [files] are alternative copies of the same episode. */
    data class Entry(val season: Int, val episode: Int, val title: String?, val files: List<SourceFile>)

    /** The episodes of a post, and names for the seasons that hold a different show or arc than their number says. */
    data class Episodes(val entries: List<Entry>, val seasonNames: Map<Int, String>)

    data class Details(val plot: String?, val tags: List<String>, val year: Int?, val minutes: Int?)

    private val driveId = Regex("""drive\.google\.com/(?:drive/(?:u/\d+/)?folders|file/(?:u/\d+/)?d)/([\w-]+)""")
    private val driveParam = Regex("""drive\.google\.com/(?:open|uc)\?(?:[^#]*&)?id=([\w-]+)""")
    private val indexUrl = Regex("""^https?://[^/]+/\d+:/""")
    private val shortUrl = Regex("""^https?://(?:www\.)?tinyurl\.com/\w+""")
    private val postParam = Regex("""[?&]p=(\d+)""")
    private val extension = Regex("""\.([A-Za-z0-9]{2,4})$""")
    private val brackets = Regex("""\[[^\]]*\]|\([^)]*\)""")
    private val spaces = Regex("""\s+""")
    private val privateDrive = Regex("""\s*\[?\s*(?:private|drivate|provide|public)\s+drive\s*\]?""", RegexOption.IGNORE_CASE)
    // Toggle headings that only say "the links are here", as opposed to naming a title of a compilation post.
    private val genericHead = Regex("""^(?:download|drive|anime|links?)\b""", RegexOption.IGNORE_CASE)

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

    /**
     * The source links of a post, each target once (several buttons often share one folder). Buttons are `a` tags; the
     * latest episode of an ongoing show can also be a Drive `/preview` iframe. Google Group, Mega, Vimeo and links to
     * other posts are not sources, and some buttons have an empty href.
     */
    fun refs(contentHtml: String): List<Ref> {
        val doc = Jsoup.parse(contentHtml)
        val links = doc.select("a[href], iframe[src]")
        val linkToggles = links.filter { classify(it.attr("href").ifEmpty { it.attr("src") }, "") != null }
            .mapNotNull { it.closest("div.toggle") }.distinct()
        return links.mapNotNull { el ->
            val text = el.text().trim()
            if (text.contains("google group", true)) return@mapNotNull null
            val group = if (linkToggles.size > 1) {
                el.closest("div.toggle")?.selectFirst(".toggle-head")?.text()?.trim()?.takeIf { it.isNotEmpty() && !genericHead.containsMatchIn(it) }
            } else null
            classify(el.attr("href").ifEmpty { el.attr("src") }, text, group)
        }.distinctBy { it.target }
    }

    /** A link's kind and target, or null when it is not a source (also used for where a short link redirects). */
    fun classify(url: String, label: String, group: String? = null): Ref? {
        val href = url.trim()
        (driveId.find(href) ?: driveParam.find(href))?.let { return Ref(Kind.DRIVE, it.groupValues[1], label, group) }
        if (indexUrl.containsMatchIn(href)) return Ref(Kind.INDEX, href.substringBefore('?').substringBefore('#'), label, group)
        if (shortUrl.containsMatchIn(href)) return Ref(Kind.SHORT, href, label, group)
        return null
    }

    /** The decoded last path segment of an index URL: `…/0:/Black%20Clover/` -> `Black Clover`; the drive root is "". */
    fun indexName(url: String): String {
        val segment = url.trimEnd('/').substringAfterLast('/')
        return if (Regex("""^\d+:$""").matches(segment)) "" else dec(segment.replace("+", "%2B"))
    }

    /** A child of an index folder; names are percent-encoded per path segment (`+` too, spaces as `%20`). */
    fun indexChild(folderUrl: String, name: String, isFolder: Boolean): String =
        folderUrl.trimEnd('/') + "/" + enc(name).replace("+", "%20") + if (isFolder) "/" else ""

    /** Each file once. A file several buttons reach keeps the copy found nearest its button, whose label fits it. */
    fun distinctFiles(files: List<SourceFile>): List<SourceFile> =
        files.groupBy { it.id }.map { (_, copies) -> copies.minBy { it.folders.size } }

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
    // `Blood Lad 01 She's a Skeleton Now`, `Black Butler 02`: a padded number with no dash in front.
    private val bareNumber = Regex("""(?<=\s)(\d{2,3})(?=\s|$)""")
    // `Usagi Drop - 02.5`, `Episode 20.5`: recap or bonus episodes between two numbered ones.
    private val halfEpisode = Regex("""(?:[-–]\s*|\bep(?:isode)?\.?\s*)\d{1,3}\.5(?![0-9])""", RegexOption.IGNORE_CASE)
    private val specialWord = Regex(
        """(?<![A-Za-z])(?:OVAs?|OADs?|ONAs?|NCOP|NCED|SPECIALS?|MOVIES?|RECAP|OMAKE|EXTRAS?|TRAILER|PV|BONUS)(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val recap = Regex("""\brecap\b""", RegexOption.IGNORE_CASE)
    // What may sit next to the special words in a folder or button that holds only extras (`OVAs & TV Specials`, `Movie 1-2`).
    private val specialFiller = Regex("""\b(?:tv|sp|and|the|part)\b|[\d\s+&,:.\-]""", RegexOption.IGNORE_CASE)

    /** What a file name says about itself; [season] and [episode] are null where it says nothing. */
    data class Slot(val season: Int?, val episode: Int?, val special: Boolean)

    fun slot(fileName: String, label: String, folders: List<String> = emptyList()): Slot {
        val plain = plainName(fileName)
        seasonEpisode.find(plain)?.let {
            val season = it.groupValues[1].toInt()
            // `S03E17 Recap` sits beside the real S03E17.
            return Slot(season, it.groupValues[2].toInt(), season == 0 || recap.containsMatchIn(plain))
        }
        val base = fileName.replace(extension, "")
        if (halfEpisode.containsMatchIn(plain)) return Slot(0, null, true)
        if (specialWord.containsMatchIn(base) || (folders + label).any(::onlyExtras)) return Slot(0, null, true)
        val season = seasonNumber(plain) ?: (folders + label).firstNotNullOfOrNull(::seasonNumber)
        val episode = episodeWord.find(plain)?.groupValues?.get(1)?.toIntOrNull()
            ?: lastDashNumber(plain) ?: bareNumber.find(plain)?.groupValues?.get(1)?.toIntOrNull()
        return Slot(season, episode, false)
    }

    /** A folder or button that holds only extras (`Specials`, `OVAs`, `Movie 1-2 [Private Drive]`), not `Blood Lad + OVA`. */
    private fun onlyExtras(name: String): Boolean {
        val text = name.replace(privateDrive, " ").replace(brackets, " ")
        return specialWord.containsMatchIn(text) && text.replace(specialWord, " ").replace(specialFiller, "").isEmpty()
    }

    /** The name without extension, brackets and release tags, so `X.265` or `5.1` never read as episode numbers. */
    private fun plainName(fileName: String): String {
        val plain = fileName.replace(extension, "").replace(brackets, " ").replace(releaseTags, " ").replace('_', ' ')
        // `Psycho-Pass.The.Movie.1080p` uses dots for spaces.
        val spaced = if (' ' !in plain.trim() && plain.count { it == '.' } > 1) plain.replace('.', ' ') else plain
        return spaced.replace(spaces, " ").trim()
    }

    private fun lastDashNumber(text: String): Int? = dashNumber.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private fun seasonNumber(text: String): Int? =
        (seasonWord.find(text) ?: ordinalSeason.find(text))?.groupValues?.get(1)?.toIntOrNull()

    /** `[Kayoanime] Blue Lock - S01E01 [1080p]….mkv` -> `Blue Lock - S01E01`; names specials in the episode list. */
    fun displayName(fileName: String): String {
        val raw = fileName.replace(extension, "").replace('_', ' ')
        val base = if (' ' !in raw.trim() && raw.count { it == '.' } > 1) raw.replace('.', ' ') else raw
        val name = base.replace(brackets, " ").replace(spaces, " ").trim().trim('-', ' ', '.')
        // `Code：Breaker][OVA1]` has unbalanced brackets: keep their text rather than show `Code：Breaker]`.
        return if (name.isEmpty() || '[' in name || ']' in name) base.replace(Regex("""[\[\]]"""), " ").replace(spaces, " ").trim() else name
    }

    // Release tags in folder names: `Kimi ni Todoke (Season 1) [BD 1080p]` and `720p | Dual Audio` both leave the show.
    private val releaseTags = Regex(
        """\b(?:\d{3,4}p|bd(?:rip)?|bluray|blu-ray|web(?:-?dl|rip)?|hevc|[xh][\s.]?26[45]|10\s*-?bit|dual[\s._-]*audio|""" +
            """eng(?:lish)?[\s-]*subs?|multi[\s-]*subs?|flac|aac|opus|e?ac3|[257]\.[01]|batch|complete|uncensored|kayoanime)\b|\|""",
        RegexOption.IGNORE_CASE,
    )
    private val seasonTokens = Regex(
        """\b(?:seasons?\s*\d+(?:\s*-\s*\d+)?|s\d{1,2}|\d{1,2}(?:st|nd|rd|th)\s+season|box\s*\d+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val episodeMarkers = listOf(seasonEpisode, episodeWord, halfEpisode, dashNumber, bareNumber, specialWord)

    /** The show a file name is about: `Code Geass S1 - 01` -> `Code Geass`, `S01E01-Title [8B42].mkv` -> "". */
    private fun fileShow(fileName: String): String {
        val plain = plainName(fileName)
        val cut = episodeMarkers.mapNotNull { it.find(plain)?.range?.first }.minOrNull() ?: plain.length
        return cleanShow(plain.substring(0, cut))
    }

    /** A folder or button text reduced to a show name: release tags, seasons and `[Private Drive]` removed. */
    private fun cleanShow(text: String): String =
        Parser.unescapeEntities(text, false).replace(privateDrive, " ").replace(brackets, " ").replace(releaseTags, " ")
            .replace(seasonTokens, " ").replace(spaces, " ").trim().trim('-', ':', '–', ' ', '.', ',', '+', '&')
            .takeIf { name -> name.count { it.isLetterOrDigit() } >= 2 && !onlyExtras(name) }.orEmpty()

    private fun showKey(name: String) = name.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /**
     * The names a post title gives its show: `Chaika: The Coffin Princess (Hitsugi no Chaika) (Season 1-2)` ->
     * both titles. Files named either way are the same show, so their copies merge instead of becoming two seasons.
     */
    fun aliases(postTitle: String): List<String> {
        val title = cleanTitle(postTitle)
        val parts = listOf(title.substringBefore('(')) + Regex("""\(([^)]*)\)""").findAll(title).map { it.groupValues[1] }
        return parts.map { showKey(cleanShow(it)) }.filter { key -> key.any { it.isLetter() } }.distinct()
    }

    /** The show of a file: its own name, else the nearest folder that names one, else its button, else its toggle. */
    private fun showOf(file: SourceFile): String =
        fileShow(file.name).ifEmpty { (file.folders + file.label).map(::cleanShow).firstOrNull { it.isNotEmpty() }.orEmpty() }

    /**
     * How a season holding [file]'s show is named when it has to move: its toggle, subfolder, own name, button or
     * folder. The button comes late because several buttons of a post can share one folder (`Prisoners of the Sky`,
     * `Signs of Holy War` and `Grudge of Edinburgh` all open `Nanatsu`).
     */
    private fun seasonName(file: SourceFile, show: String): String {
        val subfolders = file.folders.dropLast(1).map(::cleanShow)
        return (listOfNotNull(file.group?.let(::cleanShow)) + subfolders + fileShow(file.name) + cleanShow(file.label) +
            file.folders.takeLast(1).map(::cleanShow)).firstOrNull { it.isNotEmpty() } ?: show
    }

    /**
     * Turns the files of a post into numbered entries. Files that name no season take the season of their folder or
     * button (`Season 2`), else 1. Files of one show that claim the same slot are alternative copies (BD and WEB
     * releases, 1080p and 720p). A different show or arc that lands on a slot already taken (`Code Geass S1 - 01`
     * next to `Code Geass - Dakkan no Roze - 01`) moves whole to new seasons named after it. Specials, movies, OVAs
     * and half episodes go to season 0, one entry per file in post order. [aliases] are the post's names for its show.
     */
    fun entries(files: List<SourceFile>, aliases: List<String> = emptyList()): Episodes {
        data class Placed(val file: SourceFile, val season: Int, val episode: Int?, val explicit: Boolean)
        data class Show(val key: String, val name: String, val files: MutableList<Placed> = mutableListOf())

        val videos = files.filter { isVideo(it.name) }
        val specials = mutableListOf<SourceFile>()
        val shows = LinkedHashMap<String, Show>()
        for (file in videos) {
            val slot = slot(file.name, file.label, file.folders)
            if (slot.special) {
                specials += file
                continue
            }
            val name = showOf(file)
            // In a compilation post each toggle is its own title even when the files share a show name.
            val key = (file.group?.let { showKey(it) + "|" } ?: "") + showKey(name).let { if (it in aliases) MAIN_SHOW else it }
            shows.getOrPut(key) { Show(key, name) }.files += Placed(file, slot.season ?: 1, slot.episode, slot.season != null)
        }

        val owner = HashMap<Pair<Int, Int>, String>()
        val bySlot = LinkedHashMap<Pair<Int, Int>, MutableList<SourceFile>>()
        val titles = HashMap<Pair<Int, Int>, String>()
        val names = HashMap<Int, String>()
        var nextSeason = (shows.values.flatMap { s -> s.files.map { it.season } }.maxOrNull() ?: 0) + 1
        val seasonOf = HashMap<String, Map<Int, Int>>()

        // The titles of a compilation post that are single files (a movie list) take the first numbers of their season,
        // then shows whose files say their season claim their slots; the rest yield to them.
        val standalone = { s: Show -> s.files.all { it.episode == null && it.file.group != null } }
        val order = shows.values.sortedWith(compareByDescending<Show>(standalone).thenByDescending { s -> s.files.any { it.explicit } })
        fun appendUnnumbered(show: Show, placed: Placed) {
            val season = seasonOf[show.key]!![placed.season]!!
            val number = (bySlot.keys.filter { it.first == season }.maxOfOrNull { it.second } ?: 0) + 1
            owner[season to number] = show.key
            bySlot[season to number] = mutableListOf(placed.file)
            // A toggle heading names its title only when the toggle holds that one file.
            titles[season to number] = placed.file.group?.takeIf { show.files.size == 1 } ?: displayName(placed.file.name)
        }
        for (show in order) {
            if (standalone(show)) {
                seasonOf[show.key] = show.files.map { it.season }.distinct().associateWith { it }
                show.files.forEach { appendUnnumbered(show, it) }
                continue
            }
            val numbered = show.files.filter { it.episode != null }
            val clash = numbered.any { owner[it.season to it.episode!!]?.let { o -> o != show.key } == true }
            val native = show.files.map { it.season }.distinct().sorted()
            seasonOf[show.key] = if (!clash) native.associateWith { it } else native.associateWith { season ->
                val moved = nextSeason++
                val name = seasonName(show.files.first { it.season == season }.file, show.name)
                names[moved] = if (native.size > 1 || season > 1) "$name Season $season" else name
                moved
            }
            for (placed in numbered) {
                val key = seasonOf[show.key]!![placed.season]!! to placed.episode!!
                owner[key] = show.key
                bySlot.getOrPut(key) { mutableListOf() } += placed.file
            }
        }
        // Files without a number (movies in a list, a lone episode) take the next free number of their season.
        for (show in shows.values.filterNot(standalone)) {
            show.files.filter { it.episode == null }.forEach { appendUnnumbered(show, it) }
        }
        val regular = bySlot.map { (key, group) -> Entry(key.first, key.second, titles[key], group) }
        val extras = specials.mapIndexed { i, file -> Entry(0, i + 1, displayName(file.name), listOf(file)) }
        val sorted = (regular + extras).sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
        return Episodes(sorted, names)
    }

    // ---- links ---------------------------------------------------------------------------------------------

    /**
     * Episode `data`: `https://drive.google.com/file/d/<id>/view#<name>&<label>` for Drive files, `<index file url>#…`
     * for index files, copies separated by `|`.
     */
    fun encodeSources(files: List<SourceFile>): String = files.joinToString("|") {
        val url = if (it.isIndex) it.id else "https://drive.google.com/file/d/${it.id}/view"
        "$url#${enc(it.name)}&${enc(it.label)}"
    }

    fun decodeSources(data: String): List<SourceFile> = data.split('|').mapNotNull { part ->
        val url = part.substringBefore('#')
        val id = driveId.find(url)?.groupValues?.get(1) ?: url.takeIf { indexUrl.containsMatchIn(it) } ?: return@mapNotNull null
        val fragment = part.substringAfter('#', "")
        SourceFile(id, dec(fragment.substringBefore('&')), dec(fragment.substringAfter('&', "")))
    }

    private val resolution = Regex("""(?<![0-9])(2160|1440|1080|720|576|480|420|360)p""", RegexOption.IGNORE_CASE)

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

    private const val MAIN_SHOW = "\u0000main"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
