package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Test
import java.io.File

/**
 * Recon aid, skipped unless KAYO_LISTINGS names a folder of crawled Drive trees (`<post id>.json`, see the recon's
 * crawl script) next to `posts*.json` REST dumps in `../data`: writes `../report.txt` with the episode list of each post.
 */
class KayoanimeReport {
    @Test fun report() {
        val dir = System.getenv("KAYO_LISTINGS")?.let(::File) ?: return
        val mapper = ObjectMapper()
        val html = File(dir, "../data").listFiles { f -> f.name.matches(Regex("""posts\d\.json""")) }.orEmpty()
            .map { mapper.readTree(it) }.filter { it.isArray }.flatMap { it.toList() }.associate { it["id"].asInt() to it["content"]["rendered"].asText() }
        val out = StringBuilder()
        dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.nameWithoutExtension.toInt() }.forEach { f ->
            val post = mapper.readTree(f)
            val refs = KayoanimeParser.refs(html[post["id"].asInt()].orEmpty()).associateBy { it.target }
            val files = post["refs"].flatMap { node ->
                val ref = refs[node["id"].asText()] ?: KayoanimeParser.Ref(KayoanimeParser.Kind.DRIVE, node["id"].asText(), node["label"].asText())
                flatten(node["tree"], ref.target, ref, emptyList())
            }
            val episodes = KayoanimeParser.entries(KayoanimeParser.distinctFiles(files), KayoanimeParser.aliases(post["title"].asText()))
            out.append("##### ${post["id"]} ${post["title"].asText()}\n")
            post["refs"].forEach { out.append("  ref [${it["label"].asText()}] ${it["tree"]["error"]?.asText() ?: it["tree"]["name"]?.asText()}\n") }
            episodes.seasonNames.toSortedMap().forEach { (s, n) -> out.append("  season $s = $n\n") }
            episodes.entries.forEach { e ->
                out.append("  S${e.season}E${e.episode}${e.title?.let { " \"$it\"" } ?: ""} <- ${e.files.joinToString(" || ") { "${it.folders.reversed().joinToString("/")}/${it.name}" }}\n")
            }
        }
        File(dir, "../report.txt").writeText(out.toString())
    }

    private fun flatten(node: JsonNode, id: String, ref: KayoanimeParser.Ref, parents: List<String>): List<KayoanimeParser.SourceFile> {
        if (node["type"]?.asText() != "folder") {
            return if (node["error"] != null) emptyList()
            else listOf(KayoanimeParser.SourceFile(id, node["name"]?.asText().orEmpty(), ref.label, parents, ref.group))
        }
        val folders = listOfNotNull(node["name"]?.asText()) + parents
        return node["children"].flatMap { c ->
            if (c["type"]?.asText() == "folder") flatten(c, c["id"]?.asText().orEmpty(), ref, folders)
            else listOf(KayoanimeParser.SourceFile(c["id"].asText(), c["name"].asText(), ref.label, folders, ref.group))
        }
    }
}
