package com.cyclone.mobile.ui.overlay

import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks

/** Shares the existing accessibility overlay host; outside its bounds belongs to the human app. */
@Composable
fun BackgroundTaskGlass(task: WorkspaceTaskUi, onAsk: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(task.packageName) { runCatching { context.packageManager.getApplicationIcon(task.packageName) }.getOrNull() }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
        .padding(start = 12.dp, end = 12.dp, bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xF21A2329), shadowElevation = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (icon != null) AndroidView(factory = { ImageView(it) }, update = {
                        it.setImageDrawable(icon); it.contentDescription = task.app
                    }, modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(task.subtitle, color = Color(0xFFBCCBD3), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                Button(onClick = { context.startActivity(WorkspaceTasks.progressIntent(context, task)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("View progress") }
            }
        }
        Surface(onClick = onAsk, shape = RoundedCornerShape(30.dp), color = Color(0xF21A2329)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ask Cyclone", modifier = Modifier.weight(1f), color = Color.White.copy(alpha = .75f))
                if (task.working) TextButton(onClick = { WorkspaceTasks.command(context, task, "cancel") }) { Text("■", color = Color.White) }
            }
        }
    }
}
