from __future__ import annotations

from dataclasses import dataclass
import re
import secrets
import time

from ..cyclone_bridge.client import BridgeDisconnectedError, BridgeOperationError, BridgeProtocolError
from .fleet import DeviceFleetManager, DeviceSession
from .models import DesktopRuntimeError, DeviceFleetState, RuntimeErrorCode

_CODE_RE = re.compile(r"^[A-Z]{4}$")


@dataclass
class PairingChallenge:
    challenge_id: str
    pc_nonce: str
    usb_session_id: str
    expires_at_ms: int
    attempts: int = 0


class PairingCoordinator:
    MAX_ATTEMPTS = 5
    MAX_LIFETIME_MS = 60_000
    BEGIN_TRANSPORT_ATTEMPTS = 2
    BEGIN_RETRY_DELAY_SECONDS = 0.2

    def __init__(self, fleet: DeviceFleetManager):
        self.fleet = fleet

    def begin(self, device_id: str) -> dict:
        session = self._pairable(device_id)
        pc_nonce = secrets.token_urlsafe(32)
        response = None
        for attempt in range(self.BEGIN_TRANSPORT_ATTEMPTS):
            try:
                response = session.bridge(token="").request_unauthenticated(
                    "pair.begin",
                    {"usbSessionId": session.usb_session_id, "pcNonce": pc_nonce},
                    request_id=secrets.token_urlsafe(18),
                )
                break
            except BridgeOperationError as exc:
                self._map_bridge_error(exc)
            except BridgeProtocolError as exc:
                # A malformed response is not a transient USB race; do not duplicate requests.
                raise DesktopRuntimeError(
                    RuntimeErrorCode.DEVICE_DISCONNECTED,
                    "Cyclone pairing returned an invalid response.",
                    retryable=True,
                ) from exc
            except BridgeDisconnectedError as exc:
                if attempt + 1 >= self.BEGIN_TRANSPORT_ATTEMPTS:
                    raise DesktopRuntimeError(
                        RuntimeErrorCode.DEVICE_DISCONNECTED,
                        "Cyclone pairing transport is unavailable.",
                        retryable=True,
                    ) from exc
                # pair.begin only creates/replaces a short-lived challenge and carries no credential.
                # It is safe to repair the isolated ADB forward and retry once. pair.complete below is
                # intentionally never retried because it consumes pairing state and returns a credential.
                time.sleep(self.BEGIN_RETRY_DELAY_SECONDS)
                try:
                    session.adb.ensure_bridge_forward(session.local_port)
                except Exception:
                    pass

        if response is None:
            raise DesktopRuntimeError(
                RuntimeErrorCode.DEVICE_DISCONNECTED,
                "Cyclone pairing transport is unavailable.",
                retryable=True,
            )
        challenge_id = str(response.get("challengeId") or "")
        expires_at = int(response.get("expiresAtMs") or 0)
        now = int(time.time() * 1000)
        if not challenge_id or expires_at <= now or expires_at - now > self.MAX_LIFETIME_MS:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Phone returned an invalid pairing challenge.")
        pending = PairingChallenge(challenge_id, pc_nonce, session.usb_session_id, expires_at)
        self.fleet.set_pairing(session, pending)
        return {
            "deviceId": device_id,
            "pairingId": challenge_id,
            "pairing": True,
            "expiresAtMs": expires_at,
            "expiresAtEpochMs": expires_at,
            "attemptsRemaining": self.MAX_ATTEMPTS,
        }

    def complete(self, device_id: str, code: str) -> dict:
        session = self.fleet.get(device_id)
        pending = session.pending_pairing
        if pending is None:
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_REPLAY, "No active pairing challenge exists.")
        if not isinstance(pending, PairingChallenge):
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Pairing state is invalid.")
        now = int(time.time() * 1000)
        if pending.usb_session_id != session.usb_session_id:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_SESSION_MISMATCH, "USB session changed; begin pairing again.", retryable=True)
        if now > pending.expires_at_ms:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_EXPIRED, "Pairing code expired; begin pairing again.", retryable=True)
        code = code.strip().upper()
        if not _CODE_RE.fullmatch(code):
            self._failed_attempt(session, pending)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_CODE_REJECTED, "Pairing code must be exactly four uppercase letters.")
        try:
            response = session.bridge(token="").request_unauthenticated(
                "pair.complete",
                {
                    "challengeId": pending.challenge_id,
                    "usbSessionId": pending.usb_session_id,
                    "pcNonce": pending.pc_nonce,
                    "code": code,
                },
                request_id=secrets.token_urlsafe(18),
            )
        except BridgeOperationError as exc:
            if exc.code in {"PAIRING_CODE_REJECTED", "PAIRING_ATTEMPTS_EXCEEDED"}:
                self._failed_attempt(session, pending, force_exhausted=exc.code == "PAIRING_ATTEMPTS_EXCEEDED")
            self._map_bridge_error(exc)
        except (BridgeDisconnectedError, BridgeProtocolError) as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Cyclone pairing transport disconnected.", retryable=True) from exc
        credential = str(response.get("credential") or "")
        if len(credential) < 43:
            raise DesktopRuntimeError(RuntimeErrorCode.CAPABILITY_UNAVAILABLE, "Phone returned an invalid pairing credential.")
        self.fleet.remember_credential(session, credential)
        self.fleet.set_pairing(session, None)
        session.state = DeviceFleetState.READY if session.screen_awake else DeviceFleetState.SLEEPING
        return {"deviceId": device_id, "paired": True, "state": session.state.value, "device": session.public()}

    def revoke(self, device_id: str) -> dict:
        session = self.fleet.get(device_id)
        if session.credential:
            try:
                session.bridge().request("pair.revoke", {}, request_id=secrets.token_urlsafe(18))
            except BridgeOperationError as exc:
                self._map_bridge_error(exc)
            except (BridgeDisconnectedError, BridgeProtocolError) as exc:
                raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Could not revoke pairing while the phone is disconnected.", retryable=True) from exc
        self.fleet.remember_credential(session, None)
        self.fleet.set_pairing(session, None)
        session.state = DeviceFleetState.UNPAIRED
        return {"deviceId": device_id, "paired": False, "state": session.state.value}

    def _failed_attempt(self, session: DeviceSession, pending: PairingChallenge, *, force_exhausted: bool = False) -> None:
        pending.attempts += 1
        if force_exhausted or pending.attempts >= self.MAX_ATTEMPTS:
            self.fleet.set_pairing(session, None)
            raise DesktopRuntimeError(RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED, "Pairing attempt limit reached; begin again.")

    def _pairable(self, device_id: str) -> DeviceSession:
        session = self.fleet.get(device_id)
        if session.adb_device.state == "unauthorized":
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_UNAUTHORIZED, "Authorize USB debugging on the phone.")
        if session.adb_device.state != "device":
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_READY, "Phone is not ready for pairing.", retryable=True)
        try:
            session.adb.ensure_bridge_forward(session.local_port)
        except Exception as exc:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "Could not prepare the isolated USB bridge.", retryable=True) from exc
        return session

    @staticmethod
    def _map_bridge_error(exc: BridgeOperationError) -> None:
        mapping = {
            "AUTH_REJECTED": RuntimeErrorCode.AUTH_REJECTED,
            "PAIRING_EXPIRED": RuntimeErrorCode.PAIRING_EXPIRED,
            "PAIRING_REPLAY": RuntimeErrorCode.PAIRING_REPLAY,
            "PAIRING_CODE_REJECTED": RuntimeErrorCode.PAIRING_CODE_REJECTED,
            "PAIRING_ATTEMPTS_EXCEEDED": RuntimeErrorCode.PAIRING_ATTEMPTS_EXCEEDED,
            "PAIRING_SESSION_MISMATCH": RuntimeErrorCode.PAIRING_SESSION_MISMATCH,
            "CAPABILITY_UNAVAILABLE": RuntimeErrorCode.CAPABILITY_UNAVAILABLE,
        }
        error_code = mapping.get(exc.code, RuntimeErrorCode.CAPABILITY_UNAVAILABLE)
        raise DesktopRuntimeError(error_code, f"Phone rejected pairing with {error_code.value}.")
