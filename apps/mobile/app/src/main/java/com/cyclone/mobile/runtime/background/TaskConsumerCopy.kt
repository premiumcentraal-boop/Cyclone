package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.ui.overlay.GlassStepKind

/** Consumer wording only. The executor's original message and identity remain on the task. */
object TaskConsumerCopy {
    fun subtitle(task: WorkspaceTaskUi): String {
        if (!task.working) return task.message
        val app = task.app.ifBlank { "your app" }
        return when (task.glassStepKind) {
            GlassStepKind.SKILL -> "Using your $app routine"
            GlassStepKind.LAYER2_SLICE -> {
                val label = task.message.removePrefix("Layer 2 slice · ").substringBefore(" · ").takeIf { it != "Layer 2 slice" && it.isNotBlank() }
                if (label != null) "Working in $label" else "Working in $app"
            }
            GlassStepKind.FAST_PATH -> when {
                task.message.contains("checking the result", true) -> "Checking the result in $app"
                task.message.contains("checking the page", true) -> "Reading $app"
                else -> "Navigating $app"
            }
            null -> task.message
        }
    }
}
