package com.cyclone.mobile.gateway

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** In-app control center for Cyclone's USB-only PC/Codex gateway. */
class GatewaySettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var enabledSwitch: Switch
    private lateinit var stateView: TextView
    private lateinit var statusView: TextView
    private lateinit var tokenView: TextView
    private lateinit var copyButton: Button
    private lateinit var rotateButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var diagnosticsView: TextView
    private var diagnosticsVisible = false
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
        title = "Full PC + Codex Gateway"
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
            text = "Full PC + Codex Gateway"
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())
        column.addView(TextView(this).apply {
            text = "Connect this Cyclone app to a trusted Windows PC over USB. Android policy and PhoneToolExecutor remain the authority for phone actions."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(10))
        }, matchWrap())

        stateView = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        }
        column.addView(stateView, matchWrap())

        enabledSwitch = Switch(this).apply {
            text = "Enable Gateway"
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
            setPadding(0, dp(12), 0, dp(12))
        }
        column.addView(statusView, matchWrap())

        column.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, matchWrap())

        column.addView(TextView(this).apply {
            text = "Session token"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, 0)
        }, matchWrap())
        tokenView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        column.addView(tokenView, matchWrap())

        copyButton = Button(this).apply {
            text = "Copy session token"
            setOnClickListener {
                GatewayRuntime.tokenForUser(this@GatewaySettingsActivity)?.takeIf(String::isNotBlank)?.let { token ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cyclone Gateway session token", token))
                    Toast.makeText(this@GatewaySettingsActivity, "Session token copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        column.addView(copyButton, matchWrap())

        rotateButton = Button(this).apply {
            text = "Rotate token"
            setOnClickListener {
                GatewayRuntime.rotateToken(this@GatewaySettingsActivity)
                renderState()
            }
        }
        column.addView(rotateButton, matchWrap())

        disconnectButton = Button(this).apply {
            text = "Disconnect PC"
            setOnClickListener { GatewayRuntime.disconnect(); renderState() }
        }
        column.addView(disconnectButton, matchWrap())

        column.addView(TextView(this).apply {
            text = "Windows setup\n1. Connect USB and enable USB debugging.\n2. Run setup-cyclone-bridge.ps1 once.\n3. Copy the session token above.\n4. Run start-cyclone-bridge.ps1 and paste the token when asked."
            textSize = 15f
            setPadding(0, dp(18), 0, dp(8))
        }, matchWrap())

        diagnosticsView = TextView(this).apply {
            textSize = 13f
            visibility = View.GONE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(Button(this).apply {
            text = "Show diagnostics"
            setOnClickListener {
                diagnosticsVisible = !diagnosticsVisible
                text = if (diagnosticsVisible) "Hide diagnostics" else "Show diagnostics"
                diagnosticsView.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
                renderState()
            }
        }, matchWrap())
        column.addView(diagnosticsView, matchWrap())

        column.addView(TextView(this).apply {
            text = "USB only · no LAN listener · no arbitrary shell or root · typed values are not written to Gateway diagnostics."
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
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
        val connected = session?.optBoolean("connected") == true
        val accessibility = status.optBoolean("accessibilityConnected")
        val state = when (status.optString("gatewayState")) {
            "CONNECTED" -> "CONNECTED"
            "WAITING_FOR_PC" -> "WAITING FOR PC"
            "ATTENTION_NEEDED" -> "ATTENTION NEEDED"
            else -> "OFF"
        }
        stateView.text = state
        statusView.text = buildString {
            append("Gateway: ").append(if (enabled) "On" else "Off")
            append("\nPhone control: ").append(if (accessibility) "Ready" else "Accessibility needs attention")
            append("\nUSB / PC session: ").append(if (connected) "Connected" else if (enabled) "Waiting for PC" else "Off")
            status.optString("lastSafeError").takeIf { it.isNotBlank() && it != "null" }?.let {
                append("\n\nAttention: ").append(it)
            }
        }

        tokenView.text = if (enabled) GatewayRuntime.tokenForUser(this).orEmpty() else "Enable the Gateway to create a session token."
        copyButton.isEnabled = enabled && GatewayRuntime.tokenForUser(this)?.isNotBlank() == true
        rotateButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled && connected

        if (diagnosticsVisible) {
            diagnosticsView.text = buildString {
                append("Android socket: ").append(if (status.optBoolean("socketListening")) "READY" else "OFF")
                append("\nADB clients: ").append(session?.optInt("clientCount") ?: 0)
                append("\nProtocol: ").append(status.optString("protocolVersion"))
                append("\nAction authority: ").append(status.optString("actionAuthorityBinding"))
                append("\nProduction authority bound: ").append(status.optBoolean("productionActionAuthorityBound"))
                append("\nForward: tcp:8766 -> localabstract:cyclone_gateway")
                status.optString("lastError").takeIf { it.isNotBlank() && it != "null" }?.let {
                    append("\nListener error: ").append(it)
                }
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
