package com.nropvox

import java.io.ByteArrayOutputStream

/** Static extraction of the site's own base91-carried WASM. Never evaluates page JavaScript. */
internal object StreamCornerDrm {
    data class Module(val base64: String, val exportName: String, val bytes: ByteArray)

    /** ABI observed in the supplied scripts; export names are discovered from each live module. */
    data class Abi(val input: Int, val output: Int, val selector: Int, val capacity: Int = 60_000)
    val payloadAbi = Abi(input = 0x101e10, output = 0x112540, selector = 0x1379bc)
    val keyAbi = Abi(input = 1056432, output = 1123728, selector = 2083625)
    // Payload call: export(inputLength, input, capacity, output, selector).
    // Key call: export(input, selector, output, capacity, inputLength).
    // Key input: hex-decoded KID (16 bytes) + wrapped key (44 bytes); expected output: 16 bytes.

    /** Older uppercase-token embeds carry JSON directly: Base32, byte shift, repeating XOR. */
    fun legacyPayload(token: String): String? {
        if (token.isEmpty() || token.length > 60_000) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val passphrase = "0000@streamcorner@0000".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in token) {
            val value = alphabet.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                val shifted = (((buffer ushr bits) and 255) - 75) and 255
                out.write(shifted xor passphrase[out.size() % passphrase.size].toInt())
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8).takeIf { it.startsWith("{") }
    }

    private const val STRING = 1
    private const val NUMBER = 2
    private const val NAME = 3
    private const val PUNCT = 4
    private data class Token(val kind: Int, val value: String, val start: Int, val end: Int)
    private data class Edit(val start: Int, val end: Int, val value: String)
    private data class Function(val name: String, val body: Int)

    fun extract(script: String): List<Module> {
        require(script.length <= 4_000_000) { "Player script too large" }
        var source = script
        var tokens = lex(source)
        val arrays = mutableMapOf<String, List<Any?>>()
        for (i in 2 until tokens.size - 2) {
            if (tokens[i].value != "[" || tokens[i - 1].value != "=" || tokens[i - 2].kind != NAME) continue
            var j = i + 1
            var begin = j
            val values = ArrayList<Any?>()
            try {
                while (tokens[j].value != "]") {
                    if (tokens[j].value == ",") {
                        values.add(primitive(tokens.subList(begin, j)))
                        begin = j + 1
                    }
                    j++
                    require(j - i < 1024)
                }
                if (begin < j) values.add(primitive(tokens.subList(begin, j)))
                if (values.size > 50) arrays[tokens[i - 2].value] = values
            } catch (_: IllegalArgumentException) {
            } catch (_: IndexOutOfBoundsException) {
            }
        }
        val edits = ArrayList<Edit>()
        for (i in 0 until tokens.size - 3) {
            val array = arrays[tokens[i].value] ?: continue
            if (tokens[i + 1].value != "[" || tokens[i + 3].value != "]") continue
            val index = number(tokens[i + 2]) ?: continue
            if (index !in array.indices) continue
            edits.add(Edit(tokens[i].start, tokens[i + 3].end, literal(array[index])))
        }
        source = apply(source, edits)
        tokens = lex(source)
        val payloads = mutableMapOf<String, String>()
        for (i in 2 until tokens.size) {
            val t = tokens[i]
            if (t.kind == STRING && t.value.length > 1000 && tokens[i - 1].value == "=" && tokens[i - 2].kind == NAME) {
                payloads[tokens[i - 2].value] = t.value
            }
        }
        if (payloads.isEmpty()) return emptyList()
        val functions = ArrayList<Function>()
        val tables = mutableMapOf<String, String>()
        for (i in tokens.indices) {
            if (tokens[i].value != "function") continue
            var j = i + 1
            var name: String? = null
            if (tokens.getOrNull(j)?.kind == NAME) name = tokens[j++].value
            else if (i >= 2 && tokens[i - 1].value == "=") name = tokens[i - 2].value
            if (name == null || tokens.getOrNull(j)?.value != "(") continue
            var depth = 1
            j++
            while (j < tokens.size && depth > 0) {
                when (tokens[j++].value) { "(" -> depth++; ")" -> depth-- }
            }
            if (tokens.getOrNull(j)?.value != "{") continue
            val body = j + 1
            functions.add(Function(name, body))
            val alphabet = tokens.getOrNull(body + 3)
            if (tokens.getOrNull(body)?.value in listOf("var", "let", "const") &&
                tokens.getOrNull(body + 2)?.value == "=" && alphabet?.kind == STRING &&
                alphabet.value.length == 91 && alphabet.value.toSet().size == 91
            ) tables[name] = alphabet.value
        }
        val wrappers = mutableMapOf<String, Pair<String, String>>()
        for ((name, body) in functions) {
            if (tokens.getOrNull(body)?.value != "return") continue
            val decoder = tokens.getOrNull(body + 1)?.value ?: continue
            val payload = tokens.getOrNull(body + 3)?.value ?: continue
            if (decoder in tables && payload in payloads && tokens.getOrNull(body + 2)?.value == "(") {
                wrappers[name] = decoder to payload
            }
        }
        val modules = ArrayList<Module>()
        for (i in 0 until tokens.size - 5) {
            val (decoder, payload) = wrappers[tokens[i].value] ?: continue
            if (tokens[i + 1].value != "(" || tokens[i + 3].value != "," || tokens[i + 5].value != ")") continue
            val start = number(tokens[i + 2]) ?: continue
            val length = number(tokens[i + 4]) ?: continue
            val carrier = payloads.getValue(payload)
            if (start < 0 || length < 0 || start.toLong() + length > carrier.length) continue
            val decoded = decode91(carrier.substring(start, start + length), tables.getValue(decoder))
            if (!decoded.startsWith("AGFzbQ")) continue
            val bytes = decode64(decoded)
            val export = wasmExport(bytes) ?: continue
            if (modules.none { it.base64 == decoded }) modules.add(Module(decoded, export, bytes))
        }
        return modules
    }

    private fun primitive(tokens: List<Token>): Any? {
        require(tokens.isNotEmpty())
        if (tokens.size == 1) {
            val t = tokens.single()
            if (t.kind == STRING) return t.value
            if (t.kind == NUMBER) return number(t) ?: t.value.toDouble()
            when (t.value) { "null", "undefined" -> return null; "true" -> return true; "false" -> return false }
        }
        if (tokens.size == 2) {
            val value = primitive(tokens.subList(1, 2))
            when (tokens[0].value) {
                "!" -> return value == null || value == false || value == 0 || value == ""
                "void" -> return null
                "-" -> return -(value as? Number ?: error("Not numeric")).toInt()
            }
        }
        throw IllegalArgumentException("Not a primitive")
    }

    private fun number(t: Token): Int? {
        if (t.kind != NUMBER) return null
        return if (t.value.startsWith("0x", true)) t.value.substring(2).toIntOrNull(16) else t.value.toIntOrNull()
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        else -> value.toString()
    }

    private fun apply(source: String, edits: List<Edit>): String = buildString {
        var position = 0
        for (edit in edits.sortedWith(compareBy<Edit> { it.start }.thenByDescending { it.end })) {
            if (edit.start < position) continue
            append(source, position, edit.start)
            append(edit.value)
            position = edit.end
        }
        append(source, position, source.length)
    }

    private fun lex(source: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < source.length) {
            val start = i
            val c = source[i]
            if (c.isWhitespace()) { i++; continue }
            if (source.startsWith("//", i)) { i = source.indexOf('\n', i + 2).takeIf { it >= 0 } ?: source.length; continue }
            if (source.startsWith("/*", i)) { i = source.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: source.length; continue }
            if (c == '\'' || c == '"' || c == '`') {
                i++
                val value = StringBuilder()
                var closed = false
                while (i < source.length) {
                    val ch = source[i++]
                    if (ch == c) { closed = true; break }
                    if (ch != '\\') { value.append(ch); continue }
                    if (i == source.length) break
                    val escape = source[i++]
                    when (escape) {
                        'n' -> value.append('\n'); 'r' -> value.append('\r'); 't' -> value.append('\t')
                        'b' -> value.append('\b'); 'f' -> value.append('\u000c'); 'v' -> value.append('\u000b'); '0' -> value.append('\u0000')
                        '\n' -> Unit
                        '\r' -> if (source.getOrNull(i) == '\n') i++
                        'x', 'u' -> {
                            val count = if (escape == 'x') 2 else 4
                            val hex = source.substring(i, (i + count).coerceAtMost(source.length))
                            val number = hex.toIntOrNull(16)
                            if (number != null) { value.append(number.toChar()); i += count } else value.append(escape)
                        }
                        else -> value.append(escape)
                    }
                }
                if (closed) out.add(Token(STRING, value.toString(), start, i))
                else { i = start + 1; out.add(Token(PUNCT, c.toString(), start, i)) }
                continue
            }
            if (c == '_' || c == '$' || c.isLetter()) {
                i++
                while (i < source.length && (source[i] == '_' || source[i] == '$' || source[i].isLetterOrDigit())) i++
                out.add(Token(NAME, source.substring(start, i), start, i)); continue
            }
            if (c.isDigit() || (c == '.' && source.getOrNull(i + 1)?.isDigit() == true)) {
                i++
                if (c == '0' && source.getOrNull(i)?.lowercaseChar() == 'x') {
                    i++
                    while (i < source.length && source[i].digitToIntOrNull(16) != null) i++
                } else {
                    while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                    if (source.getOrNull(i)?.lowercaseChar() == 'e') {
                        i++
                        if (source.getOrNull(i) in listOf('+', '-')) i++
                        while (i < source.length && source[i].isDigit()) i++
                    }
                }
                out.add(Token(NUMBER, source.substring(start, i), start, i)); continue
            }
            out.add(Token(PUNCT, c.toString(), start, ++i))
        }
        return out
    }

    private fun decode91(source: String, alphabet: String): String {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        var value = -1
        for (c in source) {
            val p = alphabet.indexOf(c)
            if (p < 0) continue
            if (value < 0) { value = p; continue }
            value += p * 91
            buffer = buffer or (value shl bits)
            bits += if ((value and 8191) > 88) 13 else 14
            do { out.write(buffer and 255); buffer = buffer ushr 8; bits -= 8 } while (bits > 7)
            value = -1
        }
        if (value >= 0) out.write((buffer or (value shl bits)) and 255)
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun decode64(source: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in source) {
            if (c == '=') break
            val n = alphabet.indexOf(c)
            require(n >= 0) { "Invalid WASM carrier" }
            buffer = (buffer shl 6) or n
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((buffer ushr bits) and 255) }
        }
        return out.toByteArray()
    }

    /** Accept only valid v1 WASM with no imports and one application export. */
    private fun wasmExport(bytes: ByteArray): String? {
        if (bytes.size !in 8..512_000 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))) return null
        var position = 8
        fun uleb(): Int {
            var value = 0
            var shift = 0
            do {
                require(position < bytes.size && shift <= 28)
                val b = bytes[position++].toInt() and 255
                value = value or ((b and 127) shl shift)
                shift += 7
            } while ((b and 128) != 0)
            require(value >= 0)
            return value
        }
        val exported = ArrayList<String>()
        return try {
            while (position < bytes.size) {
                val id = bytes[position++].toInt() and 255
                val size = uleb()
                val end = position.toLong() + size
                require(end <= bytes.size)
                if (id == 2 && uleb() != 0) return null
                if (id == 7) repeat(uleb()) {
                    val length = uleb()
                    require(position.toLong() + length < end)
                    val name = String(bytes, position, length, Charsets.UTF_8)
                    position += length
                    val kind = bytes[position++].toInt() and 255
                    uleb()
                    if (kind == 0 && name != "_initialize" && !name.startsWith("__") && !name.contains("emscripten")) exported.add(name)
                }
                position = end.toInt()
            }
            exported.singleOrNull()
        } catch (_: IllegalArgumentException) { null } catch (_: IndexOutOfBoundsException) { null }
    }
}
