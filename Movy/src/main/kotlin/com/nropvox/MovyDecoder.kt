package com.nropvox

/**
 * Port of movy.sx's `enc=2` payload decoder: an XOR stream cipher keyed by the `/seed` string and the TMDB id.
 * The plaintext starts with the magic "mvm1" followed by UTF-8 JSON.
 *
 * All arithmetic is 32-bit and wraps like the site's `Math.imul` / `>>> 0` code.
 */
object MovyDecoder {
    private const val GOLD = 0x9e3779b9.toInt()
    private const val SLOTS = 61
    private val MAGIC = "mvm1".toByteArray()

    /** Returns the decrypted JSON, or null when the seed or id does not match the payload. */
    fun decrypt(data: ByteArray, seed: String, mediaId: Int): String? {
        val plain = ByteArray(data.size)
        val keys = keystream(seed, mediaId)
        var word = 0
        for (i in data.indices) {
            if (i % 4 == 0) word = keys.next()
            plain[i] = (data[i].toInt() xor (word ushr (8 * (i % 4)))).toByte()
        }
        if (plain.size < MAGIC.size || !MAGIC.indices.all { plain[it] == MAGIC[it] }) return null
        return String(plain, MAGIC.size, plain.size - MAGIC.size, Charsets.UTF_8)
    }

    private fun keystream(seed: String, mediaId: Int): Iterator<Int> = iterator {
        var h = 0x811c9dc5.toInt()
        for (ch in seed) h = (h xor ch.code) * 0x01000193

        // A sparse 61-slot table; unset slots read as 0 and do not set the mask below.
        val table = IntArray(SLOTS)
        val present = BooleanArray(SLOTS)
        var s = fmix(fmix(h) xor fmix(mediaId xor GOLD))
        for (i in 0 until 8) {
            val a = mod61(s)
            s = (s + GOLD).rotateLeft(7 + (i and 7))
            table[a] = s xor fmix(s)
            present[a] = true
            s = fmix(s + a)
        }

        var acc = fmix(0xa5a5a5a5.toInt() xor s)
        var counter = 0
        while (true) {
            val d = mod61(acc)
            val mask = if (present[d]) -1 else 0
            val n = table[d] xor (GOLD * (counter + 1))
            var b = (acc xor n) or (acc and n and mask)
            b = (b + acc).rotateLeft(d and 31) xor acc.rotateLeft((d * 7) and 31)
            acc = fmix(b + GOLD)
            table[d] = acc
            present[d] = true
            counter++
            yield(acc)
        }
    }

    /** MurmurHash3 32-bit finalizer. */
    private fun fmix(value: Int): Int {
        var e = value
        e = e xor (e ushr 16)
        e *= 0x85ebca6b.toInt()
        e = e xor (e ushr 13)
        e *= 0xc2b2ae35.toInt()
        return e xor (e ushr 16)
    }

    private fun mod61(value: Int): Int = ((value.toLong() and 0xffffffffL) % SLOTS).toInt()
}
