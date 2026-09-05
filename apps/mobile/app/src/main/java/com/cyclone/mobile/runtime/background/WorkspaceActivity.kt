package com.cyclone.mobile.runtime.background

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import rikka.shizuku.Shizuku

/** User chooses the target app explicitly; the task's identity is fixed before model execution. */
class WorkspaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply { text = "Keep using your phone"; textSize = 26f }
        val description = TextView(this).apply {
            text = "Cyclone can work in a separate app workspace. You'll take over for payment and other sensitive steps."
            textSize = 16f; setPadding(0, 20, 0, 24)
        }
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@WorkspaceActivity, android.R.layout.simple_spinner_dropdown_item,
                apps.map { it.loadLabel(packageManager).toString() })
            contentDescription = "App for this task"
        }
        val goal = EditText(this).apply { hint = "What would you like done?"; minLines = 3 }
        val status = TextView(this)
        val authorize = Button(this).apply {
            text = "Enable background work"
            setOnClickListener {
                runCatching {
                    check(Shizuku.pingBinder()) { "Start Shizuku on this phone first." }
                    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(902)
                    else status.text = "Background access is enabled."
                }.onFailure { status.text = it.message }
            }
        }
        val start = Button(this).apply {
            text = "Start task"
            setOnClickListener {
                if (goal.text.isBlank() || apps.isEmpty()) { status.text = "Choose an app and describe your task."; return@setOnClickListener }
                startForegroundService(Intent(this@WorkspaceActivity, WorkspaceTaskService::class.java)
                    .putExtra("package", apps[picker.selectedItemPosition].activityInfo.packageName)
                    .putExtra("label", apps[picker.selectedItemPosition].loadLabel(packageManager).toString())
                    .putExtra("goal", goal.text.toString()))
                finish()
            }
        }
        listOf(title, description, picker, goal, authorize, start, status).forEach {
            layout.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(layout)
    }
}
