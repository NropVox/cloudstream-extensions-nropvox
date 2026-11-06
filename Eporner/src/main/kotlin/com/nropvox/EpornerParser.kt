package com.nropvox

/** Pure helpers for eporner.com: ids, stream-hash conversion and xhr source parsing. */
object EpornerParser {
    data class Source(val quality: Int, val label: String, val url: String)

    private val idRegex = Regex("""/(?:video-|hd-porn/)([A-Za-z0-9]{11})""")
    private val hashRegex = Regex("""EP\.video\.player\.hash\s*=\s*'([0-9a-f]{32})'""")
    private val sourceRegex = Regex(""""labelShort"\s*:\s*"(\d+)p?[^"]*"\s*,\s*"src"\s*:\s*"([^"]+)"""")

    fun videoId(url: String): String? = idRegex.find(url)?.groupValues?.get(1)

    fun pageHash(html: String): String? = hashRegex.find(html)?.groupValues?.get(1)

    /** 32 hex chars -> four 8-char chunks, each rendered in base36 and concatenated. */
    fun xhrHash(hash: String): String =
        hash.chunked(8).joinToString("") { it.toLong(16).toString(36) }

    fun sources(json: String): List<Source> =
        sourceRegex.findAll(json)
            .map { Source(it.groupValues[1].toInt(), "${it.groupValues[1]}p", it.groupValues[2]) }
            .distinctBy { it.url }
            .sortedByDescending { it.quality }
            .toList()

    fun tags(keywords: String?): List<String> =
        keywords.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
