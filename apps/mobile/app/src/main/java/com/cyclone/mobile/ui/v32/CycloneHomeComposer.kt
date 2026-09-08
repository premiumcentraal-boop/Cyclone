package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun CycloneHomeComposer(onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var tools by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = listOf(text, it).filter(String::isNotBlank).joinToString(" ") }
    }
    CycloneGlassSurface {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            BasicTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().padding(10.dp).heightIn(min = 60.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 5,
                decorationBox = { field -> Box { if (text.isEmpty()) Text("What should Cyclone do?", color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CycloneIntelligenceControls()
                Box {
                    IconButton(onClick = { tools = true }) { Icon(Icons.Rounded.Add, "Add attachment") }
                    DropdownMenu(tools, { tools = false }) {
                        listOf("File" to false, "Take photo" to true).forEach { (label, camera) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { tools = false; context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("camera", camera)) })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }.onFailure { error = "Voice is unavailable. You can type your request." } }) { Icon(Icons.Rounded.Mic, "Dictate request") }
                FilledIconButton(onClick = { onSubmit(text.trim()); text = "" }, enabled = text.isNotBlank()) { Icon(Icons.Rounded.ArrowUpward, "Send request") }
            }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
