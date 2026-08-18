"""Convert successful interactive phone traces into reviewable Skill candidates."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable


_SELECTOR_TOOLS = frozenset(
    {
        "phone.find",
        "phone.click",
        "phone.long_press",
        "phone.type",
        "phone.replace_text",
        "phone.wait_for",
        "phone.assert",
    }
)
_COORDINATE_ONLY_TOOLS = frozenset({"phone.tap"})


@dataclass(frozen=True)
class TraceStep:
    tool: str
    params: dict[str, Any]
    ok: bool
    evidence: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class SkillCandidate:
    name: str
    inputs: tuple[str, ...]
    steps: tuple[dict[str, Any], ...]
    assertions: tuple[dict[str, Any], ...]
    fallback: tuple[str, ...]
    requires_review: bool = True

    def as_document(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "inputs": list(self.inputs),
            "steps": [dict(step) for step in self.steps],
            "assertions": [dict(assertion) for assertion in self.assertions],
            "fallback": list(self.fallback),
            "metadata": {
                "learnedFrom": "interactive_trace",
                "requiresReview": self.requires_review,
                "enabled": False,
            },
        }


def trace_to_skill_candidate(
    *,
    name: str,
    trace: Iterable[TraceStep],
    inputs: Iterable[str] = (),
) -> SkillCandidate:
    """Compile only deterministic successful actions; never auto-enable a learned Skill."""

    if not name.strip():
        raise ValueError("Skill candidate requires a name.")

    compiled_steps: list[dict[str, Any]] = []
    assertions: list[dict[str, Any]] = []
    for index, step in enumerate(trace):
        if not step.tool.startswith("phone."):
            raise ValueError(f"Trace step {index} is not a phone.* tool.")
        if not step.ok:
            raise ValueError(f"Trace step {index} failed and cannot become a deterministic Skill.")
        if step.tool in _COORDINATE_ONLY_TOOLS:
            selector = step.params.get("selector")
            if not isinstance(selector, dict) or not selector:
                raise ValueError(
                    f"Trace step {index} is coordinate-only; resolve a stable selector before learning it."
                )
        if step.tool in _SELECTOR_TOOLS and step.tool not in {"phone.find"}:
            selector = step.params.get("selector")
            if not isinstance(selector, dict) or not selector:
                raise ValueError(
                    f"Trace step {index} needs a structured selector before it can be learned."
                )

        if step.tool == "phone.assert":
            assertions.append({"tool": step.tool, "params": dict(step.params)})
        elif step.tool not in {"phone.observe", "phone.screenshot", "phone.find"}:
            compiled_steps.append({"tool": step.tool, "params": dict(step.params)})

        after_fingerprint = step.evidence.get("afterFingerprint")
        if isinstance(after_fingerprint, str) and after_fingerprint:
            assertions.append(
                {
                    "type": "screen_fingerprint_changed",
                    "afterFingerprint": after_fingerprint,
                    "sourceStep": index,
                }
            )

    if not compiled_steps:
        raise ValueError("Trace contains no reusable deterministic actions.")

    return SkillCandidate(
        name=name.strip(),
        inputs=tuple(str(value).strip() for value in inputs if str(value).strip()),
        steps=tuple(compiled_steps),
        assertions=tuple(assertions),
        fallback=("fresh_observe", "known_recovery", "ai_recovery", "human_takeover"),
    )
