"""Safety and schema guard for AI-generated Agent-2 workflow documents."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


ALLOWED_STEP_TYPES = frozenset(
    {
        "phone_tool",
        "wait",
        "condition",
        "branch",
        "repeat",
        "set_variable",
        "parse_text",
        "regex_extract",
        "delay",
        "assertion",
        "invoke_skill",
        "http_request",
        "cyclone_event",
        "request_human_takeover",
    }
)

CONSEQUENTIAL_PHONE_TOOLS = frozenset(
    {
        "phone.send_message",
        "phone.purchase",
        "phone.delete",
        "phone.submit",
        "phone.transfer",
    }
)

_SECRET_KEYS = frozenset(
    {"password", "passcode", "secret", "token", "api_key", "apikey", "credential"}
)


@dataclass(frozen=True)
class WorkflowValidation:
    valid: bool
    errors: tuple[str, ...]
    warnings: tuple[str, ...]
    required_permissions: tuple[str, ...]
    consequential_steps: tuple[int, ...]


class WorkflowValidator:
    """Validate untrusted model output before compiling it into Agent 2 types."""

    def validate(self, document: dict[str, Any]) -> WorkflowValidation:
        errors: list[str] = []
        warnings: list[str] = []
        required_permissions: set[str] = set()
        consequential: list[int] = []

        if not isinstance(document.get("name"), str) or not document["name"].strip():
            errors.append("Workflow requires a non-empty name.")
        trigger = document.get("trigger")
        if not isinstance(trigger, dict) or not trigger:
            errors.append("Workflow requires a typed trigger object.")

        steps = document.get("steps")
        if not isinstance(steps, list) or not steps:
            errors.append("Workflow requires at least one step.")
            steps = []

        for index, raw in enumerate(steps):
            if not isinstance(raw, dict):
                errors.append(f"Step {index} must be an object.")
                continue
            step_type = raw.get("type")
            if step_type not in ALLOWED_STEP_TYPES:
                errors.append(f"Step {index} has unsupported type: {step_type!r}.")
                continue

            if self._contains_literal_secret(raw):
                errors.append(
                    f"Step {index} appears to contain a raw credential; use SecretReference instead."
                )

            permission = raw.get("requiresPermission")
            if isinstance(permission, str) and permission.strip():
                required_permissions.add(permission.strip())

            if step_type == "phone_tool":
                tool = raw.get("tool")
                if not isinstance(tool, str) or not tool.startswith("phone."):
                    errors.append(f"Step {index} must name a phone.* tool.")
                    continue
                if tool in CONSEQUENTIAL_PHONE_TOOLS or bool(raw.get("consequential")):
                    consequential.append(index)
                    if raw.get("confirmation") != "required":
                        errors.append(
                            f"Step {index} is consequential and must set confirmation='required'."
                        )
                if tool == "phone.screenshot":
                    warnings.append(
                        f"Step {index} uses a screenshot; prefer UI-tree selectors when sufficient."
                    )

            if step_type == "request_human_takeover":
                if not isinstance(raw.get("resumeCondition"), dict):
                    errors.append(
                        f"Step {index} takeover must define a structured resumeCondition."
                    )

        return WorkflowValidation(
            valid=not errors,
            errors=tuple(errors),
            warnings=tuple(warnings),
            required_permissions=tuple(sorted(required_permissions)),
            consequential_steps=tuple(consequential),
        )

    def _contains_literal_secret(self, value: Any) -> bool:
        if isinstance(value, dict):
            for key, child in value.items():
                normalized = str(key).lower().replace("-", "_")
                if normalized in _SECRET_KEYS and child not in (None, "", "***"):
                    return True
                if self._contains_literal_secret(child):
                    return True
        elif isinstance(value, list):
            return any(self._contains_literal_secret(child) for child in value)
        return False


def compile_workflow_candidate(
    raw: dict[str, Any],
    *,
    validator: WorkflowValidator | None = None,
) -> dict[str, Any]:
    """Return a normalized copy only after validation; never execute raw model text."""

    validator = validator or WorkflowValidator()
    validation = validator.validate(raw)
    if not validation.valid:
        raise ValueError("; ".join(validation.errors))
    return {
        "name": raw["name"].strip(),
        "trigger": dict(raw["trigger"]),
        "conditions": list(raw.get("conditions", [])),
        "steps": [dict(step) for step in raw["steps"]],
        "verification": dict(raw.get("verification", {})),
        "recovery": dict(raw.get("recovery", {})),
        "metadata": {
            **dict(raw.get("metadata", {})),
            "generatedBy": "hermes",
            "requiresReview": True,
            "requiredPermissions": list(validation.required_permissions),
            "consequentialSteps": list(validation.consequential_steps),
        },
    }
