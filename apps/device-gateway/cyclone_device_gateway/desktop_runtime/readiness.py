from __future__ import annotations

from typing import Any

from .models import AITrustState, BridgeState, DiscoveryState, MediaState


def _video_diagnostics(session: Any) -> dict[str, Any]:
    controller = getattr(session, "video", None)
    if controller is None or not hasattr(controller, "diagnostics"):
        return {}
    try:
        value = controller.diagnostics()
    except Exception:
        return {}
    return value if isinstance(value, dict) else {}


def discovery_state(session: Any) -> DiscoveryState:
    state = str(getattr(getattr(session, "adb_device", None), "state", "") or "").lower()
    if state == "device":
        return DiscoveryState.ADB_READY
    if state == "unauthorized":
        return DiscoveryState.UNAUTHORIZED
    if state in {"offline", "recovery", "sideload", "bootloader"}:
        return DiscoveryState.OFFLINE
    return DiscoveryState.ABSENT


def media_state(session: Any, diagnostics: dict[str, Any] | None = None) -> MediaState:
    discovery = discovery_state(session)
    if discovery != DiscoveryState.ADB_READY:
        return MediaState.UNAVAILABLE if discovery == DiscoveryState.OFFLINE else MediaState.STOPPED
    controller = getattr(session, "video", None)
    if controller is None:
        return MediaState.UNAVAILABLE
    diagnostics = diagnostics if diagnostics is not None else _video_diagnostics(session)
    active = diagnostics.get("activeProfiles") or []
    frames = diagnostics.get("framesByProfile") or {}
    failures = diagnostics.get("failuresByProfile") or {}
    last_event = str(diagnostics.get("lastEvent") or "")
    if active:
        if not bool(getattr(session, "screen_awake", True)):
            return MediaState.SLEEPING
        if any(int(value or 0) > 0 for value in frames.values()):
            return MediaState.LIVE
        if any(int(value or 0) >= 3 for value in failures.values()) or "failed" in last_event:
            return MediaState.RECONNECTING
        if "keyframe" in last_event:
            return MediaState.WAITING_KEYFRAME
        return MediaState.STARTING
    return MediaState.STOPPED


def bridge_state(session: Any) -> BridgeState:
    error = str(getattr(session, "bridge_last_error", "") or "")
    error_class = str(getattr(session, "bridge_error_class", "") or "")
    lowered = error.lower()
    if "not installed" in lowered:
        return BridgeState.APP_MISSING
    if discovery_state(session) != DiscoveryState.ADB_READY:
        return BridgeState.APP_STOPPED
    if getattr(session, "bridge_ok", None) is True:
        return BridgeState.CONNECTED
    if "auth" in error_class.lower() or "auth" in lowered or "token" in lowered or "signature" in lowered:
        return BridgeState.AUTH_FAILED
    if getattr(session, "bridge_ok", None) is False:
        return BridgeState.DEGRADED
    return BridgeState.SOCKET_STARTING


def ai_trust_state(session: Any, bridge: BridgeState | None = None, trust_status: dict[str, Any] | None = None) -> AITrustState:
    if trust_status:
        explicit = str(trust_status.get("state") or "")
        mapping = {
            "UNPAIRED": AITrustState.UNPAIRED,
            "CONFIRMATION_REQUIRED": AITrustState.CONFIRMATION_REQUIRED,
            "TRUSTED": AITrustState.TRUSTED,
            "REVOKED": AITrustState.REVOKED,
            "EXPIRED": AITrustState.EXPIRED,
        }
        if explicit in mapping:
            return mapping[explicit]
    if getattr(session, "pending_pairing", None) is not None:
        return AITrustState.CONFIRMATION_REQUIRED
    if not getattr(session, "credential", None):
        return AITrustState.UNPAIRED
    bridge = bridge or bridge_state(session)
    if bridge == BridgeState.AUTH_FAILED:
        return AITrustState.EXPIRED
    return AITrustState.TRUSTED


def connection_label(
    discovery: DiscoveryState,
    media: MediaState,
    bridge: BridgeState,
    trust: AITrustState,
) -> str:
    if discovery == DiscoveryState.UNAUTHORIZED:
        return "Phone detected · USB debugging authorization needed"
    if discovery in {DiscoveryState.ABSENT, DiscoveryState.OFFLINE}:
        return "Phone disconnected · Waiting for USB recovery"
    if media == MediaState.LIVE and trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        return "Screen connected · Allow AI control on phone"
    if media == MediaState.LIVE and trust == AITrustState.TRUSTED and bridge in {BridgeState.DEGRADED, BridgeState.SOCKET_STARTING}:
        return "Screen live · AI bridge reconnecting"
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED and media == MediaState.UNAVAILABLE:
        return "AI ready · Live display unavailable"
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED and media == MediaState.LIVE:
        return "Screen live · AI/Codex ready"
    if trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED:
        return "Phone connected · AI/Codex ready"
    if trust == AITrustState.EXPIRED:
        return "Phone connected · Allow AI control again"
    if trust == AITrustState.REVOKED:
        return "Phone connected · AI access revoked"
    if bridge == BridgeState.APP_MISSING:
        return "Phone connected · Cyclone Mobile is not installed"
    if media in {MediaState.STARTING, MediaState.WAITING_KEYFRAME, MediaState.RECONNECTING}:
        return "Phone connected · Live display starting"
    return "Phone connected · AI access not yet allowed"


def readiness_cards(
    discovery: DiscoveryState,
    media: MediaState,
    bridge: BridgeState,
    trust: AITrustState,
) -> dict[str, dict[str, str | bool]]:
    phone_ready = discovery == DiscoveryState.ADB_READY
    if phone_ready:
        phone = {"state": "READY", "ready": True, "message": "USB connection ready"}
    elif discovery == DiscoveryState.UNAUTHORIZED:
        phone = {"state": "ACTION_REQUIRED", "ready": False, "message": "Approve USB debugging on the phone"}
    else:
        phone = {"state": "OFFLINE", "ready": False, "message": "Reconnect the phone by USB"}

    if media == MediaState.LIVE:
        display = {"state": "READY", "ready": True, "message": "Live display ready"}
    elif media == MediaState.STOPPED:
        display = {"state": "IDLE", "ready": False, "message": "Open the phone to start live display"}
    elif media in {MediaState.STARTING, MediaState.WAITING_KEYFRAME, MediaState.RECONNECTING}:
        display = {"state": "RECOVERING", "ready": False, "message": "Live display is reconnecting"}
    elif media == MediaState.SLEEPING:
        display = {"state": "SLEEPING", "ready": False, "message": "Phone screen is sleeping"}
    else:
        display = {"state": "LIMITED", "ready": False, "message": "Live display is unavailable; AI can remain usable"}

    ai_ready = trust == AITrustState.TRUSTED and bridge == BridgeState.CONNECTED
    if ai_ready:
        ai = {"state": "READY", "ready": True, "message": "AI/Codex access ready"}
    elif trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        ai = {"state": "ACTION_REQUIRED", "ready": False, "message": "Allow this PC on the phone"}
    elif trust in {AITrustState.EXPIRED, AITrustState.REVOKED} or bridge == BridgeState.AUTH_FAILED:
        ai = {"state": "ACTION_REQUIRED", "ready": False, "message": "AI trust must be restored"}
    elif trust == AITrustState.TRUSTED:
        ai = {"state": "RECOVERING", "ready": False, "message": "AI bridge is reconnecting"}
    else:
        ai = {"state": "LIMITED", "ready": False, "message": "AI access is unavailable"}
    return {"phoneConnection": phone, "liveDisplay": display, "aiCodexAccess": ai}


def enrich_device_public(session: Any, trust_status: dict[str, Any] | None = None) -> dict[str, Any]:
    """Return the V3.3 public device shape without leaking serials, credentials or frame bytes.

    The legacy aggregate ``state`` is kept for existing UI compatibility, but bridge/media failures
    no longer force the entire phone into ``ATTENTION``. Consumers should prefer ``planes`` and
    ``readiness``.
    """
    public = dict(session.public())
    diagnostics = _video_diagnostics(session)
    discovery = discovery_state(session)
    media = media_state(session, diagnostics)
    bridge = bridge_state(session)
    trust = ai_trust_state(session, bridge, trust_status)

    if discovery == DiscoveryState.UNAUTHORIZED:
        legacy_state = "UNAUTHORIZED"
    elif discovery != DiscoveryState.ADB_READY:
        legacy_state = "DISCONNECTED"
    elif trust in {AITrustState.UNPAIRED, AITrustState.CONFIRMATION_REQUIRED}:
        legacy_state = "PAIRING" if trust == AITrustState.CONFIRMATION_REQUIRED else "UNPAIRED"
    elif not bool(getattr(session, "screen_awake", True)):
        legacy_state = "SLEEPING"
    else:
        legacy_state = "READY"

    public["state"] = legacy_state
    public["paired"] = trust == AITrustState.TRUSTED
    public["connectionLabel"] = connection_label(discovery, media, bridge, trust)
    public["planes"] = {
        "discovery": discovery.value,
        "media": media.value,
        "bridge": bridge.value,
        "aiTrust": trust.value,
    }
    public["readiness"] = readiness_cards(discovery, media, bridge, trust)
    public["videoDiagnostics"] = {
        "subscriberCount": int(diagnostics.get("subscriberCount") or 0),
        "activeProfiles": list(diagnostics.get("activeProfiles") or []),
        "lastEvent": str(diagnostics.get("lastEvent") or "idle"),
        "lastFrameAvailable": bool(diagnostics.get("lastFrameAvailable")),
    }
    return public
