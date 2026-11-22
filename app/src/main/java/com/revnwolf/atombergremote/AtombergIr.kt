package com.revnwolf.atombergremote

/**
 * Atomberg fan IR codes (NEC protocol, 38 kHz, extended address 0x00/0xF3).
 *
 * Command bytes are the LSB-first values (same convention as ESPHome transmit_nec,
 * where Atomberg = address 0xF300). MSB-first 32-bit equivalents, as captured with
 * Arduino IRremote, are noted beside each one (e.g. Power = 0x00CF8976).
 */
enum class AtombergButton(val label: String, val command: Int) {
    POWER("Power", 0x91),     // 00CF8976
    SPEED_1("Speed 1", 0x8B), // 00CFD12E
    SPEED_2("Speed 2", 0x90), // 00CF09F6
    SPEED_3("Speed 3", 0x8A), // 00CF51AE
    SPEED_4("Speed 4", 0x93), // 00CFC936
    SPEED_5("Speed 5", 0x88), // 00CF11EE
    BOOST("Boost", 0x8F),     // 00CFF10E
    SLEEP("Sleep", 0x8E),     // 00CF718E
    TIMER_1H("Timer 1h", 0x85), // 00CFA15E
    TIMER_2H("Timer 2h", 0x86), // 00CF619E
    TIMER_3H("Timer 3h", 0x92), // 00CF49B6
    TIMER_6H("Timer 6h", 0x8C), // 00CF31CE
    ;

    /** Wire bytes in transmit order. */
    val bytes get() = intArrayOf(0x00, 0xF3, command and 0xFF, command.inv() and 0xFF)
}

/** How the pattern is handed to the phone's IR HAL. Different OEM HALs need different shapes. */
enum class SignalMode(val label: String) {
    STANDARD("Standard"),       // one NEC frame, padded with a trailing gap (even-length pattern)
    STRONG("Strong (repeat)"),  // NEC frame + 2 NEC repeat codes, like holding the key briefly
    LEGACY("Legacy (cycles)"),  // STANDARD expressed in carrier cycles (old Samsung/HTC-style HALs)
}

object NecEncoder {
    const val CARRIER_HZ = 38_000

    fun pattern(command: Int, mode: SignalMode, carrierHz: Int = CARRIER_HZ): IntArray {
        val bytes = intArrayOf(0x00, 0xF3, command and 0xFF, command.inv() and 0xFF)
        val us = Nec.build(bytes, repeatCodes = if (mode == SignalMode.STRONG) 2 else 0)
        return if (mode == SignalMode.LEGACY) Nec.toCycles(us, carrierHz) else us
    }

    fun durationMs(pattern: IntArray): Long = pattern.sumOf { it.toLong() } / 1000
}

/** Generic NEC builder with knobs used by the tester's variant sweep. */
object Nec {
    const val HDR_MARK = 9000
    const val HDR_SPACE = 4500
    const val REPEAT_SPACE = 2250
    const val BIT_MARK = 562
    const val ONE_SPACE = 1687
    const val ZERO_SPACE = 562
    /** Kept below 65.5 ms: some IR chips store each duration in 16 bits and corrupt longer ones. */
    const val MAX_GAP = 40_000

    /**
     * @param bytes wire bytes, each sent LSB first
     * @param frames how many full frames
     * @param repeatCodes NEC "key held" repeat codes after the last frame
     * @param trailingGap end with a space (even length). false = original odd-length pattern
     */
    fun build(
        bytes: IntArray,
        frames: Int = 1,
        repeatCodes: Int = 0,
        trailingGap: Boolean = true,
        gap: Int = MAX_GAP,
    ): IntArray {
        val out = ArrayList<Int>(80 * (frames + 1))
        repeat(frames) { f ->
            out += HDR_MARK; out += HDR_SPACE
            for (b in bytes) for (i in 0 until 8) {
                out += BIT_MARK
                out += if (((b shr i) and 1) == 1) ONE_SPACE else ZERO_SPACE
            }
            out += BIT_MARK
            val last = f == frames - 1 && repeatCodes == 0
            if (!last || trailingGap) out += gap
        }
        repeat(repeatCodes) { r ->
            out += HDR_MARK; out += REPEAT_SPACE; out += BIT_MARK
            if (r < repeatCodes - 1 || trailingGap) out += gap
        }
        return out.toIntArray()
    }

    /** 32-bit value as printed by Arduino IRremote (MSB first), e.g. 0x00CF8976. */
    fun bytesFromMsb32(v: Long) = IntArray(4) { i ->
        Integer.reverse(((v shr (24 - 8 * i)) and 0xFF).toInt()) ushr 24
    }

    fun toCycles(us: IntArray, carrierHz: Int) =
        IntArray(us.size) { maxOf(1, Math.round(us[it].toLong() * carrierHz / 1_000_000.0).toInt()) }
}

/** A code typed by the user, normalised to a carrier + microsecond pattern. */
data class ParsedCode(val carrierHz: Int, val pattern: IntArray, val description: String)

object CodeParser {
    /**
     * Accepts:
     *  - NEC 32-bit hex, MSB first (IRremote style):   00CF8976
     *  - ESPHome NEC address + command:                 F300 91   or  0xF300 0x6E91
     *  - Pronto hex:                                    0000 006D 0022 0002 0157 00AC ...
     *  - Raw microseconds (mark, space, ...):           9000 4500 562 562 ...  (optional "freq=38000")
     */
    fun parse(input: String): ParsedCode {
        var text = input.trim()
        var freq = NecEncoder.CARRIER_HZ
        Regex("""freq\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            freq = it.groupValues[1].toInt()
            text = text.removeRange(it.range).trim()
        }
        val tokens = text.split(Regex("""[\s,;]+""")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "Enter a code" }
        val hex = tokens.map { it.removePrefix("0x").removePrefix("0X") }

        if (hex.size >= 6 && hex[0] == "0000" && hex.all { it.length == 4 && it.isHex() }) {
            return pronto(hex.map { it.toInt(16) })
        }
        if (tokens.size == 1 && hex[0].isHex() && hex[0].length in 5..8) {
            val v = hex[0].toLong(16)
            return ParsedCode(freq, Nec.build(Nec.bytesFromMsb32(v)), "NEC32 MSB 0x%08X".format(v))
        }
        if (tokens.size == 2 && hex.all { it.isHex() && it.length <= 4 }) {
            val addr = hex[0].toInt(16)
            var cmd = hex[1].toInt(16)
            if (cmd <= 0xFF) cmd = cmd or ((cmd.inv() and 0xFF) shl 8)
            val bytes = intArrayOf(addr and 0xFF, addr shr 8 and 0xFF, cmd and 0xFF, cmd shr 8 and 0xFF)
            return ParsedCode(freq, Nec.build(bytes), "NEC addr 0x%04X cmd 0x%04X".format(addr, cmd))
        }
        val raw = tokens.map { it.toIntOrNull() ?: throw IllegalArgumentException("Can't read \"$it\"") }
        require(raw.size >= 2 && raw.all { it > 0 }) { "Raw needs positive µs values" }
        return ParsedCode(freq, raw.toIntArray(), "Raw ${raw.size} values")
    }

    private fun pronto(w: List<Int>): ParsedCode {
        val freq = (1_000_000 / (w[1] * 0.241246)).toInt()
        val n1 = w[2]; val n2 = w[3]
        val cycles = w.drop(4)
        require(cycles.size >= 2 * (n1 + n2)) { "Pronto code is truncated" }
        // Prefer the repeat sequence if there is no once-sequence.
        val seq = if (n1 > 0) cycles.take(2 * n1) else cycles.drop(2 * n1).take(2 * n2)
        val us = seq.map { (it * 1_000_000L / freq).toInt().coerceAtLeast(1) }
        return ParsedCode(freq, us.toIntArray(), "Pronto ${freq / 1000}kHz, ${us.size} values")
    }

    private fun String.isHex() = isNotEmpty() && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
