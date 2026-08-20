package com.cyclone.mobile.gateway

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.cyclone.mobile.R

/** Explicit user-facing developer surface. The gateway is OFF until the user enables this switch. */
class GatewaySettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var enabledSwitch: Switch
    private lateinit var statusView: TextView
    private lateinit var tokenView: TextView
    private lateinit var rotateButton: Button
    private lateinit var disconnectButton: Button
    private var suppressSwitchCallback = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, 750L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayRuntime.startIfEnabled(this)
        title = "Cyclone PC Gateway"
        setContentView(buildContent())
        renderState()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(TextView(this).apply {
            text = "PC Gateway (USB debugging)"
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())
        column.addView(TextView(this).apply {
            text = "Expose Cyclone's semantic Accessibility, Page Awareness, App Graph, Brain and teaching tools to a USB-connected PC. The endpoint uses Android localabstract only; it never opens a LAN port."
            textSize = 16f
            setPadding(0, dp(10), 0, dp(18))
        }, matchWrap())

        enabledSwitch = Switch(this).apply {
            text = "PC Gateway (USB debugging)"
            textSize = 18f
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitchCallback) return@setOnCheckedChangeListener
                if (checked) GatewayRuntime.enable(this@GatewaySettingsActivity)
                else GatewayRuntime.disable(this@GatewaySettingsActivity)
                renderState()
            }
        }
        column.addView(enabledSwitch, matchWrap())

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(18), 0, dp(12))
        }
        column.addView(statusView, matchWrap())

        column.addView(TextView(this).apply {
            text = "Session token"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())
        tokenView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        column.addView(tokenView, matchWrap())
        column.addView(TextView(this).apply {
            text = "Use this token only for the current trusted PC session. Rotating it immediately disconnects existing clients. Cyclone never writes it to gateway logs."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(14))
        }, matchWrap())

        rotateButton = Button(this).apply {
            text = "Rotate session token"
            setOnClickListener {
                runCatching { GatewayRuntime.rotateToken(this@GatewaySettingsActivity) }
                renderState()
            }
        }
        column.addView(rotateButton, matchWrap())
        disconnectButton = Button(this).apply {
            text = "Disconnect PC session"
            setOnClickListener { GatewayRuntime.disconnect(); renderState() }
        }
        column.addView(disconnectButton, matchWrap())

        column.addView(TextView(this).apply {
            text = "Windows / Agent 1\n\nadb forward tcp:8766 localabstract:cyclone_gateway\n\nThen connect to 127.0.0.1:8766 and send one UTF-8 JSON request per line using the session token above."
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, dp(22), 0, dp(10))
        }, matchWrap())
        column.addView(TextView(this).apply {
            text = "Safety: PC_CODEX actions still go through Cyclone's existing controller and semantic action engine. Password/OTP entry, arbitrary root shell and high-risk semantic controls are not exposed by this gateway."
            textSize = 13f
        }, matchWrap())
        return scroll
    }

    private fun renderState() {
        val enabled = GatewayRuntime.isEnabled(this)
        suppressSwitchCallback = true
        enabledSwitch.isChecked = enabled
        suppressSwitchCallback = false
        val status = GatewayRuntime.status(this)
        val session = status.optJSONObject("connectedSession")
        statusView.text = buildString {
            append(if (enabled) "Gateway: ON" else "Gateway: OFF")
            append("\nListener: ")
            append(if (status.optBoolean("socketListening")) "ready on localabstract:cyclone_gateway" else "stopped")
            append("\nAccessibility: ")
            append(if (status.optBoolean("accessibilityConnected")) "connected" else "not connected")
            append("\nPC/ADB session: ")
            append(if (session?.optBoolean("connected") == true) "connected (${session.optInt("clientCount")} client)" else "not connected")
            status.optString("lastError").takeIf { it.isNotBlank() && it != "null" && it != "<redacted>" }?.let { append("\nListener error: ").append(it) }
        }
        tokenView.text = if (enabled) GatewayRuntime.tokenForUser(this).orEmpty() else "Enable the gateway to create a new random token."
        rotateButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled && session?.optBoolean("connected") == true
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
