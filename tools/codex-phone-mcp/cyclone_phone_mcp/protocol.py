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
    typed = result.get("protocol_version") == CAPABILITY_PROTOCOL_VERSION or any(
        key in result for key in ("transport", "execution", "verification")
    )
    if typed:
        if result.get("protocol_version") != CAPABILITY_PROTOCOL_VERSION:
            return Failure("PROTOCOL_MISMATCH", "protocol")
        transport = result.get("transport")
        if not isinstance(transport, dict) or transport.get("ok") is not True:
            return _layer_failure(transport, "DEVICE_DISCONNECTED", "transport")
        execution = result.get("execution")
        if execution is not None:
            if not isinstance(execution, dict) or "ok" not in execution:
                return Failure("PROTOCOL_MISMATCH", "protocol")
            if execution.get("ok") is not True:
                return _layer_failure(execution, "EXECUTION_FAILED", "execution")
        verification = result.get("verification")
        if verification is not None:
            if not isinstance(verification, dict) or "ok" not in verification:
                return Failure("PROTOCOL_MISMATCH", "protocol")
            required = verification.get("status") != "not_required"
            if required and verification.get("ok") is not True:
                return _layer_failure(verification, "VERIFICATION_FAILED", "verification")
        if "capability_id" in result and result.get("capability_id") != "phone.observe":
            if not isinstance(execution, dict) or not isinstance(verification, dict):
                return Failure("PROTOCOL_MISMATCH", "protocol")
        error = result.get("error")
        if error not in (None, {}, ""):
            return _error_failure(error, "GATEWAY_ERROR", "gateway")
        if result.get("ok") is not True:
            return Failure("GATEWAY_REPORTED_FAILURE", "gateway")
        return None
    error = result.get("error")
    if error not in (None, {}, ""):
        return _error_failure(error, "GATEWAY_ERROR", "gateway")
    if result.get("success") is False or result.get("ok") is False:
        return Failure(str(result.get("error_class") or "LEGACY_FAILURE"), "legacy")
    action = result.get("action")
    if isinstance(action, dict) and (action.get("success") is False or action.get("ok") is False):
        return Failure("LEGACY_ACTION_FAILURE", "legacy")
    return None


def _layer_failure(layer: Any, default_code: str, default_layer: str) -> Failure:
    if isinstance(layer, dict):
        return _error_failure(layer.get("error"), default_code, default_layer)
    return Failure(default_code, default_layer)


def _error_failure(error: Any, default_code: str, default_layer: str) -> Failure:
    if isinstance(error, dict):
        return Failure(str(error.get("code") or default_code), str(error.get("layer") or default_layer))
    return Failure(default_code, default_layer)
