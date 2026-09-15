package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun CycloneHomeComposer(onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    val backdrop = LocalCycloneLiquidBackdrop.current
    var text by rememberSaveable { mutableStateOf("") }
    var tools by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                text = listOf(text, it).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }

    fun send() {
        val request = text.trim()
        if (request.isBlank()) return
        onSubmit(request)
        text = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Home is a launcher, not a second AI settings surface. Model/intelligence/autonomy live in
        // the AI workspace; this launcher stays focused on request, attachment, voice and send.
        CycloneLiquidPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CycloneTrayIconAction(
                    onClick = { tools = !tools },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        "Add attachment",
                        modifier = Modifier.size(22.dp),
                        tint = if (tools) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 72.dp)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .semantics { contentDescription = "Home request composer" },
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    "Ask Cyclone",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            field()
                        }
                    },
                )

                CycloneTrayIconAction(
                    onClick = {
                        runCatching {
                            voice.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                            )
                        }.onFailure { error = "Voice is unavailable. You can type your request." }
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        "Dictate request",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (backdrop != null) {
                    CycloneKyantLiquidIconButton(
                        onClick = { send() },
                        backdrop = backdrop,
                        enabled = text.isNotBlank(),
                        modifier = Modifier.size(46.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            "Send request",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else {
                    CycloneTrayIconAction(onClick = { send() }, enabled = text.isNotBlank(), modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Rounded.ArrowUpward, "Send request", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        if (tools) {
            CycloneLiquidPanel(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        tools = false
                        context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
                    }) { Text("File") }
                    TextButton(onClick = {
                        tools = false
                        context.startActivity(
                            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                                .putExtra("camera", true),
                        )
                    }) { Text("Photo") }
                    TextButton(onClick = {
                        tools = false
                        context.startActivity(Intent(context, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java))
                    }) { Text("Share screen") }
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                error,
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
