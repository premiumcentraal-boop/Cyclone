package com.cyclone.mobile.ui.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.platform.catalog.CatalogHealthTone
import com.cyclone.mobile.platform.catalog.CatalogModuleView
import com.cyclone.mobile.platform.catalog.CatalogReleaseChannel
import com.cyclone.mobile.platform.catalog.ModuleCatalogViewState
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.modules.ModuleDiagnosticSeverity

/**
 * Unwired Cyclone-native module management surface. Integration may place this under Settings;
 * this composable owns no supervisor, catalog, navigation, installation, or update state.
 */
@Composable
fun CycloneModulesScreen(
    state: ModuleCatalogViewState,
    onRefresh: () -> Unit,
    onSetEnabled: (ModuleId, Boolean) -> Unit,
    onClearQuarantine: (ModuleId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CatalogHeader(state, onRefresh)
        }
        item {
            LocalOnlyBanner(state.localOnlyMessage)
        }
        if (state.isEmpty) {
            item {
                EmptyCatalog(state.emptyMessage)
            }
        } else {
            items(state.modules, key = { it.moduleId.value }) { module ->
                ModuleCard(
                    module = module,
                    onSetEnabled = onSetEnabled,
                    onClearQuarantine = onClearQuarantine,
                )
            }
        }
    }
}

@Composable
private fun CatalogHeader(state: ModuleCatalogViewState, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.isEmpty) {
                Text(
                    "${state.modules.size} modules · ${state.issueCount} needing attention · ${state.updateCount} updates",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Refresh")
        }
    }
}

@Composable
private fun LocalOnlyBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyCatalog(message: String) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.Extension,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text("No modules to show", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModuleCard(
    module: CatalogModuleView,
    onSetEnabled: (ModuleId, Boolean) -> Unit,
    onClearQuarantine: (ModuleId) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                HealthIcon(module.healthTone)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(module.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(module.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = module.enabled,
                    onCheckedChange = { enabled -> onSetEnabled(module.moduleId, enabled) },
                    enabled = module.management.canToggle,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (module.isBuiltIn) LabelPill("Built in")
                if (module.isCritical) LabelPill("Required", warning = true)
                when (module.releaseChannel) {
                    CatalogReleaseChannel.STABLE -> Unit
                    CatalogReleaseChannel.BETA -> LabelPill("Beta", warning = true)
                    CatalogReleaseChannel.EXPERIMENTAL -> LabelPill("Experimental", warning = true)
                }
                LabelPill(module.stateLabel, warning = module.healthTone != CatalogHealthTone.HEALTHY)
            }

            module.management.toggleExplanation?.let { explanation ->
                Text(explanation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow("Version", module.version)
            DetailRow("Provider", module.providerName)
            DetailRow("Compatibility", module.compatibilityLabel)
            DetailRow("Restart", module.restartRequirementLabel)

            if (module.capabilities.isNotEmpty()) {
                DetailSection(
                    title = "Provides",
                    value = module.capabilities.joinToString { it.label },
                    icon = Icons.Rounded.BuildCircle,
                )
            }
            if (module.permissions.isNotEmpty()) {
                val permissions = module.permissions.joinToString { permission ->
                    permission.label + if (permission.required) " (required)" else " (optional)"
                }
                DetailSection("Permissions", permissions, Icons.Rounded.Security)
            }
            module.restartMessage?.let { message ->
                DetailSection("Recovery", message, Icons.Rounded.Refresh)
            }

            module.diagnostics.forEach { diagnostic ->
                DiagnosticCard(
                    severity = diagnostic.severity,
                    title = diagnostic.title,
                    explanation = diagnostic.explanation,
                    suggestedAction = diagnostic.suggestedAction,
                )
            }

            if (module.management.canClearQuarantine) {
                Button(onClick = { onClearQuarantine(module.moduleId) }) {
                    Text("Clear quarantine after review")
                }
            }
        }
    }
}

@Composable
private fun HealthIcon(tone: CatalogHealthTone) {
    val (icon, tint) = when (tone) {
        CatalogHealthTone.HEALTHY -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.secondary
        CatalogHealthTone.ATTENTION -> Icons.Rounded.Info to MaterialTheme.colorScheme.tertiary
        CatalogHealthTone.UNAVAILABLE -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        CatalogHealthTone.INACTIVE -> Icons.Rounded.Extension to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.wrapContentWidth())
    }
}

@Composable
private fun DetailSection(title: String, value: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticCard(
    severity: ModuleDiagnosticSeverity,
    title: String,
    explanation: String,
    suggestedAction: String?,
) {
    val container = when (severity) {
        ModuleDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.secondaryContainer
        ModuleDiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        ModuleDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (severity) {
        ModuleDiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
        ModuleDiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        ModuleDiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = content)
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = content)
        suggestedAction?.let { action ->
            Spacer(Modifier.height(4.dp))
            Text(action, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@Composable
private fun LabelPill(text: String, warning: Boolean = false) {
    val color: Color = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color, RoundedCornerShape(100.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
