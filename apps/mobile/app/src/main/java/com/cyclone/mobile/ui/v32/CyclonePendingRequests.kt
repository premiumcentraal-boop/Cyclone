package com.cyclone.mobile.ui.v32

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.background.WorkspaceActivity

/** Shared pending requests. Starting always enters existing app selection and execution checks. */
@Composable
fun CyclonePendingRequests(onOpen: () -> Unit = {}) {
    val requests by WorkspaceTasks.requests.state.collectAsState()
    val current by WorkspaceTasks.state.collectAsState()
    val overlay by com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.activity.collectAsState()
    val context = LocalContext.current
    if (requests.isEmpty()) return
    CycloneGlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
            Text("Up next · ${requests.size}", style = MaterialTheme.typography.titleSmall)
            Text("Saved for this session. Start when the current task is closed.", style = MaterialTheme.typography.bodySmall)
            requests.forEach { request ->
                Row(Modifier.fillMaxWidth()) {
                    Text(request.goal, maxLines = 2, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(enabled = remember(current, overlay) { WorkspaceTasks.canStartRequest() }, onClick = {
                        onOpen()
                        context.startActivity(Intent(context, WorkspaceActivity::class.java)
                            .putExtra("goal", request.goal).putExtra("pendingRequestId", request.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Start") }
                    TextButton(onClick = { WorkspaceTasks.requests.remove(request.id) }) { Text("Remove") }
                }
            }
        }
    }
}
