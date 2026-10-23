package com.nropvox

/**
 * Just enough of a WebAssembly interpreter to run the key-mixing module that every flixcloud embed ships in
 * `w_payload`. The module is regenerated on each page load with different operations inside, so it cannot be
 * hard-coded; it only uses i32 arithmetic, byte loads/stores, locals, one global and structured loops.
 * Anything outside that subset throws, which surfaces as a failed decode rather than a wrong key.
 */
class ReanimeWasm(bytes: ByteArray) {
    private class FuncType(val params: Int, val results: Int)

    private class Func(val type: FuncType) {
        var locals = 0
        lateinit var code: Array<Instr>
    }

    /** `end`/`elseAt` are the indices of the matching `end` / `else` for block, loop and if. */
    private class Instr(val op: Int, val a: Int = 0, val b: Int = 0, val table: IntArray? = null) {
        var end = -1
        var elseAt = -1
        var arity = 0
    }

    private class Label(val target: Int, val height: Int, val arity: Int)

    private val types = ArrayList<FuncType>()
    private val funcTypeIdx = ArrayList<Int>()
    private val funcs = ArrayList<Func>()
    private val exports = HashMap<String, Int>()
    private val globals = ArrayList<Int>()
    private var memPages = 1
    private lateinit var mem: ByteArray
    private val dataSegments = ArrayList<Pair<Int, ByteArray>>()

    init {
        parse(bytes)
        instantiate()
    }

    /** Linear memory, for reading results back after [call]. */
    val memory: ByteArray get() = mem

    fun call(name: String, vararg args: Int): Int? {
        val index = exports[name] ?: throw IllegalArgumentException("No export $name")
        return invoke(funcs[index], args)
    }

    // ---- binary parsing ----

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun byte(): Int = b[p++].toInt() and 0xff
        fun u32(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val x = byte()
                result = result or ((x and 0x7f).toLong() shl shift)
                shift += 7
                if (x and 0x80 == 0) return result.toInt()
            }
        }

        fun s32(): Int {
            var result = 0L
            var shift = 0
            var x: Int
            do {
                x = byte()
                result = result or ((x and 0x7f).toLong() shl shift)
                shift += 7
            } while (x and 0x80 != 0)
            if (shift < 64 && x and 0x40 != 0) result = result or (-1L shl shift)
            return result.toInt()
        }

        fun name(): String {
            val n = u32()
            val s = String(b, p, n, Charsets.UTF_8)
            p += n
            return s
        }
    }

    private fun parse(bytes: ByteArray) {
        require(bytes.size > 8 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0x61 && bytes[2].toInt() == 0x73 && bytes[3].toInt() == 0x6d) {
            "Not a wasm module"
        }
        val r = Reader(bytes, 8)
        while (r.p < bytes.size) {
            val id = r.byte()
            val size = r.u32()
            val sectionEnd = r.p + size
            when (id) {
                1 -> repeat(r.u32()) {
                    require(r.byte() == 0x60) { "Bad func type" }
                    val params = r.u32()
                    repeat(params) { requireI32(r.byte()) }
                    val results = r.u32()
                    repeat(results) { requireI32(r.byte()) }
                    types.add(FuncType(params, results))
                }
                3 -> repeat(r.u32()) { funcTypeIdx.add(r.u32()) }
                5 -> repeat(r.u32()) {
                    val flags = r.byte()
                    memPages = r.u32()
                    if (flags and 1 != 0) r.u32()
                }
                6 -> repeat(r.u32()) {
                    requireI32(r.byte())
                    r.byte() // mutability
                    require(r.byte() == 0x41) { "Unsupported global init" }
                    globals.add(r.s32())
                    require(r.byte() == 0x0b) { "Unsupported global init" }
                }
                7 -> repeat(r.u32()) {
                    val name = r.name()
                    val kind = r.byte()
                    val index = r.u32()
                    if (kind == 0) exports[name] = index
                }
                10 -> {
                    val count = r.u32()
                    for (i in 0 until count) {
                        val bodySize = r.u32()
                        val bodyEnd = r.p + bodySize
                        val func = Func(types[funcTypeIdx[i]])
                        repeat(r.u32()) {
                            val n = r.u32()
                            requireI32(r.byte())
                            func.locals += n
                        }
                        func.code = decode(Reader(bytes, r.p, bodyEnd))
                        funcs.add(func)
                        r.p = bodyEnd
                    }
                }
                11 -> repeat(r.u32()) {
                    require(r.u32() == 0) { "Unsupported data segment" }
                    require(r.byte() == 0x41) { "Unsupported data offset" }
                    val offset = r.s32()
                    require(r.byte() == 0x0b) { "Unsupported data offset" }
                    val n = r.u32()
                    dataSegments.add(offset to bytes.copyOfRange(r.p, r.p + n))
                    r.p += n
                }
            }
            r.p = sectionEnd
        }
    }

    private fun requireI32(type: Int) = require(type == 0x7f) { "Only i32 is supported (got 0x${type.toString(16)})" }

    private fun decode(r: Reader): Array<Instr> {
        val out = ArrayList<Instr>()
        val open = ArrayList<Int>()
        while (r.p < r.end) {
            val op = r.byte()
            val instr = when (op) {
                0x02, 0x03, 0x04 -> {
                    val blockType = r.byte()
                    require(blockType == 0x40 || blockType == 0x7f) { "Unsupported block type" }
                    Instr(op).also { it.arity = if (blockType == 0x7f) 1 else 0 }
                }
                0x0c, 0x0d, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24 -> Instr(op, r.u32())
                0x0e -> {
                    val n = r.u32()
                    val table = IntArray(n + 1) { r.u32() }
                    Instr(op, table = table)
                }
                0x28, 0x2c, 0x2d, 0x2e, 0x2f, 0x36, 0x3a, 0x3b -> Instr(op, r.u32(), r.u32())
                0x3f, 0x40 -> Instr(op, r.byte())
                0x41 -> Instr(op, r.s32())
                in SIMPLE_OPS -> Instr(op)
                else -> throw UnsupportedOperationException("wasm opcode 0x${op.toString(16)}")
            }
            val index = out.size
            out.add(instr)
            when (op) {
                0x02, 0x03, 0x04 -> open.add(index)
                0x05 -> out[open.last()].elseAt = index
                0x0b -> if (open.isNotEmpty()) out[open.removeAt(open.size - 1)].end = index
            }
        }
        return out.toTypedArray()
    }

    private fun instantiate() {
        mem = ByteArray(memPages * 65536)
        for ((offset, data) in dataSegments) data.copyInto(mem, offset)
    }

    // ---- execution ----

    private fun invoke(func: Func, args: IntArray): Int? {
        val locals = IntArray(func.type.params + func.locals)
        args.copyInto(locals, 0, 0, func.type.params)
        val stack = IntArray(STACK_SIZE)
        var sp = 0
        val labels = ArrayList<Label>()
        val code = func.code
        var pc = 0
        var steps = 0L

        fun branch(depth: Int) {
            val label = labels[labels.size - 1 - depth]
            val top = sp
            sp = label.height + label.arity
            if (label.arity > 0) stack[label.height] = stack[top - 1]
            repeat(depth) { labels.removeAt(labels.size - 1) }
            if (label.target < 0) {
                // loop: re-enter, keeping its label
                pc = -label.target
            } else {
                labels.removeAt(labels.size - 1)
                pc = label.target
            }
        }

        while (pc < code.size) {
            if (++steps > MAX_STEPS) throw IllegalStateException("wasm step limit")
            val ins = code[pc++]
            when (val op = ins.op) {
                0x00 -> throw IllegalStateException("wasm unreachable")
                0x01 -> {}
                0x02 -> labels.add(Label(ins.end + 1, sp, ins.arity))
                // A loop label branches back to just after the loop instruction; encode that as a negative target.
                0x03 -> labels.add(Label(-pc, sp, 0))
                0x04 -> {
                    val cond = stack[--sp]
                    labels.add(Label(ins.end + 1, sp, ins.arity))
                    if (cond == 0) {
                        if (ins.elseAt >= 0) pc = ins.elseAt + 1
                        else {
                            labels.removeAt(labels.size - 1)
                            pc = ins.end + 1
                        }
                    }
                }
                0x05 -> { // reached the end of the "then" arm
                    val label = labels.removeAt(labels.size - 1)
                    pc = label.target
                }
                0x0b -> if (labels.isNotEmpty()) labels.removeAt(labels.size - 1)
                0x0c -> branch(ins.a)
                0x0d -> if (stack[--sp] != 0) branch(ins.a)
                0x0e -> {
                    val table = ins.table!!
                    val i = stack[--sp]
                    branch(if (i in 0 until table.size - 1) table[i] else table[table.size - 1])
                }
                0x0f -> return if (func.type.results > 0) stack[sp - 1] else null
                0x10 -> {
                    val callee = funcs[ins.a]
                    val n = callee.type.params
                    val callArgs = IntArray(n) { stack[sp - n + it] }
                    sp -= n
                    val result = invoke(callee, callArgs)
                    if (result != null) stack[sp++] = result
                }
                0x1a -> sp--
                0x1b -> {
                    val cond = stack[--sp]
                    val second = stack[--sp]
                    if (cond == 0) stack[sp - 1] = second
                }
                0x20 -> stack[sp++] = locals[ins.a]
                0x21 -> locals[ins.a] = stack[--sp]
                0x22 -> locals[ins.a] = stack[sp - 1]
                0x23 -> stack[sp++] = globals[ins.a]
                0x24 -> globals[ins.a] = stack[--sp]
                0x28 -> stack[sp - 1] = load(stack[sp - 1], ins.b, 4, false)
                0x2c -> stack[sp - 1] = load(stack[sp - 1], ins.b, 1, true)
                0x2d -> stack[sp - 1] = load(stack[sp - 1], ins.b, 1, false)
                0x2e -> stack[sp - 1] = load(stack[sp - 1], ins.b, 2, true)
                0x2f -> stack[sp - 1] = load(stack[sp - 1], ins.b, 2, false)
                0x36 -> { val v = stack[--sp]; store(stack[--sp], ins.b, 4, v) }
                0x3a -> { val v = stack[--sp]; store(stack[--sp], ins.b, 1, v) }
                0x3b -> { val v = stack[--sp]; store(stack[--sp], ins.b, 2, v) }
                0x3f -> stack[sp++] = mem.size / 65536
                0x40 -> {
                    val delta = stack[sp - 1]
                    stack[sp - 1] = mem.size / 65536
                    mem = mem.copyOf(mem.size + delta * 65536)
                }
                0x41 -> stack[sp++] = ins.a
                0x45 -> stack[sp - 1] = if (stack[sp - 1] == 0) 1 else 0
                0x67 -> stack[sp - 1] = Integer.numberOfLeadingZeros(stack[sp - 1])
                0x68 -> stack[sp - 1] = Integer.numberOfTrailingZeros(stack[sp - 1])
                0x69 -> stack[sp - 1] = Integer.bitCount(stack[sp - 1])
                else -> {
                    val b = stack[--sp]
                    val a = stack[sp - 1]
                    stack[sp - 1] = binary(op, a, b)
                }
            }
        }
        return if (func.type.results > 0 && sp > 0) stack[sp - 1] else null
    }

    private fun binary(op: Int, a: Int, b: Int): Int = when (op) {
        0x46 -> bool(a == b)
        0x47 -> bool(a != b)
        0x48 -> bool(a < b)
        0x49 -> bool(Integer.compareUnsigned(a, b) < 0)
        0x4a -> bool(a > b)
        0x4b -> bool(Integer.compareUnsigned(a, b) > 0)
        0x4c -> bool(a <= b)
        0x4d -> bool(Integer.compareUnsigned(a, b) <= 0)
        0x4e -> bool(a >= b)
        0x4f -> bool(Integer.compareUnsigned(a, b) >= 0)
        0x6a -> a + b
        0x6b -> a - b
        0x6c -> a * b
        0x6d -> a / b
        0x6e -> Integer.divideUnsigned(a, b)
        0x6f -> a % b
        0x70 -> Integer.remainderUnsigned(a, b)
        0x71 -> a and b
        0x72 -> a or b
        0x73 -> a xor b
        0x74 -> a shl (b and 31)
        0x75 -> a shr (b and 31)
        0x76 -> a ushr (b and 31)
        0x77 -> Integer.rotateLeft(a, b)
        0x78 -> Integer.rotateRight(a, b)
        else -> throw UnsupportedOperationException("wasm opcode 0x${op.toString(16)}")
    }

    private fun bool(v: Boolean) = if (v) 1 else 0

    private fun address(base: Int, offset: Int, size: Int): Int {
        val a = (base.toLong() and 0xffffffffL) + (offset.toLong() and 0xffffffffL)
        if (a + size > mem.size) throw IndexOutOfBoundsException("wasm memory access at $a")
        return a.toInt()
    }

    private fun load(base: Int, offset: Int, size: Int, signed: Boolean): Int {
        val a = address(base, offset, size)
        var v = 0
        for (i in 0 until size) v = v or ((mem[a + i].toInt() and 0xff) shl (8 * i))
        if (signed && size < 4) {
            val shift = 32 - 8 * size
            v = (v shl shift) shr shift
        }
        return v
    }

    private fun store(base: Int, offset: Int, size: Int, value: Int) {
        val a = address(base, offset, size)
        for (i in 0 until size) mem[a + i] = (value ushr (8 * i)).toByte()
    }

    private companion object {
        const val STACK_SIZE = 1024
        const val MAX_STEPS = 5_000_000L

        // Opcodes with no immediates that [binary] and the main loop implement.
        val SIMPLE_OPS: Set<Int> = buildSet {
            addAll(listOf(0x00, 0x01, 0x05, 0x0b, 0x0f, 0x1a, 0x1b, 0x45, 0x67, 0x68, 0x69))
            addAll(0x46..0x4f)
            addAll(0x6a..0x78)
        }
    }
}
