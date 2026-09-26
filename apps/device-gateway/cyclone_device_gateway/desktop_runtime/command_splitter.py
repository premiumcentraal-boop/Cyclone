"""Single-command splitter for multi-device dispatch.

This is the "one command box" piece: you type or say one sentence to the
root device ("unlock and check messages on Work Phone, open camera and take
a photo on Tablet"), and this turns it into one goal per device.

Deliberately conservative about ambiguity. If the sentence names a device
that isn't in the fleet, or splits into an assignment for a device the
caller didn't tell us about, this refuses to guess and asks for
clarification instead - the same "explicit device required, never guess"
rule already enforced at the MCP layer (see
test_multi_device_ambiguity_is_explicit_and_safe in the MCP test suite).
This module does not itself dispatch anything; the caller (the API layer)
is responsible for actually starting tasks with the returned assignments.
"""
from __future__ import annotations

import json
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Protocol


class SplitterError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class DeviceRef:
    device_id: str
    nickname: str | None
    name: str | None

    def label(self) -> str:
        return self.nickname or self.name or self.device_id


@dataclass
class DeviceAssignment:
    device_id: str
    goal: str


@dataclass
class SplitResult:
    assignments: list[DeviceAssignment]
    clarification: str | None  # non-None means: do not dispatch anything yet, ask the human this

    def public(self) -> dict[str, Any]:
        return {
            "assignments": [{"deviceId": a.device_id, "goal": a.goal} for a in self.assignments],
            "clarification": self.clarification,
        }


class CommandSplitterModel(Protocol):
    def split(self, command: str, devices: list[DeviceRef]) -> dict[str, Any]: ...


_SYSTEM_PROMPT = (
    "You turn one instruction from a person into a separate goal for each device they "
    "mentioned. You are given the instruction and the exact list of devices that exist, "
    "each with an id and a human-readable label. "
    "Reply with EXACTLY one JSON object, nothing else: "
    '{"assignments": [{"deviceId": "<must be one of the given ids>", "goal": "<what that device should do>"}], '
    '"clarification": null} '
    "If the instruction names a device that is not in the given list, or you cannot tell which "
    "given device a part of the instruction refers to, do NOT guess: return an EMPTY assignments "
    "list and put a short question for the human in \"clarification\" instead, naming what's unclear. "
    "Only ever use deviceId values from the exact list you were given - never invent one."
)


def _device_catalog_text(devices: list[DeviceRef]) -> str:
    return json.dumps([
        {"deviceId": d.device_id, "label": d.label()} for d in devices
    ], ensure_ascii=False)


def _parse_split_response(raw: str, valid_device_ids: set[str]) -> SplitResult:
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        raise SplitterError("MALFORMED_SPLIT", f"Splitter did not return valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise SplitterError("MALFORMED_SPLIT", "Splitter response must be a JSON object.")

    clarification = data.get("clarification")
    clarification = str(clarification).strip() if clarification else None

    raw_assignments = data.get("assignments")
    if not isinstance(raw_assignments, list):
        raise SplitterError("MALFORMED_SPLIT", "Splitter response must include an assignments list.")

    assignments: list[DeviceAssignment] = []
    seen_device_ids: set[str] = set()
    for item in raw_assignments:
        if not isinstance(item, dict):
            raise SplitterError("MALFORMED_SPLIT", "Each assignment must be a JSON object.")
        device_id = str(item.get("deviceId") or "")
        goal = str(item.get("goal") or "").strip()
        if device_id not in valid_device_ids:
            # The model invented or mis-copied a device id. This is exactly the
            # "don't guess" case - refuse the whole split rather than silently
            # dropping or misdirecting one device's instruction.
            return SplitResult(assignments=[], clarification=(
                f"I couldn't match part of that instruction to one of your actual devices "
                f"(got an unrecognized device reference). Could you rephrase which device that part is for?"
            ))
        if device_id in seen_device_ids:
            raise SplitterError("MALFORMED_SPLIT", f"Splitter assigned two goals to the same device: {device_id!r}")
        if not goal:
            raise SplitterError("MALFORMED_SPLIT", "Every assignment needs a non-empty goal.")
        seen_device_ids.add(device_id)
        assignments.append(DeviceAssignment(device_id=device_id, goal=goal))

    if not assignments and not clarification:
        raise SplitterError("MALFORMED_SPLIT", "Splitter returned no assignments and no clarification question.")

    return SplitResult(assignments=assignments, clarification=None if assignments else clarification)


class OpenRouterCommandSplitter:
    def __init__(
        self,
        api_key: str,
        model: str,
        providers: list[str],
        *,
        post: Callable[[str, dict[str, str], dict[str, Any]], dict[str, Any]] | None = None,
        timeout_s: float = 30.0,
    ):
        if not api_key:
            raise SplitterError("MISSING_KEY", "An OpenRouter API key is required.")
        if not providers:
            raise SplitterError("NO_PROVIDER", "At least one verified provider endpoint is required.")
        self._api_key = api_key
        self._model = model
        self._providers = list(providers)
        self._post = post or self._http_post
        self._timeout_s = timeout_s

    def _http_post(self, url: str, headers: dict[str, str], body: dict[str, Any]) -> dict[str, Any]:
        data = json.dumps(body).encode("utf-8")
        request = urllib.request.Request(url, data=data, headers=headers, method="POST")
        with urllib.request.urlopen(request, timeout=self._timeout_s) as response:  # noqa: S310
            return json.loads(response.read().decode("utf-8"))

    def split(self, command: str, devices: list[DeviceRef]) -> SplitResult:
        if not devices:
            raise SplitterError("NO_DEVICES", "No paired devices to split this command across.")
        messages = [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps({
                "instruction": command, "devices": json.loads(_device_catalog_text(devices)),
            }, ensure_ascii=False)},
        ]
        body = {
            "model": self._model, "messages": messages, "stream": False, "max_tokens": 800,
            "provider": {"only": self._providers, "sort": "latency", "allow_fallbacks": False},
        }
        headers = {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}
        response = self._post("https://openrouter.ai/api/v1/chat/completions", headers, body)
        try:
            content = response["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise SplitterError("PROVIDER_RESPONSE", f"Unexpected OpenRouter response shape: {exc}") from exc
        return _parse_split_response(content, {d.device_id for d in devices})


def split_command(model: CommandSplitterModel, command: str, devices: list[DeviceRef]) -> SplitResult:
    """Thin functional wrapper so callers (the API layer, tests) don't need to
    know whether they're holding an OpenRouterCommandSplitter or a fake."""
    command = command.strip()
    if not command:
        raise SplitterError("INVALID_REQUEST", "The command is empty.")
    result = model.split(command, devices)
    return result if isinstance(result, SplitResult) else _parse_split_response(
        json.dumps(result), {d.device_id for d in devices},
    )
