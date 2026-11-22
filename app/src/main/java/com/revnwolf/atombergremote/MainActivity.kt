package com.revnwolf.atombergremote

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var ir: ConsumerIrManager? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir?.hasIrEmitter() != true) {
            status.text = getString(R.string.no_ir)
        }
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
                send(button, (v as Button).text)
            }
        }
    }

    private fun send(button: AtombergButton, label: CharSequence) {
        val manager = ir
        if (manager == null || !manager.hasIrEmitter()) {
            Toast.makeText(this, R.string.no_ir, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            manager.transmit(NecEncoder.CARRIER_HZ, NecEncoder.pattern(button.command))
            status.text = getString(R.string.sent, label.toString().replace('\n', ' '))
        } catch (e: Exception) {
            status.text = getString(R.string.send_failed, e.message ?: e.javaClass.simpleName)
        }
    }
}
