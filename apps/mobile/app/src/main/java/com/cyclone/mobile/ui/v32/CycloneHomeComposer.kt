package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
        // Home is a launcher, not a second AI settings surface. Model and intelligence live in
        // the AI workspace; phone autonomy lives in Settings.
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
            CycloneAttachmentTools(
                onCamera = {
                    tools = false
                    context.startActivity(
                        Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                            .putExtra("camera", true),
                    )
                },
                onFiles = {
                    tools = false
                    context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
                },
                onPhotos = {
                    tools = false
                    context.startActivity(
                        Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                            .putExtra("photos", true),
                    )
                },
                onShareScreen = {
                    tools = false
                    com.cyclone.mobile.capture.LiveScreenShare.start(context)
                },
            )
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

@Composable
internal fun CycloneAttachmentTools(
    onCamera: () -> Unit,
    onFiles: () -> Unit,
    onShareScreen: () -> Unit,
    onPhotos: (() -> Unit)? = null,
    extras: List<Pair<ImageVector, String>> = emptyList(),
    tileExtras: List<Pair<ImageVector, String>> = emptyList(),
    onExtra: (String) -> Unit = {},
    filesLabel: String = "Files & photos",
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .padding(top = 2.dp, bottom = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = .45f)),
            )
        }
        val tiles = buildList {
            add(Triple(Icons.Rounded.CameraAlt, "Camera", onCamera))
            add(Triple(Icons.Rounded.PhotoLibrary, "Photos", onPhotos ?: onFiles))
            add(Triple(Icons.Rounded.AttachFile, filesLabel, onFiles))
            tileExtras.forEach { (icon, label) -> add(Triple(icon, label, { onExtra(label) })) }
        }
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label, click) ->
                    CyclonePlusTile(icon, label, Modifier.weight(1f), click)
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            extras.forEach { (icon, label) ->
                CycloneAttachmentToolRow(icon, label) { onExtra(label) }
            }
            CycloneAttachmentToolRow(Icons.Rounded.ScreenShare, "Share screen", onShareScreen)
        }
    }
}

@Composable
private fun CyclonePlusTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun CycloneAttachmentToolRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
