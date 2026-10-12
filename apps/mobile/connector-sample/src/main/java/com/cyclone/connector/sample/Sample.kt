package com.cyclone.connector.sample

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cyclone.connector.client.CycloneConnector
import com.cyclone.connector.client.CycloneConnectorException
import org.json.JSONObject
import kotlin.concurrent.thread

/** The marker Cyclone looks for. Cyclone never binds it. */
class CycloneConnect : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

/** Cyclone says new events wait: pull them and remember how many arrived. */
class CycloneWake : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                val prefs = context.getSharedPreferences("sample", Context.MODE_PRIVATE)
                CycloneConnector.connect(context).use { cyclone ->
                    val page = cyclone.events(prefs.getLong("since", 0))
                    prefs.edit().putLong("since", page.getLong("next"))
                        .putInt("events", prefs.getInt("events", 0) + page.getJSONArray("events").length())
                        .putLong("wokeAt", System.currentTimeMillis()).apply()
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** Cyclone opens this when the owner taps one of our entries. */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 18f
            text = "Opened from Cyclone.\nEntry: ${intent.getStringExtra(CycloneConnector.EXTRA_ENTRY_ID) ?: "(none)"}"
        })
    }
}

/** Buttons for every call, and what Cyclone answered. */
class SampleActivity : Activity() {
    private lateinit var log: TextView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        fun button(label: String, action: (CycloneConnector) -> Any) = column.addView(Button(this).apply {
            text = label
            setOnClickListener { run(label, action) }
        })
        button("Hello") { it.hello() }
        button("Profiles") { it.profiles() }
        button("Note on the first Cyclone profile") { c ->
            val id = c.profiles().getJSONArray("profiles").let { list ->
                (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("kind") == "profile" }?.getString("id")
            } ?: return@button "No Cyclone profile yet."
            c.setExt(id, JSONObject().put("note", "Hello from the sample").put("at", System.currentTimeMillis()))
        }
        button("Add two entries") {
            it.setEntries(listOf(
                CycloneConnector.Entry("sample-ready", "sample.demo", "Sample space", "Opened by the sample", "ic_sample"),
                CycloneConnector.Entry("sample-attention", "sample.demo", "Sample sign-in", "", null, "attention", "Sign in again"),
            ))
        }
        button("Clear entries") { it.setEntries(emptyList()) }
        button("Events since the start") { it.events(0) }
        button("Last wake") {
            val prefs = getSharedPreferences("sample", Context.MODE_PRIVATE)
            "Events pulled after wakes: ${prefs.getInt("events", 0)}, last wake at ${prefs.getLong("wokeAt", 0)}"
        }
        column.addView(log)
        setContentView(ScrollView(this).apply { addView(column) })
        show("Cyclone installed: ${CycloneConnector.isCycloneInstalled(this)}")
    }

    private fun run(label: String, action: (CycloneConnector) -> Any) {
        thread {
            val text = try {
                CycloneConnector.connect(this).use { c ->
                    when (val result = action(c)) {
                        is JSONObject -> result.toString(2)
                        else -> result.toString()
                    }
                }
            } catch (e: CycloneConnectorException) {
                "${e.code}: ${e.message}"
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            main.post { show("— $label\n$text") }
        }
    }

    private fun show(text: String) {
        log.text = text + "\n\n" + log.text
    }
}
