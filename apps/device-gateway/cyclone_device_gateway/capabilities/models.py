from __future__ import annotations

from enum import StrEnum
import re
from typing import Any, Literal
import uuid

from pydantic import BaseModel, ConfigDict, Field, field_validator


CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
HUMAN_GESTURE_CONTROL_VERSION = "cyclone.human_gesture.control.v1"
HUMAN_GESTURE_TRACE_VERSION = "cyclone.human_gesture.trace.v1"
HUMANIZE_PROFILES = frozenset({"auto", "off", "light", "normal"})
HUMANIZE_ACTIONS = frozenset({"phone.click", "phone.swipe", "phone.scroll"})
RAW_TRAJECTORY_KEYS = frozenset({"control1", "control2", "controlPoints", "bezier", "path", "points", "samples", "trajectory", "strokes"})
SAFE_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")


class CapabilityKind(StrEnum):
    OBSERVATION = "OBSERVATION"
    ACTION = "ACTION"
    HEALTH = "HEALTH"


class CapabilityHealthState(StrEnum):
    AVAILABLE = "AVAILABLE"
    DEGRADED = "DEGRADED"
    UNAVAILABLE = "UNAVAILABLE"


class FailureLayer(StrEnum):
    CAPABILITY = "CAPABILITY"
    TRANSPORT = "TRANSPORT"
    POLICY = "POLICY"
    EXECUTION = "EXECUTION"
    VERIFICATION = "VERIFICATION"
    PROTOCOL = "PROTOCOL"


class GatewayErrorCode(StrEnum):
    CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
    STALE_OBSERVATION = "STALE_OBSERVATION"
    POLICY_DENIED = "POLICY_DENIED"
    EXECUTION_FAILED = "EXECUTION_FAILED"
    VERIFICATION_FAILED = "VERIFICATION_FAILED"
    DEVICE_DISCONNECTED = "DEVICE_DISCONNECTED"
    PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH"
    AUTH_REJECTED = "AUTH_REJECTED"


class GatewayError(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    code: GatewayErrorCode
    layer: FailureLayer
    message: str
    retryable: bool = False


class CapabilityHealth(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    state: CapabilityHealthState
    reason_code: str | None = None


class SafetyMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    mutates_phone: bool
    requires_fresh_observation: bool
    requires_android_policy: bool
    sensitive_parameter_names: tuple[str, ...] = ()
    authoritative_executor: Literal["CYCLONE_ANDROID_PHONE_TOOL_EXECUTOR"] = "CYCLONE_ANDROID_PHONE_TOOL_EXECUTOR"
    generic_shell_allowed: Literal[False] = False


class CapabilityDescriptor(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    capability_id: str
    version: str
    kind: CapabilityKind
    request_schema: str
    response_schema: str
    safety: SafetyMetadata
    health: CapabilityHealth


class Witness(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    observation_id: str
    gateway_record_id: str
    page_key: str | None = None
    package: str | None = None
    accessibility_fingerprint: str | None = None


class LayerOutcome(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    ok: bool
    authoritative: bool = False
    status: str
    error: GatewayError | None = None


def _validate_human_gesture_params(capability_id: str, params: dict[str, Any]) -> dict[str, Any]:
    humanize = params.get("humanize")
    if humanize is not None:
        if capability_id not in HUMANIZE_ACTIONS:
            raise ValueError("humanize is only valid for typed click, swipe, or scroll actions")
        if not isinstance(humanize, str) or humanize not in HUMANIZE_PROFILES:
            raise ValueError("humanize must be one of auto, off, light, normal")
    forbidden = RAW_TRAJECTORY_KEYS.intersection(params)
    if forbidden:
        raise ValueError("PC-authored raw gesture trajectories are not permitted")
    return params


class CapabilityActionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol_version: str = CAPABILITY_PROTOCOL_VERSION
    correlation_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    capability_id: str
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = ""
    expected_observation_id: str | None = None
    source: Literal["PC_CODEX"] = "PC_CODEX"
    session_id: str | None = None
    sessionId: str | None = None
    display_id: int | None = Field(default=None, ge=0)
    displayId: int | None = Field(default=None, ge=0)
    executionContext: dict[str, Any] | None = None

    @field_validator("correlation_id", "capability_id")
    @classmethod
    def non_blank(cls, value: str) -> str:
        if not SAFE_IDENTIFIER.fullmatch(value):
            raise ValueError("must be a bounded safe identifier")
        return value

    def model_post_init(self, __context: Any) -> None:
        _validate_human_gesture_params(self.capability_id, self.params)


class CapabilityActionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol_version: Literal["cyclone.gateway.capability.v1"] = CAPABILITY_PROTOCOL_VERSION
    correlation_id: str
    capability_id: str
    ok: bool
    transport: LayerOutcome
    execution: LayerOutcome
    verification: LayerOutcome
    before: Witness | None = None
    after: Witness | None = None
    android_execution: dict[str, Any] | None = None
    safety: SafetyMetadata
    latency_ms: int = Field(ge=0)
    transition_id: str | None = None
    error: GatewayError | None = None


class HumanGestureDiscovery(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)
    control_version: Literal["cyclone.human_gesture.control.v1"] = HUMAN_GESTURE_CONTROL_VERSION
    trace_version: Literal["cyclone.human_gesture.trace.v1"] = HUMAN_GESTURE_TRACE_VERSION
    transport_schema_ready: Literal[True] = True
    runtime_available: Literal[False] = False
    profiles: tuple[Literal["auto", "off", "light", "normal"], ...] = ("auto", "off", "light", "normal")
    actions: dict[str, str] = Field(default_factory=lambda: {
        "phone.click": "schema_ready_runtime_unverified",
        "phone.scroll": "schema_ready_runtime_unverified",
        "phone.swipe": "schema_ready_runtime_unverified",
        "phone.drag": "unsupported",
    })
    execution_planes: dict[str, str] = Field(default_factory=lambda: {
        "foreground": "runtime_unverified",
        "session_kernel_vd": "runtime_unverified",
        "layer2_workspace": "runtime_unverified",
    })


class CapabilityDiscoveryResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol_version: Literal["cyclone.gateway.capability.v1"] = CAPABILITY_PROTOCOL_VERSION
    gateway_health: CapabilityHealth
    capabilities: tuple[CapabilityDescriptor, ...]
    human_gesture: HumanGestureDiscovery = Field(default_factory=HumanGestureDiscovery)


class CapabilityObserveRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol_version: str = CAPABILITY_PROTOCOL_VERSION
    correlation_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    include_screenshot: bool = False
    mode: Literal["compact", "full"] = "compact"
    goal: str | None = None
    session_id: str | None = None
    sessionId: str | None = None
    display_id: int | None = Field(default=None, ge=0)
    displayId: int | None = Field(default=None, ge=0)
    executionContext: dict[str, Any] | None = None

    @field_validator("correlation_id")
    @classmethod
    def safe_correlation_id(cls, value: str) -> str:
        if not SAFE_IDENTIFIER.fullmatch(value):
            raise ValueError("must be a bounded safe identifier")
        return value


class CapabilityObservationResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol_version: Literal["cyclone.gateway.capability.v1"] = CAPABILITY_PROTOCOL_VERSION
    correlation_id: str
    capability_id: Literal["phone.observe"] = "phone.observe"
    ok: bool
    transport: LayerOutcome
    witness: Witness | None = None
    observation: dict[str, Any] | None = None
    error: GatewayError | None = None
