package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * First-stage retraction for in-app Ask Cyclone.
 *
 * Unlike the tiny launcher, this remains a real composer: type, add, choose model/intelligence,
 * dictate or send immediately. The up affordance restores the expanded drawer.
 */
@Composable
internal fun CycloneMinimizedComposerBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onExpand: () -> Unit,
    onAdd: () -> Unit,
    onModelAndIntelligence: () -> Unit,
    onVoice: () -> Unit,
    onSubmit: () -> Unit,
    modelLabel: String,
    intelligenceLabel: String,
    sendEnabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = cycloneConversationPalette()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CycloneConversationTokens.composerRadius),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(.8.dp, palette.cardOutline),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                onClick = onAdd,
                enabled = !busy,
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .70f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "Add", Modifier.size(23.dp))
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(
                    modifier = Modifier
                        .clickable(enabled = !busy, role = Role.Button, onClick = onModelAndIntelligence)
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                        .semantics { contentDescription = "Model and intelligence" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        intelligenceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                BasicTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp, max = 64.dp)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                        .semantics { contentDescription = "Ask Cyclone minimized composer" },
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSubmit() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    "Ask Cyclone…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            field()
                        }
                    },
                )
            }

            Surface(
                onClick = if (text.isBlank() && !busy) onVoice else onSubmit,
                enabled = if (text.isBlank() && !busy) true else sendEnabled,
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (text.isBlank() && !busy) Icons.Rounded.GraphicEq else Icons.Rounded.ArrowUpward,
                        if (text.isBlank() && !busy) "Voice mode" else "Send request",
                        Modifier.size(21.dp),
                    )
                }
            }

            Surface(
                onClick = onExpand,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .60f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Expand chat", Modifier.size(21.dp))
                }
            }
        }
    }
}
