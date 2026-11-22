package com.revnwolf.atombergremote

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private var ir: ConsumerIrManager? = null
    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var modeBtn: Button
    private var mode = SignalMode.STANDARD
    private var carrierHz = NecEncoder.CARRIER_HZ
    private var deviceInfo = ""
    private val sender = Executors.newSingleThreadExecutor() // transmit() blocks until sent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        diag = findViewById(R.id.diag)
        modeBtn = findViewById(R.id.btnMode)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        mode = SignalMode.entries.getOrElse(prefs.getInt("mode", 0)) { SignalMode.STANDARD }
        modeBtn.text = getString(R.string.mode, mode.label)
        modeBtn.setOnClickListener {
            mode = SignalMode.entries[(mode.ordinal + 1) % SignalMode.entries.size]
            prefs.edit().putInt("mode", mode.ordinal).apply()
            modeBtn.text = getString(R.string.mode, mode.label)
        }

        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        probe()

        mapOf(
            R.id.btnPower to AtombergButton.POWER,
            R.id.btnS1 to AtombergButton.SPEED_1,
            R.id.btnS2 to AtombergButton.SPEED_2,
            R.id.btnS3 to AtombergButton.SPEED_3,
            R.id.btnS4 to AtombergButton.SPEED_4,
            R.id.btnS5 to AtombergButton.SPEED_5,
            R.id.btnBoost to AtombergButton.BOOST,
            R.id.btnSleep to AtombergButton.SLEEP,
            R.id.btnT1 to AtombergButton.TIMER_1H,
            R.id.btnT2 to AtombergButton.TIMER_2H,
            R.id.btnT3 to AtombergButton.TIMER_3H,
            R.id.btnT6 to AtombergButton.TIMER_6H,
        ).forEach { (id, button) ->
            findViewById<Button>(id).setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                send(button, (v as Button).text.toString().replace('\n', ' '))
            }
        }
    }

    /** Report what the phone says about its IR hardware, and pick a carrier it supports. */
    private fun probe() {
        val feature = packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)
        val manager = ir
        val emitter = try { manager?.hasIrEmitter() == true } catch (e: Exception) { false }
        val ranges = try { manager?.carrierFrequencies } catch (e: Exception) { null }
        if (!ranges.isNullOrEmpty() && ranges.none { carrierHz in it.minFrequency..it.maxFrequency }) {
            // 38 kHz not listed: use the closest supported frequency instead of failing.
            carrierHz = ranges.map { it.minFrequency.coerceAtLeast(0) to it.maxFrequency }
                .map { (lo, hi) -> carrierHz.coerceIn(lo, hi) }
                .minByOrNull { kotlin.math.abs(it - NecEncoder.CARRIER_HZ) } ?: carrierHz
        }
        val rangeText = ranges?.joinToString { "${it.minFrequency / 1000}-${it.maxFrequency / 1000}k" } ?: "n/a"
        deviceInfo = "feature=$feature emitter=$emitter carriers=$rangeText using=${carrierHz / 1000}kHz"
        diag.text = deviceInfo
        if (!emitter) status.text = getString(R.string.no_ir)
    }

    private fun send(button: AtombergButton, label: String) {
        val manager = ir
        if (manager == null || !manager.hasIrEmitter()) {
            status.text = getString(R.string.no_ir)
            return
        }
        val pattern = NecEncoder.pattern(button.command, mode, carrierHz)
        val expectedMs = NecEncoder.durationMs(NecEncoder.pattern(button.command,
            if (mode == SignalMode.LEGACY) SignalMode.STANDARD else mode))
        sender.execute {
            val t0 = SystemClock.elapsedRealtime()
            val error = try {
                manager.transmit(carrierHz, pattern); null
            } catch (e: Exception) {
                e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            }
            val took = SystemClock.elapsedRealtime() - t0
            runOnUiThread {
                status.text = if (error == null) getString(R.string.sent, label)
                              else getString(R.string.send_failed, error)
                // transmit() is synchronous: returning far faster than the pattern length means
                // the HAL accepted the call but likely never fired the LED.
                val warn = if (error == null && took < expectedMs / 2) "  ⚠ returned too fast" else ""
                diag.text = "$deviceInfo\nlast: ${mode.label}, ${took}ms (signal ${expectedMs}ms)$warn"
            }
        }
    }

    override fun onDestroy() {
        sender.shutdown()
        super.onDestroy()
    }
}
