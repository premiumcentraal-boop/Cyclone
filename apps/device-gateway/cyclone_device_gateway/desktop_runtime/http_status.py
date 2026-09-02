from __future__ import annotations

from .models import RuntimeErrorCode

# HTTP status for DesktopRuntimeError raised from /v1/devices/{id}/agent/*.
# STALE_OBSERVATION and POLICY_DENIED were added in 3.8.2 so fail-closed
# observation/policy errors are not collapsed to generic 503.
DESKTOP_HTTP_STATUS = {
    RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404,
    RuntimeErrorCode.DEVICE_DISCONNECTED.value: 503,
    RuntimeErrorCode.DEVICE_UNAUTHORIZED.value: 409,
    RuntimeErrorCode.DEVICE_NOT_READY.value: 409,
    RuntimeErrorCode.PAIRING_REQUIRED.value: 401,
    RuntimeErrorCode.PAIRING_EXPIRED.value: 409,
    RuntimeErrorCode.PAIRING_REPLAY.value: 409,
    RuntimeErrorCode.PAIRING_CODE_REJECTED.value: 403,
    RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED.value: 429,
    RuntimeErrorCode.PAIRING_SESSION_MISMATCH.value: 409,
    RuntimeErrorCode.TRUST_CONFIRMATION_REQUIRED.value: 409,
    RuntimeErrorCode.TRUST_REVOKED.value: 403,
    RuntimeErrorCode.TRUST_EXPIRED.value: 401,
    RuntimeErrorCode.TRUST_AUTH_FAILED.value: 403,
    RuntimeErrorCode.PROTOCOL_MISMATCH.value: 426,
    RuntimeErrorCode.PHONE_LOCKED.value: 423,
    RuntimeErrorCode.AUTH_REJECTED.value: 403,
    RuntimeErrorCode.INVALID_REQUEST.value: 400,
    RuntimeErrorCode.STALE_OBSERVATION.value: 409,
    RuntimeErrorCode.POLICY_DENIED.value: 403,
    RuntimeErrorCode.STREAM_CAPACITY.value: 503,
    RuntimeErrorCode.CAPABILITY_UNAVAILABLE.value: 503,
}


def desktop_http_status(code: str) -> int:
    return DESKTOP_HTTP_STATUS.get(str(code), 503)
