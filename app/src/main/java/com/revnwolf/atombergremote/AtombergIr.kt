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
    DOUBLE("Double-send"),      // same frame in two back-to-back transmit() calls (proven on iQOO 13)
    STANDARD("Standard"),       // one NEC frame, padded with a trailing gap (even-length pattern)
    STRONG("Strong (repeat)"),  // NEC frame + 2 NEC repeat codes, like holding the key briefly
    LEGACY("Legacy (cycles)"),  // STANDARD expressed in carrier cycles (old Samsung/HTC-style HALs)
    REAL_REMOTE("Real remote replay"), // exact capture of an Atomberg remote (NEC + repeat, real timings)
    PRONTO("Pronto-style"),     // real-remote timing rounded to whole carrier cycles, like most remote apps
}

object NecEncoder {
    const val CARRIER_HZ = 38_000

    fun pattern(button: AtombergButton, mode: SignalMode, carrierHz: Int = CARRIER_HZ): IntArray = when (mode) {
        SignalMode.DOUBLE -> Nec.doubleSend(button.bytes)[0]
        SignalMode.STANDARD -> Nec.build(button.bytes)
        SignalMode.STRONG -> Nec.build(button.bytes, repeatCodes = 2)
        SignalMode.LEGACY -> Nec.toCycles(Nec.build(button.bytes), carrierHz)
        SignalMode.REAL_REMOTE -> Captured.CAPTURED[button]
            ?: Nec.build(button.bytes, repeatCodes = 1, t = NecTiming.REMOTE)
        SignalMode.PRONTO -> Nec.quantize(Nec.build(button.bytes, repeatCodes = 1, t = NecTiming.REMOTE), carrierHz)
    }

    /**
     * Transmit calls to make, in order, ~2 ms apart. DOUBLE mirrors the "Atomberg BLDC Fan Remote"
     * app (The RMP Apps), which works on vivo/iQOO: one plain NEC frame, then the same frame again
     * in a second call ending with a 1000 µs mark.
     */
    fun calls(button: AtombergButton, mode: SignalMode, carrierHz: Int = CARRIER_HZ): List<IntArray> =
        if (mode == SignalMode.DOUBLE) Nec.doubleSend(button.bytes) else listOf(pattern(button, mode, carrierHz))

    fun durationMs(pattern: IntArray): Long = pattern.sumOf { it.toLong() } / 1000
}

/** NEC pulse lengths in µs. */
data class NecTiming(val hdrMark: Int, val hdrSpace: Int, val repeatSpace: Int, val bitMark: Int, val zeroSpace: Int, val oneSpace: Int) {
    companion object {
        val SPEC = NecTiming(9000, 4500, 2250, 562, 562, 1687)
        /** Averages measured from a real Atomberg remote capture. */
        val REMOTE = NecTiming(9410, 4680, 2340, 600, 580, 1750)
        /** Round values used by the working third-party app. */
        val ROUND = NecTiming(9000, 4500, 2250, 560, 560, 1690)
    }
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
        t: NecTiming = NecTiming.SPEC,
    ): IntArray {
        val out = ArrayList<Int>(80 * (frames + 1))
        repeat(frames) { f ->
            out += t.hdrMark; out += t.hdrSpace
            for (b in bytes) for (i in 0 until 8) {
                out += t.bitMark
                out += if (((b shr i) and 1) == 1) t.oneSpace else t.zeroSpace
            }
            out += t.bitMark
            val last = f == frames - 1 && repeatCodes == 0
            if (!last || trailingGap) out += gap
        }
        repeat(repeatCodes) { r ->
            out += t.hdrMark; out += t.repeatSpace; out += t.bitMark
            if (r < repeatCodes - 1 || trailingGap) out += gap
        }
        return out.toIntArray()
    }

    /** 32-bit value as printed by Arduino IRremote (MSB first), e.g. 0x00CF8976. */
    fun bytesFromMsb32(v: Long) = IntArray(4) { i ->
        Integer.reverse(((v shr (24 - 8 * i)) and 0xFF).toInt()) ushr 24
    }

    /** Two separate transmit() payloads: plain frame (odd length), then the same frame ending in a 1000 µs mark. */
    fun doubleSend(bytes: IntArray): List<IntArray> {
        val first = build(bytes, trailingGap = false, t = NecTiming.ROUND)
        val second = first.copyOf().also { it[it.size - 1] = 1000 }
        return listOf(first, second)
    }

    /** Round every duration to whole carrier cycles, as Pronto-based remote apps send them. */
    fun quantize(us: IntArray, carrierHz: Int): IntArray {
        val period = 1_000_000.0 / carrierHz
        return IntArray(us.size) { (Math.round(us[it] / period).coerceAtLeast(1) * period).toInt() }
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

/**
 * Raw timings decoded from Broadlink captures of a real Atomberg remote
 * (github.com/dasshubham762/atomberg-integration, atomberg_ir_codes.py): one NEC frame + one
 * repeat code. Trailing/inter-frame gaps capped at 40 ms.
 */
object Captured {
    val CAPTURED: Map<AtombergButton, IntArray> = mapOf(
        AtombergButton.POWER to intArrayOf(9424, 4663, 624, 558, 624, 558, 591, 591, 591, 558, 624, 591, 591, 591, 591, 591, 558, 591, 624, 1740, 591, 1740, 624, 558, 624, 591, 558, 1773, 591, 1740, 624, 1740, 591, 1740, 624, 1740, 591, 591, 591, 591, 558, 624, 591, 1740, 624, 558, 591, 558, 624, 1773, 558, 624, 558, 1773, 624, 1708, 624, 1740, 591, 591, 591, 1773, 558, 1773, 624, 558, 591, 40000, 9391, 2331, 624, 40000),
        AtombergButton.SPEED_1 to intArrayOf(9391, 4696, 591, 591, 624, 558, 591, 558, 624, 558, 624, 591, 591, 591, 591, 591, 558, 591, 591, 1740, 624, 1740, 624, 558, 624, 558, 591, 1773, 591, 1740, 591, 1773, 591, 1740, 624, 1740, 591, 1773, 558, 591, 591, 1773, 591, 591, 591, 558, 624, 558, 624, 1740, 591, 591, 591, 591, 591, 1740, 624, 558, 624, 1708, 624, 1773, 558, 1773, 591, 591, 624, 40000, 9490, 2331, 591, 40000),
        AtombergButton.SPEED_2 to intArrayOf(9424, 4696, 591, 591, 624, 558, 591, 558, 624, 558, 624, 591, 591, 591, 558, 624, 558, 591, 591, 1773, 591, 1740, 624, 558, 591, 624, 558, 1773, 591, 1740, 624, 1708, 624, 1740, 591, 624, 558, 591, 591, 591, 591, 591, 591, 1740, 624, 558, 624, 558, 624, 1740, 591, 1740, 591, 1773, 591, 1740, 624, 1708, 624, 591, 591, 1773, 558, 1773, 624, 558, 591, 40000, 9391, 2364, 558, 40000),
        AtombergButton.SPEED_3 to intArrayOf(9424, 4663, 624, 591, 591, 591, 558, 624, 558, 591, 624, 558, 624, 558, 624, 558, 591, 558, 624, 1773, 558, 1773, 591, 591, 591, 591, 591, 1740, 624, 1708, 624, 1773, 558, 1773, 591, 591, 591, 1740, 624, 558, 624, 1740, 591, 591, 591, 591, 558, 624, 558, 1773, 624, 1708, 624, 558, 624, 1740, 591, 591, 591, 1740, 624, 1740, 591, 1740, 624, 558, 624, 40000, 9424, 2331, 591, 40000),
        AtombergButton.SPEED_4 to intArrayOf(9424, 4663, 591, 591, 624, 558, 591, 591, 591, 558, 624, 591, 591, 591, 591, 591, 558, 624, 558, 1773, 624, 1708, 624, 558, 624, 558, 591, 1773, 591, 1740, 624, 1740, 591, 1740, 624, 1740, 591, 1773, 558, 591, 591, 591, 591, 1773, 591, 558, 624, 558, 624, 1740, 591, 591, 591, 591, 591, 1740, 624, 1740, 591, 558, 624, 1773, 558, 1773, 591, 591, 591, 40000, 9424, 2331, 624, 40000, 9424, 2331, 624, 40000),
        AtombergButton.SPEED_5 to intArrayOf(9424, 4696, 591, 591, 591, 591, 591, 591, 558, 624, 591, 558, 624, 558, 624, 558, 624, 558, 591, 1773, 591, 1740, 624, 558, 624, 558, 624, 1708, 624, 1773, 558, 1773, 591, 1740, 624, 558, 624, 558, 591, 591, 591, 1773, 591, 591, 558, 591, 591, 591, 591, 1773, 591, 1740, 624, 1740, 591, 1773, 558, 591, 591, 1773, 591, 1740, 624, 1740, 591, 591, 591, 40000, 9424, 2331, 624, 40000),
        AtombergButton.BOOST to intArrayOf(9424, 4663, 624, 558, 624, 558, 624, 591, 591, 591, 558, 591, 591, 591, 591, 591, 624, 558, 591, 1740, 624, 1740, 591, 591, 591, 591, 591, 1740, 624, 1740, 591, 1740, 624, 1740, 591, 1773, 558, 1773, 624, 1708, 624, 1740, 591, 591, 591, 591, 591, 591, 591, 1740, 624, 558, 624, 558, 591, 591, 591, 591, 591, 1773, 558, 1773, 624, 1708, 624, 558, 624, 40000, 9391, 2364, 591, 40000),
        AtombergButton.SLEEP to intArrayOf(9424, 4663, 624, 558, 624, 558, 624, 558, 624, 558, 591, 591, 591, 591, 591, 591, 591, 591, 558, 1773, 624, 1708, 624, 558, 624, 558, 624, 1740, 591, 1773, 558, 1773, 591, 1740, 624, 558, 624, 1708, 624, 1773, 558, 1773, 591, 591, 624, 558, 591, 591, 591, 1740, 624, 1740, 591, 591, 591, 591, 558, 624, 558, 1773, 624, 1708, 624, 1740, 591, 591, 591, 40000, 9391, 2331, 624, 40000),
    )
}
