package com.nropvox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.lagradost.cloudstream3.mapper

/**
 * Decoder for SvelteKit `__data.json` page loaders.
 *
 * The body is newline-delimited JSON. The first line holds the route's nodes (layout first, page last),
 * each a devalue-flattened array where index 0 is the root and every other value is referenced by index.
 * Streamed promises are encoded as `["Promise", idx]` and arrive later as `chunk` lines keyed by that id.
 */
object SvelteData {
    sealed class Result {
        data class Page(val data: JsonNode) : Result()
        data class Redirect(val location: String) : Result()
    }

    private const val PROMISE = "__promise__"
    private val nodes = JsonNodeFactory.instance

    fun parse(body: String): Result {
        var page: JsonNode = NullNode.instance
        val chunks = HashMap<Int, JsonNode>()
        for (line in body.lineSequence()) {
            if (line.isBlank()) continue
            val message = mapper.readTree(line)
            when (message["type"]?.asText()) {
                // Non-canonical slugs are answered with a redirect to the canonical route.
                "redirect" -> return Result.Redirect(message["location"].asText())
                "data" -> page = message["nodes"]?.lastOrNull()
                    ?.takeIf { it["type"]?.asText() == "data" }
                    ?.let { unflatten(it["data"]) }
                    ?: NullNode.instance
                "chunk" -> chunks[message["id"].asInt()] = unflatten(message["data"])
            }
        }

        // Only the page's own fields are streamed, so resolving the top level is enough.
        (page as? ObjectNode)?.let { root ->
            root.fieldNames().asSequence().toList().forEach { key ->
                val id = root[key]?.get(PROMISE) ?: return@forEach
                root.set<JsonNode>(key, chunks[id.asInt()] ?: NullNode.instance)
            }
        }
        return Result.Page(page)
    }

    private fun unflatten(values: JsonNode?): JsonNode {
        if (values == null || !values.isArray || values.isEmpty) return NullNode.instance
        val memo = HashMap<Int, JsonNode>()

        fun hydrate(index: Int): JsonNode {
            // Negative indices encode undefined, holes, NaN, the infinities and -0.
            if (index < 0) return NullNode.instance
            memo[index]?.let { return it }
            val value = values[index]
            return when {
                // Plain arrays only hold indices, so a leading string marks a typed value.
                value.isArray && !value.isEmpty && value[0].isTextual -> when (value[0].asText()) {
                    "Promise" -> nodes.objectNode().put(PROMISE, hydrate(value[1].asInt()).asInt())
                    "Date", "BigInt", "URL", "URLSearchParams", "RegExp" -> value[1] ?: NullNode.instance
                    "Object" -> value[1] ?: NullNode.instance
                    "Set" -> nodes.arrayNode().also { set ->
                        memo[index] = set
                        for (i in 1 until value.size()) set.add(hydrate(value[i].asInt()))
                    }
                    "Map" -> nodes.objectNode().also { map ->
                        memo[index] = map
                        for (i in 1 until value.size() - 1 step 2) {
                            map.set<JsonNode>(hydrate(value[i].asInt()).asText(), hydrate(value[i + 1].asInt()))
                        }
                    }
                    // Null-prototype object: literal keys followed by value indices.
                    "null" -> nodes.objectNode().also { obj ->
                        memo[index] = obj
                        for (i in 1 until value.size() - 1 step 2) {
                            obj.set<JsonNode>(value[i].asText(), hydrate(value[i + 1].asInt()))
                        }
                    }
                    else -> TextNode(value.toString())
                }
                value.isArray -> nodes.arrayNode().also { array ->
                    memo[index] = array
                    value.forEach { array.add(hydrate(it.asInt())) }
                }
                value.isObject -> nodes.objectNode().also { obj ->
                    memo[index] = obj
                    value.fields().forEach { (key, ref) -> obj.set<JsonNode>(key, hydrate(ref.asInt())) }
                }
                else -> value
            }.also { memo[index] = it }
        }

        return hydrate(0)
    }
}
