from __future__ import annotations

from collections.abc import Iterable

from ..actions.router import ALLOWED_TOOLS
from .models import (
    CapabilityDescriptor,
    CapabilityDiscoveryResponse,
    CapabilityHealth,
    CapabilityHealthState,
    CapabilityKind,
    SafetyMetadata,
)


OBSERVATION_CAPABILITIES = {"phone.observe", "phone.find", "phone.wait_for"}
SENSITIVE_PARAMETERS = {"phone.type": ("text", "value")}


class CapabilityRegistry:
    """Read-only descriptors for operations already owned by Android.

    This registry advertises the existing allowlist. It cannot add operations to the Android
    bridge and is not an authority or executor.
    """

    def __init__(self, capability_ids: Iterable[str] = ALLOWED_TOOLS):
        ids = tuple(sorted(set(capability_ids)))
        unknown = set(ids) - ALLOWED_TOOLS
        if unknown:
            raise ValueError(f"Registry cannot invent Android capabilities: {sorted(unknown)}")
        self._capability_ids = ids

    def descriptor(
        self,
        capability_id: str,
        health: CapabilityHealth,
    ) -> CapabilityDescriptor | None:
        if capability_id not in self._capability_ids:
            return None
        mutates = capability_id not in OBSERVATION_CAPABILITIES
        return CapabilityDescriptor(
            capability_id=capability_id,
            version="1.0.0",
            kind=CapabilityKind.ACTION if mutates else CapabilityKind.OBSERVATION,
            request_schema="cyclone.gateway.action.request.v1",
            response_schema="cyclone.gateway.action.response.v1",
            safety=SafetyMetadata(
                mutates_phone=mutates,
                requires_fresh_observation=mutates,
                requires_android_policy=mutates,
                sensitive_parameter_names=SENSITIVE_PARAMETERS.get(capability_id, ()),
            ),
            health=health,
        )

    def discover(self, bridge) -> CapabilityDiscoveryResponse:
        health = self._bridge_health(bridge)
        return CapabilityDiscoveryResponse(
            gateway_health=health,
            capabilities=tuple(
                self.descriptor(capability_id, health)
                for capability_id in self._capability_ids
            ),
        )

    @staticmethod
    def _bridge_health(bridge) -> CapabilityHealth:
        try:
            status = bridge.request("bridge.status", {})
        except Exception:
            return CapabilityHealth(
                state=CapabilityHealthState.UNAVAILABLE,
                reason_code="DEVICE_DISCONNECTED",
            )
        if not isinstance(status, dict):
            return CapabilityHealth(
                state=CapabilityHealthState.UNAVAILABLE,
                reason_code="PROTOCOL_MISMATCH",
            )
        readiness_fields = ("gatewayEnabled", "socketListening", "accessibilityConnected")
        if any(not isinstance(status.get(field), bool) for field in readiness_fields):
            return CapabilityHealth(
                state=CapabilityHealthState.UNAVAILABLE,
                reason_code="PROTOCOL_MISMATCH",
            )
        ready = bool(
            status["gatewayEnabled"]
            and status["socketListening"]
            and status["accessibilityConnected"]
        )
        return CapabilityHealth(
            state=(
                CapabilityHealthState.AVAILABLE
                if ready
                else CapabilityHealthState.DEGRADED
            ),
            reason_code=None if ready else "ANDROID_NOT_READY",
        )
