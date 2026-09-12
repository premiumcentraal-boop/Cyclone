package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selection is local and free: catalog requests never run a model completion. */
@Composable
internal fun CycloneOpenRouterCatalog(context: Context, onSelectionChanged: () -> Unit = {}) {
    val revision by OpenRouterCatalogStore.revision.collectAsState()
    val models = remember(revision) { OpenRouterCatalogStore.models(context) }
    val selected = remember(revision) { OpenRouterCatalogStore.selectedIds(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var keyGeneration by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Set<String>?>(null) }
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }

    LaunchedEffect(keyGeneration, refresh) {
        available = null
        error = null
        loading = hasKey
        if (!hasKey) return@LaunchedEffect
        try {
            val fetched = withContext(Dispatchers.IO) {
                val key = OpenRouterSecretStore.read(context)
                val result = OpenRouterCatalogClient().fetch(key)
                ensureActive()
                // A late response from a replaced key must never label the new account's access.
                if (key != OpenRouterSecretStore.read(context)) return@withContext null
                OpenRouterCatalogStore.saveCatalog(context, result.models)
                result
            }
            if (fetched != null) {
                available = fetched.availableIds
                error = fetched.warning?.let { "Catalog loaded; account availability could not be checked. $it" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ProviderFailure.sanitize(failure.message ?: "Could not load the catalog. Try refreshing.")
        } finally {
            loading = false
        }
    }

    val rows = remember(models, selected, query, onlySelected) {
        // Retain removed/unlisted selections so the user can still uncheck them.
        val known = models.map { it.id }.toSet()
        val missing = (selected - known).map { CatalogModel(it, it, false, true, 0, null) }
        OpenRouterCatalog.search(models + missing, query).filter { !onlySelected || it.id in selected }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CycloneApiKeyEditor(context) {
            hasKey = OpenRouterSecretStore.hasKey(context)
            available = null
            keyGeneration++
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Search OpenRouter models") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${models.size} models · ${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { refresh++ }, enabled = hasKey && !loading) { Text("Refresh") }
        }
        FilterChip(selected = onlySelected, onClick = { onlySelected = !onlySelected }, label = { Text("Selected only") })
        Text("Tap a model to add or remove it from your picker. A green check means selected.", style = MaterialTheme.typography.bodySmall)
        if (!hasKey) Text("Add your OpenRouter API key to load the full catalog.", style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (rows.isEmpty()) {
            Text(if (models.isEmpty()) "Your catalog will appear here." else "No matching models.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(rows, key = { it.id }) { model ->
                    val checked = model.id in selected
                    val listed = models.any { it.id == model.id }
                    val unavailable = available?.let { model.id !in it } == true
                    val canSelect = checked || (hasKey && listed && model.textOutput && !unavailable)
                    Row(
                        Modifier.fillMaxWidth().toggleable(checked, enabled = canSelect, role = Role.Checkbox) { value ->
                            try {
                                OpenRouterCatalogStore.select(context, model.id, value)
                                onSelectionChanged()
                            } catch (_: Exception) {
                                error = "Could not save your model selection. Please try again."
                            }
                        }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.bodyMedium)
                            Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when {
                                !listed -> "No longer in catalog · uncheck to remove"
                                !model.textOutput -> "Not a chat model"
                                unavailable -> "Unavailable under this key's account settings"
                                model.imageInput -> "Text + images"
                                else -> "Text only"
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null, modifier = Modifier.size(24.dp),
                            tint = if (checked) Color(0xFF2EAD62) else MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
