package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.ApiKeySettingsOperation
import com.cyclone.mobile.ai.OpenRouterSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared by Settings and the legacy Model & API route. Drafts never enter saved state. */
@Composable
internal fun CycloneApiKeyEditor(context: Context, onChanged: () -> Unit = {}) {
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (hasKey) "API key secured" else "Add your OpenRouter key", style = MaterialTheme.typography.titleSmall)
        Text("Protected by Android Keystore", style = MaterialTheme.typography.bodySmall)
        if (!hasKey) OutlinedTextField(
            value = draft, onValueChange = { draft = it; error = null },
            modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
            label = { Text("OpenRouter API key") },
            visualTransformation = PasswordVisualTransformation(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(enabled = !busy && (hasKey || draft.isNotBlank()), onClick = {
            busy = true
            val removing = hasKey
            val submitted = draft
            scope.launch {
                try {
                    error = withContext(Dispatchers.IO) {
                        if (removing) ApiKeySettingsOperation.remove { OpenRouterSecretStore.clear(context) }
                        else ApiKeySettingsOperation.save(submitted,
                            { OpenRouterSecretStore.save(context, it) }, { OpenRouterSecretStore.read(context) })
                    }
                    if (error == null) {
                        draft = ""
                        hasKey = !removing
                        onChanged()
                    }
                } finally { busy = false }
            }
        }) { Text(if (busy) "Saving…" else if (hasKey) "Remove" else "Secure key") }
    }
}
