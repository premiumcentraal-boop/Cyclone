"""Central least-privilege policy evaluation for host capabilities.

This is deliberately deterministic. An approval is a proposed action record,
not permission to run arbitrary shell text.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import PureWindowsPath
from uuid import UUID


READ_ONLY_CAPABILITIES = {"filesystem.read", "process.list", "window.list"}
CONSEQUENTIAL_CAPABILITIES = {
    "filesystem.write",
    "app.launch",
    "powershell.execute",
    "git.execute",
    "browser.open",
    "browser.navigate",
    "screenshot.capture",
}


@dataclass(frozen=True)
class HostAction:
    capability: str
    target: str
    arguments: dict[str, object]
    conversation_id: UUID
    task_id: UUID | None = None


@dataclass(frozen=True)
class PolicyDecision:
    allowed: bool
    requires_approval: bool
    reason: str


class PolicyEngine:
    def __init__(self, workspace_root: str = "C:/Users/Agent/Documents/CycloneWorkspace") -> None:
        self.workspace_root = PureWindowsPath(workspace_root)

    def _is_workspace_path(self, target: str) -> bool:
        candidate = PureWindowsPath(target)
        try:
            candidate.relative_to(self.workspace_root)
            return True
        except ValueError:
            return False

    def evaluate(self, action: HostAction) -> PolicyDecision:
        if action.capability not in READ_ONLY_CAPABILITIES | CONSEQUENTIAL_CAPABILITIES:
            return PolicyDecision(False, False, "Capability is not allowlisted.")

        if action.capability.startswith("filesystem.") and not self._is_workspace_path(action.target):
            return PolicyDecision(
                False,
                False,
                "Filesystem target is outside the allowlisted Cyclone workspace.",
            )

        if action.capability in READ_ONLY_CAPABILITIES:
            return PolicyDecision(True, False, "Allowlisted read-only capability.")

        return PolicyDecision(
            False,
            True,
            "Consequential host capability requires a recorded administrator approval.",
        )
