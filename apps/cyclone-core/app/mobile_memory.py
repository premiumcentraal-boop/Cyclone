"""Privacy-conscious durable memory adapter for learned mobile knowledge."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .memory import VaultEntry, VaultMemoryService


_ALLOWED_KINDS = frozenset(
    {"package", "screen", "selector", "terminology", "recovery", "skill_hint"}
)
_SECRET_KEYS = frozenset(
    {"password", "passcode", "secret", "token", "api_key", "apikey", "credential", "otp", "pin"}
)


@dataclass(frozen=True)
class MobileMemoryFact:
    kind: str
    package: str
    key: str
    value: Any
    confidence: float = 1.0


class MobileMemoryService:
    """Store stable app knowledge in the existing Obsidian-backed memory system.

    Credentials and verification secrets are rejected here rather than being
    written to plaintext notes. Callers should store only credential references
    in a platform-secure secret store.
    """

    def __init__(self, vault: VaultMemoryService) -> None:
        self._vault = vault

    def remember(self, fact: MobileMemoryFact, *, project_key: str | None = None) -> VaultEntry:
        if fact.kind not in _ALLOWED_KINDS:
            raise ValueError(f"Unsupported mobile memory kind: {fact.kind}")
        if not fact.package.strip() or not fact.key.strip():
            raise ValueError("Mobile memory requires package and key.")
        if self._contains_secret(fact.value) or self._looks_sensitive_key(fact.key):
            raise ValueError("Raw credentials or verification secrets must not be stored in mobile memory.")

        safe_package = fact.package.strip()
        payload = {
            "kind": fact.kind,
            "package": safe_package,
            "key": fact.key.strip(),
            "value": fact.value,
            "confidence": max(0.0, min(1.0, float(fact.confidence))),
        }
        title = f"Mobile {fact.kind}: {safe_package} - {fact.key.strip()}"
        return self._vault.write(
            title=title,
            category="Skills" if fact.kind in {"selector", "recovery", "skill_hint"} else "Knowledge",
            content="```json\n" + json.dumps(payload, indent=2, ensure_ascii=False) + "\n```",
            project_key=project_key,
            agent_slug="hermes",
        )

    def _looks_sensitive_key(self, key: str) -> bool:
        normalized = key.lower().replace("-", "_").replace(" ", "_")
        return any(part in _SECRET_KEYS for part in normalized.split("_"))

    def _contains_secret(self, value: Any) -> bool:
        if isinstance(value, dict):
            for key, child in value.items():
                if self._looks_sensitive_key(str(key)) and child not in (None, "", "***"):
                    return True
                if self._contains_secret(child):
                    return True
        elif isinstance(value, list):
            return any(self._contains_secret(item) for item in value)
        return False
