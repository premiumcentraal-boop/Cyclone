"""The Manager's tool registry (plan 55 R1; the shape follows Hermes Agent's ``tools/registry.py``, MIT).

Each toolset module registers its tools when it is imported, so adding a toolset is one new file and one import in
``toolsets/__init__.py``; nothing else keeps a list. The registry holds what the model is told (name, description,
JSON schema) and what Cyclone needs to treat a call safely (its kind). At run time a store binds every toolset to
the Command Center once and dispatches calls through it.

Rules the registry enforces at registration, so a bad tool fails at import and in CI, never in a turn:
- **Known kinds only.** ``read`` runs at once; ``workspace`` edits pages, cards and rows (a proposal unless the owner
  lets the AI edit the workspace); ``phone`` may start phone work and is always a proposal; ``ui`` only moves the
  owner's Glass (plan 55 R5) and changes nothing in Cyclone.
- **No forbidden powers.** A name that reads as shell, file, network, delete, approve, vault, password or key access
  is refused: those tools must not exist at all.
- **Strict schemas.** Every schema is an object with ``additionalProperties: false`` whose required fields exist.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Callable, Protocol

KINDS = ("read", "workspace", "phone", "ui")
NAME = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
#: Words no tool name may contain: these powers stay outside the Manager (AGENTS.md: no generic shell or root control).
FORBIDDEN = ("shell", "exec", "command", "terminal", "file", "http", "fetch", "url", "download", "upload", "delete",
             "remove", "approve", "vault", "password", "secret", "otp", "key", "token", "root", "adb", "install", "pay")


class Toolset(Protocol):
    """What a bound toolset does for the loop. ``kind`` decides which of read, prepare and execute is called."""

    def label(self, name: str, args: dict[str, Any]) -> str: ...

    def read(self, name: str, args: dict[str, Any]) -> dict[str, Any]: ...

    def prepare(self, name: str, args: dict[str, Any]) -> tuple[str, str]: ...

    def execute(self, name: str, args: dict[str, Any], *, actor: str) -> dict[str, Any]: ...


@dataclass(frozen=True)
class Tool:
    name: str
    kind: str
    description: str
    parameters: dict[str, Any]
    toolset: str

    def spec(self) -> dict[str, Any]:
        """The OpenAI-style function entry sent to the model."""
        return {"type": "function", "function": {"name": self.name, "description": self.description, "parameters": self.parameters}}


class RegistryError(ValueError):
    pass


class Registry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}
        self._toolsets: dict[str, Callable[[Any], Toolset]] = {}

    def toolset(self, name: str, factory: Callable[[Any], Toolset]) -> None:
        """Register how to build a toolset for one Command Center (called with the center)."""
        if name in self._toolsets:
            raise RegistryError(f"toolset {name} is registered twice")
        self._toolsets[name] = factory

    def register(self, name: str, kind: str, description: str, parameters: dict[str, Any], *, toolset: str) -> Tool:
        if not NAME.match(name):
            raise RegistryError(f"tool name {name!r} is lower_snake_case, 2..64 characters")
        if name in self._tools:
            raise RegistryError(f"tool {name} is registered twice")
        bad = next((w for w in FORBIDDEN if w in name.split("_")), None)
        if bad:
            raise RegistryError(f"tool {name}: '{bad}' is a power the Manager must not have")
        if kind not in KINDS:
            raise RegistryError(f"tool {name}: kind is one of {', '.join(KINDS)}")
        if toolset not in self._toolsets:
            raise RegistryError(f"tool {name}: register toolset {toolset} first")
        if not isinstance(description, str) or not description.strip():
            raise RegistryError(f"tool {name} needs a description")
        if (not isinstance(parameters, dict) or parameters.get("type") != "object" or parameters.get("additionalProperties") is not False
                or not isinstance(parameters.get("properties"), dict) or not set(parameters.get("required") or []) <= set(parameters["properties"])):
            raise RegistryError(f"tool {name}: the schema is a strict object whose required fields exist")
        tool = Tool(name, kind, description, parameters, toolset)
        self._tools[name] = tool
        return tool

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def tools(self) -> list[Tool]:
        return list(self._tools.values())

    def names(self) -> list[str]:
        return list(self._tools)

    def specs(self) -> list[dict[str, Any]]:
        return [t.spec() for t in self._tools.values()]

    def table(self) -> dict[str, tuple[str, str, dict[str, Any]]]:
        """name -> (kind, description, schema): the shape ``command.ai.TOOLS`` always had."""
        return {t.name: (t.kind, t.description, t.parameters) for t in self._tools.values()}

    def bind(self, center: Any) -> dict[str, Toolset]:
        """One instance of every toolset for this Command Center."""
        return {name: factory(center) for name, factory in self._toolsets.items()}


REGISTRY = Registry()
