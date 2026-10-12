package com.cyclone.mobile.secrets

import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.places.PlaceResolver
import java.security.MessageDigest

/** Process-local field identity. No field value, coordinates or plaintext secret is retained. */
data class SecretFieldAnchor(
    val placeId: String,
    val scopeKey: String,
    val controllerEpoch: Long,
    val className: String,
    val resourceId: String,
    val descriptionDigest: String?,
    val password: Boolean,
    val formLabels: List<String>,
)

/** Rebind only the uniquely identified field on the original form, never an arbitrary focused input. */
internal object SecretTargetRecovery {
    fun anchor(snapshot: UiSnapshot, node: UiNodeSnapshot, scopeKey: String, epoch: Long): SecretFieldAnchor? {
        val place = PlaceResolver.resolveObservedSnapshot(snapshot) ?: return null
        if (!node.editable || !node.visibleToUser) return null
        val form = labels(snapshot, node.windowId).take(1)
        if (form.isEmpty()) return null // Keep the existing exact-observation path when form identity is unavailable.
        return SecretFieldAnchor(place.id, scopeKey, epoch, node.className, node.resourceId,
            digest(node.contentDescription), node.password, form)
    }

    fun resolve(anchor: SecretFieldAnchor, snapshot: UiSnapshot, scopeKey: String, epoch: Long): UiNodeSnapshot? {
        if (scopeKey != anchor.scopeKey || epoch != anchor.controllerEpoch ||
            PlaceResolver.resolveObservedSnapshot(snapshot)?.id != anchor.placeId) return null
        val candidates = snapshot.nodes.filter { node ->
            node.editable && node.enabled && node.visibleToUser && node.bounds.width > 0 && node.bounds.height > 0 &&
                node.className == anchor.className && node.password == anchor.password &&
                (anchor.resourceId.isBlank() || node.resourceId == anchor.resourceId) &&
                (anchor.descriptionDigest == null || digest(node.contentDescription) == anchor.descriptionDigest) &&
                // Require form context even when an app reuses the same generic input resource on every page.
                anchor.formLabels.isNotEmpty() && labels(snapshot, node.windowId).containsAll(anchor.formLabels)
        }
        return candidates.singleOrNull()
    }

    private fun labels(snapshot: UiSnapshot, windowId: Int): List<String> = snapshot.nodes.asSequence()
        .filter { it.windowId == windowId && it.visibleToUser && !it.editable && !it.password &&
            !it.clickable && !it.checkable && it.childIds.isEmpty() && it.text.isNotBlank() && it.text != "<redacted>" }
        .mapNotNull { digest(it.text) }.distinct().take(32).toList()

    private fun digest(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it != "<redacted>" }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }
}
