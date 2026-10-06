package com.cyclone.mobile.secrets

import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneTypeEngine
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.uiSnapshotFromJson

/** Freeze safe intent before showing the card; the card itself can invalidate the observation store. */
internal object SecretTargetBinding {
    fun bind(target: SecretFillTarget): SecretFillTarget {
        if (target.anchor != null) return target
        val scope = ExecutionContext(target.sessionId, target.displayId)
        val observation = GatewayObservationStore.current(scope)?.takeIf { it.id == target.observationId } ?: return target
        val raw = observation.payload.optJSONObject("rawAccessibility") ?: return target
        val snapshot = uiSnapshotFromJson(raw)
        val catalog = PhoneTypeEngine.catalog(observation.id, observation.elements.values.map {
            PhoneTypeEngine.ObservationElementInput(it.id, it.source, it.role, it.evidence)
        }, snapshot)
        val element = catalog.elements[target.elementId] ?: return target
        val node = snapshot.nodes.firstOrNull { it.id == element.rawNodeId } ?: return target
        val key = scopeKey(scope)
        val plane = observation.payload.optJSONObject("plane")
        if (scope.sessionId == "default-foreground") {
            val holder = Layer2Workspaces.engine.holder()
            if (holder?.workspaceId != plane?.optString("workspaceId")?.takeUnless { it.isBlank() || it == "null" } ||
                (holder != null && holder.generation != plane?.optLong("workspaceGeneration"))) return target
        } else if (observation.payload.optLong("executionGeneration", -1) !=
            com.cyclone.mobile.runtime.background.WorkspaceRuntime.generation(scope.sessionId)) return target
        return target.copy(anchor = SecretTargetRecovery.anchor(snapshot, node, key, epoch(scope)))
    }

    fun epoch(scope: ExecutionContext): Long = if (scope.sessionId == "default-foreground") DeviceState.controllerEpoch() else 0

    fun scopeKey(scope: ExecutionContext): String {
        if (scope.sessionId != "default-foreground") return "${scope.sessionId}:${scope.displayId}:" +
            com.cyclone.mobile.runtime.background.WorkspaceRuntime.generation(scope.sessionId)
        val engine = Layer2Workspaces.engine
        val holder = engine.holder()
        val profile = holder?.let { h -> engine.snapshot().singleOrNull { it.id == h.workspaceId }?.androidUserId }
        return "foreground:${holder?.workspaceId}:${holder?.generation}:$profile:${engine.selectedId()}"
    }
}
