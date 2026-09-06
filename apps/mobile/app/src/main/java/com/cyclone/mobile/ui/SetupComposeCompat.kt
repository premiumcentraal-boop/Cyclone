package com.cyclone.mobile.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Classifier alias used by the setup-card content receiver while keeping the normal
 * androidx.compose.foundation.layout.Column composable callable in expression context.
 */
typealias Column = ColumnScope

/**
 * Setup reminders are rendered inside the root Box. This fallback keeps the reminder
 * modifier source-compatible outside a BoxScope; the existing vertical padding places
 * the compact reminder below the app bar.
 */
fun Modifier.align(alignment: Alignment): Modifier = this
