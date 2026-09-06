from __future__ import annotations

from enum import StrEnum
import re
from typing import Any, Literal
import uuid

from pydantic import BaseModel, ConfigDict, Field, field_validator


CAPABILITY_PROTOCOL_VERSION = "cyclone.gateway.capability.v1"
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
    authoritative_executor: Literal["CYCLONE_ANDROID_PHONE_TOOL_EXECUTOR"] = (
        "CYCLONE_ANDROID_PHONE_TOOL_EXECUTOR"
    )
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


class CapabilityActionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    protocol_version: str = CAPABILITY_PROTOCOL_VERSION
    correlation_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    capability_id: str
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = ""
    expected_observation_id: str | None = None
    source: Literal["PC_CODEX"] = "PC_CODEX"

    @field_validator("correlation_id", "capability_id")
    @classmethod
    def non_blank(cls, value: str) -> str:
        if not SAFE_IDENTIFIER.fullmatch(value):
            raise ValueError("must be a bounded safe identifier")
        return value


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


class CapabilityDiscoveryResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    protocol_version: Literal["cyclone.gateway.capability.v1"] = CAPABILITY_PROTOCOL_VERSION
    gateway_health: CapabilityHealth
    capabilities: tuple[CapabilityDescriptor, ...]


class CapabilityObserveRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    protocol_version: str = CAPABILITY_PROTOCOL_VERSION
    correlation_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    include_screenshot: bool = False
    mode: Literal["compact", "full"] = "compact"
    goal: str | None = None

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
