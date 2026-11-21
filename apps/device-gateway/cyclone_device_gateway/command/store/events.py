"""The ledger's event types (plan 76, stage 1). Every event's payload is checked against its type before it is stored.

A type lists its fields and their kinds; unknown fields are refused, every string is bounded, and no string may look like
a secret (the Command Center's ``INLINE_SECRET`` rule). Two families:
- ``audit.recorded``: every owner, engine or phone action the audit chain records, in the ledger as well.
- ``<kind>.changed``: the state of one account, task, run, approval or phone after a change, as a snapshot. Replaying the
  snapshots in order gives the current state exactly (the projections).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from ...desktop_runtime.v5_contract import INLINE_SECRET


class EventError(ValueError):
    """An event that does not fit its type. Nothing was stored."""


@dataclass(frozen=True)
class Field:
    kind: str                      # text | int | number | bool | enum | id | scalars
    max: int = 120                 # text length, or entries for scalars
    values: tuple[str, ...] = ()   # enum values
    optional: bool = True


ID = re.compile(r"^[A-Za-z0-9_.:@-]{1,180}$")
ACTION = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+){0,4}$")
KEY = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,40}$")
MAX_PAYLOAD = 8_000


def safe_text(value: Any, limit: int) -> str | None:
    """A free text made safe to keep: bounded, and withheld when it looks like a secret. None stays None."""
    if value is None:
        return None
    text = str(value)[:limit]
    return "[withheld: looked like a secret]" if INLINE_SECRET.search(text) else text


@dataclass(frozen=True)
class EventType:
    name: str
    version: int
    fields: dict[str, Field]
    state_kind: str | None = None   # the projection kind a .changed event keeps

    def validate(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise EventError(f"{self.name}: the payload is an object.")
        extra = set(payload) - set(self.fields)
        if extra:
            raise EventError(f"{self.name}: unknown field {sorted(extra)[0]!r}.")
        out: dict[str, Any] = {}
        for name, spec in self.fields.items():
            if name not in payload or payload[name] is None:
                if not spec.optional:
                    raise EventError(f"{self.name}: {name} is required.")
                out[name] = None
                continue
            out[name] = _check(self.name, name, spec, payload[name])
        return out


def _check(event: str, name: str, spec: Field, value: Any) -> Any:
    where = f"{event}.{name}"
    if spec.kind == "text":
        if not isinstance(value, str) or len(value) > spec.max:
            raise EventError(f"{where} is text up to {spec.max} characters.")
        if INLINE_SECRET.search(value):
            raise EventError(f"{where} looks like it holds a secret.")
        return value
    if spec.kind == "id":
        if not isinstance(value, str) or not ID.match(value):
            raise EventError(f"{where} is an id.")
        return value
    if spec.kind == "enum":
        if value not in spec.values:
            raise EventError(f"{where} is one of {', '.join(spec.values)}.")
        return value
    if spec.kind == "bool":
        if type(value) is not bool:
            raise EventError(f"{where} is true or false.")
        return value
    if spec.kind == "int":
        if type(value) is not int:
            raise EventError(f"{where} is a whole number.")
        return value
    if spec.kind == "number":
        if type(value) not in (int, float):
            raise EventError(f"{where} is a number.")
        return value
    if spec.kind == "scalars":
        if not isinstance(value, dict) or len(value) > spec.max:
            raise EventError(f"{where} is an object of at most {spec.max} entries.")
        for key, item in value.items():
            if not isinstance(key, str) or not KEY.match(key):
                raise EventError(f"{where} has a malformed key.")
            items = item if isinstance(item, list) else [item]
            if len(items) > 50:
                raise EventError(f"{where}.{key} lists at most 50 values.")
            for v in items:
                if v is not None and type(v) not in (str, int, float, bool):
                    raise EventError(f"{where}.{key} holds plain values only.")
                if isinstance(v, str) and (len(v) > 300 or INLINE_SECRET.search(v)):
                    raise EventError(f"{where}.{key} is too long or looks like a secret.")
        return value
    raise EventError(f"{where} has an unknown kind.")


TYPES: dict[str, EventType] = {t.name: t for t in (
    EventType("audit.recorded", 1, {"action": Field("text", 80, optional=False), "detail": Field("scalars", 24)}),
    EventType("account.changed", 1, {
        "service": Field("text", 160), "handle": Field("text", 80), "ownerBasis": Field("text", 20), "twofa": Field("text", 20),
        "status": Field("text", 20), "deleted": Field("bool", optional=False)}, state_kind="account"),
    EventType("task.changed", 1, {
        "status": Field("text", 30), "cause": Field("text", 300), "deviceId": Field("id"), "accountId": Field("id"),
        "routineId": Field("id"), "recipe": Field("text", 64), "deleted": Field("bool", optional=False)}, state_kind="task"),
    EventType("run.changed", 1, {
        "taskId": Field("id"), "deviceId": Field("id"), "status": Field("text", 30), "turns": Field("int"),
        "workingMs": Field("int"), "costUsd": Field("number"), "ended": Field("bool"), "causeKind": Field("text", 30),
        "deleted": Field("bool", optional=False)}, state_kind="run"),
    EventType("approval.changed", 1, {
        "taskId": Field("id"), "runId": Field("text", 80), "deviceId": Field("text", 180), "kind": Field("text", 30),
        "state": Field("text", 30), "answer": Field("text", 30), "deleted": Field("bool", optional=False)}, state_kind="approval"),
    EventType("phone.changed", 1, {
        "name": Field("text", 60), "state": Field("text", 30), "quarantined": Field("bool"), "infraStreak": Field("int"),
        "batteryBucket": Field("int"), "charging": Field("bool"), "thermal": Field("text", 20), "network": Field("text", 20),
        "appVersion": Field("text", 40), "freeStorageGb": Field("int"), "deleted": Field("bool", optional=False)},
        state_kind="phone"),
)}
STATE_TYPES = {t.state_kind: t for t in TYPES.values() if t.state_kind}
