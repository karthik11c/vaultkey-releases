package com.revnwolf.atombergremote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * IR diagnostics. The phone can't receive IR, so each test isolates one link of the chain:
 * HAL reports → HAL really drives the LED for the right time → LED visible on camera →
 * which signal shape / carrier / code the fan accepts.
 */
class TesterActivity : Activity() {

    private var ir: ConsumerIrManager? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    private var testButton = AtombergButton.POWER

    private lateinit var log: TextView
    private lateinit var big: TextView
    private lateinit var stop: Button
    private lateinit var reacted: Button
    @Volatile private var currentVariant = 0
    private val hits = java.util.Collections.synchronizedSet(sortedSetOf<Int>())
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** One sweep entry: the transmit() payloads to send ~2 ms apart (null = not available for this button). */
    private class Variant(val name: String, val carrier: Int, val calls: (AtombergButton) -> List<IntArray>?)

    private fun single(name: String, carrier: Int, build: (AtombergButton) -> IntArray?) =
        Variant(name, carrier) { b -> build(b)?.let { listOf(it) } }

    private val variants = listOf(
        single("NEC 1 frame + gap, 38k", 38_000) { Nec.build(it.bytes) },
        single("NEC 1 frame, no gap (odd len), 38k", 38_000) { Nec.build(it.bytes, trailingGap = false) },
        single("NEC frame + 2 repeats, 38k", 38_000) { Nec.build(it.bytes, repeatCodes = 2) },
        single("NEC 2 full frames, 38k", 38_000) { Nec.build(it.bytes, frames = 2) },
        single("NEC 3 full frames, 38k", 38_000) { Nec.build(it.bytes, frames = 3) },
        single("NEC frame + gap, 36k", 36_000) { Nec.build(it.bytes) },
        single("NEC frame + gap, 40k", 40_000) { Nec.build(it.bytes) },
        single("NEC frame + 2 repeats, 36k", 36_000) { Nec.build(it.bytes, repeatCodes = 2) },
        single("NEC frame + 2 repeats, 40k", 40_000) { Nec.build(it.bytes, repeatCodes = 2) },
        single("NEC 3 frames, 10ms gaps, 38k", 38_000) { Nec.build(it.bytes, frames = 3, gap = 10_000) },
        single("Pronto-style (whole cycles) + 1 repeat, 38k", 38_000) {
            Nec.quantize(Nec.build(it.bytes, repeatCodes = 1), 38_000) },
        single("Real-remote timing + 1 repeat, 38k", 38_000) {
            Nec.build(it.bytes, repeatCodes = 1, t = NecTiming.REMOTE) },
        single("Real-remote timing, Pronto-style, 38k", 38_000) {
            Nec.quantize(Nec.build(it.bytes, repeatCodes = 1, t = NecTiming.REMOTE), 38_000) },
        single("Replay of real remote capture, 38k", 38_000) { Captured.CAPTURED[it] },
        single("Replay of capture, Pronto-style, 38k", 38_000) {
            Captured.CAPTURED[it]?.let { p -> Nec.quantize(p, 38_000) } },
        Variant("Double-send: 2 calls, 2 ms apart (like RMP app)", 38_000) { Nec.doubleSend(it.bytes) },
        single("Double frame in ONE call, 3 ms gap", 38_000) {
            val (a, b) = Nec.doubleSend(it.bytes); a + intArrayOf(3_000) + b },
        Variant("2 calls of variant #1 (frame + gap)", 38_000) { b -> List(2) { Nec.build(b.bytes) } },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        buildUi()
        logLine(deviceReport())
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(text("IR Tester", 24f, bold = true))
        root.addView(text("The phone can't see IR, so each test checks one part. " +
            "Tap Copy log when done and send it.", 13f, muted = true))

        big = text("", 56f, bold = true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(big)
        stop = button("■ Stop") { cancelled = true }.apply { visibility = View.GONE }
        root.addView(stop)
        reacted = button("✋ Fan reacted!") {
            val n = currentVariant
            if (n > 0 && hits.add(n)) logLine("👉 Fan reacted at #$n ${variants[n - 1].name}")
            Toast.makeText(this, "Marked #$n", Toast.LENGTH_SHORT).show()
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
            textSize = 22f
            setBackgroundResource(R.drawable.power_bg)
        }
        root.addView(reacted)

        root.addView(section("1 · Hardware"))
        root.addView(row(button("Device report") { logLine(deviceReport()) },
                         button("Timing test") { run("Timing test") { timingTest() } }))

        root.addView(section("2 · Is the LED firing?"))
        root.addView(text("Open another phone's camera (front cameras work best), point it at the " +
            "top edge of this phone, then start. You should see a blinking light.", 13f, muted = true))
        root.addView(button("Camera beacon (10 s)") { run("Beacon") { beacon() } })

        root.addView(section("3 · Which signal does the fan accept?"))
        root.addView(text("Sends the chosen button in ${variants.size} variants, 5 s apart, with a big " +
            "number on screen. Stand 1–2 m from the fan; tap “Fan reacted!” whenever it beeps or changes.", 13f, muted = true))
        val pick = button("") {}
        fun refreshPick() { pick.text = "Test button: ${testButton.label} (tap to change)" }
        pick.setOnClickListener {
            testButton = AtombergButton.entries[(testButton.ordinal + 1) % AtombergButton.entries.size]
            refreshPick()
        }
        refreshPick()
        root.addView(pick)
        root.addView(button("Start variant sweep") { run("Sweep") { sweep() } })
        root.addView(text("Variant list:\n" + variants.mapIndexed { i, v -> "  ${i + 1}. ${v.name}" }
            .joinToString("\n"), 12f, muted = true, mono = true))

        root.addView(section("4 · Custom code"))
        input = EditText(this).apply {
            hint = "00CF8976  ·  F300 91  ·  0000 006D …  ·  9000 4500 562 …"
            setTextColor(color(R.color.fg)); setHintTextColor(color(R.color.muted))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
        }
        root.addView(input)
        root.addView(text("NEC hex (Arduino IRremote), ESPHome address+command, Pronto hex, or raw µs. " +
            "Add freq=36000 to change carrier.", 12f, muted = true))
        root.addView(row(button("Send once") { sendCustom(1) }, button("Send ×5") { sendCustom(5) }))

        root.addView(section("Log"))
        root.addView(row(button("Copy log") { copyLog() }, button("Clear") { log.text = "" }))
        log = text("", 11f, mono = true).apply { setTextIsSelectable(true) }
        root.addView(log)
    }

    // ---------------------------------------------------------------- tests

    private fun deviceReport(): String {
        val m = ir
        val feature = packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)
        val emitter = runCatching { m?.hasIrEmitter() }.getOrNull()
        val ranges = runCatching { m?.carrierFrequencies }
        val rangeText = ranges.fold(
            { r -> r?.joinToString { "${it.minFrequency}-${it.maxFrequency}Hz" }?.ifEmpty { "empty list" } ?: "null" },
            { "error ${it.javaClass.simpleName}" })
        val verdict = when {
            m == null -> "✗ No IR service on this ROM"
            emitter != true -> "✗ Phone says it has no IR emitter"
            else -> "✓ IR emitter reported"
        }
        return """
            |=== Device ===
            |${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}
            |ROM: ${Build.DISPLAY}
            |FEATURE_CONSUMER_IR=$feature  hasIrEmitter=$emitter
            |carriers: $rangeText
            |$verdict
        """.trimMargin()
    }

    /** transmit() is synchronous, so a real driver takes ~the pattern length. Instant = fake/no-op. */
    private fun timingTest() {
        logLine("=== Timing test ===")
        var ok = 0
        val cases = listOf(20, 100, 500, 1500) // last one probes the per-call limit
        for (ms in cases) {
            if (cancelled) return
            val pattern = intArrayOf(ms * 500, ms * 500) // half on, half off
            val r = tx(38_000, pattern, "carrier ${ms}ms")
            if (r.error == null && r.tookMs >= ms * 0.6) ok++
            SystemClock.sleep(300)
        }
        logLine(when {
            ok == cases.size -> "✓ Driver blocks for the full signal time → HAL is really transmitting"
            ok == 0 -> "✗ Driver returns instantly → HAL accepts calls but probably never fires the LED (ROM blocks 3rd-party IR?)"
            ok == cases.size - 1 -> "✓ Driver transmits; it rejects signals over ~1 s (normal limit, fan codes are < 0.35 s)"
            else -> "? Mixed timings ($ok/${cases.size}) → driver may truncate signals"
        })
    }

    private fun beacon() {
        logLine("=== Beacon 10 s ===")
        val end = SystemClock.elapsedRealtime() + 10_000
        var sent = 0; var skipped = 0
        while (!cancelled && SystemClock.elapsedRealtime() < end) {
            // 200 ms on / 100 ms off: well under the driver's per-call limit
            val r = tx(38_000, intArrayOf(200_000, 100_000), null)
            if (r.error != null) { logLine("✗ ${r.error}"); return }
            if (r.tookMs < 150) { skipped++; SystemClock.sleep(300) } else sent++
            ui { big.text = "💡 ${((end - SystemClock.elapsedRealtime()) / 1000) + 1}" }
        }
        logLine(when {
            sent == 0 -> "✗ Driver returned instantly every time → LED did NOT blink (retry in a few seconds)"
            skipped > 0 -> "⚠ $sent bursts sent, $skipped returned instantly (driver hiccup)"
            else -> "✓ $sent bursts sent. Saw it on camera? Yes → LED works. No → hardware/ROM issue."
        })
    }

    private fun sweep() {
        logLine("=== Sweep: ${testButton.label} (cmd 0x%02X) ===".format(testButton.command))
        hits.clear()
        ui { reacted.visibility = View.VISIBLE }
        try {
            for ((i, v) in variants.withIndex()) {
                if (cancelled) return
                val calls = v.calls(testButton)
                if (calls == null) { logLine("#${i + 1} ${v.name}: no capture for ${testButton.label}, skipped"); continue }
                currentVariant = i + 1
                ui { big.text = "${i + 1} / ${variants.size}" }
                calls.forEachIndexed { c, p ->
                    if (c > 0) SystemClock.sleep(2)
                    tx(v.carrier, p, "#${i + 1}${if (calls.size > 1) "." + (c + 1) else ""} ${v.name}")
                }
                // 5 s window: the fan's beep / change and the user's tap both land on this number
                for (k in 0 until 50) { if (cancelled) return; SystemClock.sleep(100) }
            }
            val result = hits.toList()
            logLine(if (result.isEmpty()) "Sweep done. ✗ No variant marked as working."
                    else "Sweep done. ✓ Working variant(s): ${result.joinToString { "#$it" }}")
        } finally {
            currentVariant = 0
            ui { reacted.visibility = View.GONE }
        }
    }

    private fun sendCustom(times: Int) {
        val code = try { CodeParser.parse(input.text.toString()) } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return
        }
        run("Custom") {
            repeat(times) { n ->
                if (cancelled) return@run
                ui { big.text = "${n + 1}" }
                tx(code.carrierHz, code.pattern, "${code.description} @${code.carrierHz}Hz")
                SystemClock.sleep(if (times > 1) 1500 else 0)
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private data class TxResult(val tookMs: Long, val error: String?)

    private fun tx(carrier: Int, pattern: IntArray, label: String?): TxResult {
        val m = ir ?: return TxResult(0, "no IR service").also { logLine("✗ no IR service") }
        val expected = pattern.sumOf { it.toLong() } / 1000
        val t0 = SystemClock.elapsedRealtime()
        val err = try { m.transmit(carrier, pattern); null } catch (e: Exception) {
            e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
        val took = SystemClock.elapsedRealtime() - t0
        if (label != null) {
            val flag = when {
                err != null -> "✗ $err"
                took < expected / 2 -> "⚠ too fast"
                else -> "ok"
            }
            logLine("$label: ${pattern.size} vals, ${expected}ms signal, took ${took}ms → $flag")
        }
        return TxResult(took, err)
    }

    private fun run(name: String, block: () -> Unit) {
        cancelled = false
        stop.visibility = View.VISIBLE; big.visibility = View.VISIBLE; big.text = "…"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scroll.post { scroll.smoothScrollTo(0, 0) }
        worker.execute {
            try { block() } catch (e: Exception) { logLine("✗ $name crashed: $e") }
            if (cancelled) logLine("($name stopped)")
            ui {
                stop.visibility = View.GONE; big.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun logLine(s: String) = ui { log.append("[${time.format(Date())}] $s\n") }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("IR log", log.text))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    @Suppress("DEPRECATION") private fun color(id: Int) = resources.getColor(id)

    private fun text(s: String, size: Float, bold: Boolean = false, muted: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size
            setTextColor(color(if (muted) R.color.muted else R.color.fg))
            typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun section(s: String) = text(s.uppercase(), 13f, muted = true).apply {
        letterSpacing = 0.1f; setPadding(0, dp(20), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; stateListAnimator = null
        setBackgroundResource(R.drawable.key_bg); setTextColor(color(R.color.fg))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        views.forEach { v -> addView(v, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
    }

    override fun onDestroy() {
        cancelled = true
        worker.shutdownNow()
        super.onDestroy()
    }
}
