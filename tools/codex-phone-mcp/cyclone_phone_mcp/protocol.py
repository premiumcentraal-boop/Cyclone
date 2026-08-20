from __future__ import annotations

from dataclasses import dataclass
from typing import Any

CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"


@dataclass(frozen=True)
class Failure:
    code: str
    layer: str


def classify_failure(result: Any) -> Failure | None:
    """Canonical MCP success/failure decision for legacy and capability responses."""
    if not isinstance(result, dict):
        return None
    error = result.get("error")
    if error not in (None, {}, ""):
        if isinstance(error, dict):
            return Failure(str(error.get("code") or "GATEWAY_ERROR"), str(error.get("layer") or "gateway"))
        return Failure("GATEWAY_ERROR", "gateway")
    typed = result.get("protocol_version") == CAPABILITY_PROTOCOL_VERSION or any(
        key in result for key in ("transport", "execution", "verification")
    )
    if typed:
        if result.get("protocol_version") != CAPABILITY_PROTOCOL_VERSION:
            return Failure("PROTOCOL_MISMATCH", "protocol")
        if result.get("ok") is not True:
            return Failure("GATEWAY_REPORTED_FAILURE", "gateway")
        transport = result.get("transport")
        if not isinstance(transport, dict) or transport.get("ok") is not True:
            return Failure("DEVICE_DISCONNECTED", "transport")
        execution = result.get("execution")
        if execution is not None:
            if not isinstance(execution, dict) or "ok" not in execution:
                return Failure("PROTOCOL_MISMATCH", "protocol")
            if execution.get("ok") is not True:
                return Failure("EXECUTION_FAILED", "execution")
        verification = result.get("verification")
        if verification is not None:
            if not isinstance(verification, dict) or "ok" not in verification:
                return Failure("PROTOCOL_MISMATCH", "protocol")
            required = verification.get("status") != "not_required"
            if required and verification.get("ok") is not True:
                return Failure("VERIFICATION_FAILED", "verification")
        if "capability_id" in result and result.get("capability_id") != "phone.observe":
            if not isinstance(execution, dict) or not isinstance(verification, dict):
                return Failure("PROTOCOL_MISMATCH", "protocol")
        return None
    if result.get("success") is False or result.get("ok") is False:
        return Failure(str(result.get("error_class") or "LEGACY_FAILURE"), "legacy")
    action = result.get("action")
    if isinstance(action, dict) and (action.get("success") is False or action.get("ok") is False):
        return Failure("LEGACY_ACTION_FAILURE", "legacy")
    return None
