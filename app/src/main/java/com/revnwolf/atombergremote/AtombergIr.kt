package com.revnwolf.atombergremote

/**
 * Atomberg fan IR codes (NEC protocol, 38 kHz, extended address 0x00/0xF3).
 *
 * Command bytes are the LSB-first values (same convention as ESPHome transmit_nec,
 * where Atomberg = address 0xF300). MSB-first 32-bit equivalents, as captured with
 * Arduino IRremote, are noted beside each one (e.g. Power = 0x00CF8976).
 */
enum class AtombergButton(val command: Int) {
    POWER(0x91),    // 00CF8976
    SPEED_1(0x8B),  // 00CFD12E
    SPEED_2(0x90),  // 00CF09F6
    SPEED_3(0x8A),  // 00CF51AE
    SPEED_4(0x93),  // 00CFC936
    SPEED_5(0x88),  // 00CF11EE
    BOOST(0x8F),    // 00CFF10E
    SLEEP(0x8E),    // 00CF718E
    TIMER_1H(0x85), // 00CFA15E
    TIMER_2H(0x86), // 00CF619E
    TIMER_3H(0x92), // 00CF49B6
    TIMER_6H(0x8C), // 00CF31CE
}

object NecEncoder {
    const val CARRIER_HZ = 38_000
    private const val ADDRESS_LOW = 0x00
    private const val ADDRESS_HIGH = 0xF3
    private const val HDR_MARK = 9000
    private const val HDR_SPACE = 4500
    private const val BIT_MARK = 562
    private const val ONE_SPACE = 1687
    private const val ZERO_SPACE = 562

    /** Alternating mark/space durations in microseconds, as ConsumerIrManager.transmit expects. */
    fun pattern(command: Int): IntArray {
        val bytes = intArrayOf(ADDRESS_LOW, ADDRESS_HIGH, command and 0xFF, command.inv() and 0xFF)
        val out = ArrayList<Int>(2 + 32 * 2 + 1)
        out += HDR_MARK
        out += HDR_SPACE
        for (b in bytes) {
            for (i in 0 until 8) { // NEC sends each byte LSB first
                out += BIT_MARK
                out += if (((b shr i) and 1) == 1) ONE_SPACE else ZERO_SPACE
            }
        }
        out += BIT_MARK // stop bit
        return out.toIntArray()
    }
}
