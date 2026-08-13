"""Private, restart-safe filesystem foundations for persistent Cyclone agents.

This module deliberately manages *only* the filesystem layout and its
self-describing manifest.  Process orchestration, browser launchers and database I/O
are integration concerns.  Keeping the boundary small means an agent never
receives a host path supplied by a chat message, and no shared writable folder
appears merely because an environment was provisioned.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
import json
import os
from pathlib import Path
import re
from typing import Any, Iterable
from uuid import UUID, NAMESPACE_URL, uuid4, uuid5


LAYOUT_VERSION = 1
_AGENT_SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
_UUID_TEXT = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
_MANIFEST_FILE = ".cyclone-environment.json"


class EnvironmentError(RuntimeError):
    """Base error for private environment lifecycle operations."""


class EnvironmentValidationError(EnvironmentError):
    """An identifier, template, or lifecycle transition is unsafe or unknown."""


class EnvironmentIntegrityError(EnvironmentError):
    """An existing environment cannot safely be adopted or reconciled."""


class EnvironmentLifecycle(str, Enum):
    PROVISIONING = "provisioning"
    READY = "ready"
    STOPPED = "stopped"
    RECONCILING = "reconciling"
    ERROR = "error"
    RETIRED = "retired"


class EnvironmentHealth(str, Enum):
    UNKNOWN = "unknown"
    HEALTHY = "healthy"
    DEGRADED = "degraded"
    UNHEALTHY = "unhealthy"


@dataclass(frozen=True)
class EnvironmentTemplate:
    """Static, operator-reviewed capabilities for a provisioned agent class."""

    key: str
    display_name: str
    description: str
    browser_profile: str
    default_capabilities: tuple[str, ...]


ENVIRONMENT_TEMPLATES: dict[str, EnvironmentTemplate] = {
    "research": EnvironmentTemplate(
        key="research",
        display_name="Research",
        description="Private research workspace with a brokered browser profile.",
        browser_profile="private",
        default_capabilities=("workspace.read", "workspace.write", "browser.brokered"),
    ),
    "developer": EnvironmentTemplate(
        key="developer",
        display_name="Developer",
        description="Private code workspace with a brokered browser profile.",
        browser_profile="private",
        default_capabilities=("workspace.read", "workspace.write", "git.worktree", "browser.brokered"),
    ),
    "reviewer": EnvironmentTemplate(
        key="reviewer",
        display_name="Reviewer",
        description="Private review workspace with a brokered browser profile.",
        browser_profile="private",
        default_capabilities=("workspace.read", "workspace.write", "review.verify", "browser.brokered"),
    ),
}


@dataclass(frozen=True)
class EnvironmentPaths:
    """Absolute paths derived from one operator-configured root and agent ID."""

    root: Path
    agent_root: Path
    workspace: Path
    browser_profile: Path
    state: Path
    manifest: Path

    @property
    def relative_root(self) -> str:
        return f"agents/{self.agent_root.name}"


@dataclass(frozen=True)
class AgentEnvironmentRecord:
    """Filesystem record that maps directly onto the future repository upsert."""

    id: UUID
    agent_id: UUID
    agent_slug: str
    template_key: str
    relative_root_path: str
    lifecycle_state: EnvironmentLifecycle
    health_state: EnvironmentHealth
    created_at: datetime
    updated_at: datetime
    last_reconciled_at: datetime
    paths: EnvironmentPaths

    def repository_values(self) -> dict[str, Any]:
        """Values for the ``agent_environments`` repository upsert boundary.

        The filesystem root intentionally is not persisted.  An operator may
        relocate it between restarts; ``relative_root_path`` keeps the stored
        record portable while still making the accepted on-disk layout precise.
        """

        return {
            "id": self.id,
            "agent_id": self.agent_id,
            "template_key": self.template_key,
            "relative_root_path": self.relative_root_path,
            "layout_version": LAYOUT_VERSION,
            "lifecycle_state": self.lifecycle_state.value,
            "health_state": self.health_state.value,
            "last_reconciled_at": self.last_reconciled_at,
            "last_healthy_at": self.last_reconciled_at if self.health_state is EnvironmentHealth.HEALTHY else None,
        }


@dataclass(frozen=True)
class ReconciliationResult:
    record: AgentEnvironmentRecord
    created: bool
    repaired_paths: tuple[str, ...]


_ALLOWED_TRANSITIONS: dict[EnvironmentLifecycle, set[EnvironmentLifecycle]] = {
    EnvironmentLifecycle.PROVISIONING: {EnvironmentLifecycle.READY, EnvironmentLifecycle.ERROR},
    EnvironmentLifecycle.READY: {
        EnvironmentLifecycle.RECONCILING,
        EnvironmentLifecycle.STOPPED,
        EnvironmentLifecycle.ERROR,
    },
    EnvironmentLifecycle.STOPPED: {EnvironmentLifecycle.PROVISIONING, EnvironmentLifecycle.RETIRED},
    EnvironmentLifecycle.RECONCILING: {EnvironmentLifecycle.READY, EnvironmentLifecycle.ERROR},
    EnvironmentLifecycle.ERROR: {EnvironmentLifecycle.RECONCILING, EnvironmentLifecycle.RETIRED},
    EnvironmentLifecycle.RETIRED: set(),
}


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _timestamp(value: str, field_name: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as exc:
        raise EnvironmentIntegrityError(f"Manifest {field_name} is not an ISO timestamp") from exc
    if parsed.tzinfo is None:
        raise EnvironmentIntegrityError(f"Manifest {field_name} must include a timezone")
    return parsed


def _validate_slug(agent_slug: str) -> str:
    if not _AGENT_SLUG.fullmatch(agent_slug):
        raise EnvironmentValidationError("Agent slug must match the Cyclone slug contract")
    return agent_slug


def _validate_agent_id(agent_id: UUID | str) -> UUID:
    if isinstance(agent_id, UUID):
        return agent_id
    if not isinstance(agent_id, str) or not _UUID_TEXT.fullmatch(agent_id):
        raise EnvironmentValidationError("Agent ID must be a canonical UUID")
    return UUID(agent_id)


class PrivateEnvironmentManager:
    """Create and reconcile isolated per-agent directories below one safe root.

    ``root`` is an operator configuration value, never an agent-controlled
    field.  All descendants derive from a UUID; neither agent names nor chat
    input can select a filesystem path.
    """

    def __init__(self, root: Path | str, *, templates: dict[str, EnvironmentTemplate] | None = None) -> None:
        configured_root = Path(root).expanduser()
        if not configured_root.is_absolute():
            raise EnvironmentValidationError("Private environment root must be an absolute operator path")
        self.root = configured_root.resolve(strict=False)
        self.templates = templates or ENVIRONMENT_TEMPLATES
        if not self.templates:
            raise EnvironmentValidationError("At least one environment template is required")

    def paths_for(self, agent_id: UUID | str) -> EnvironmentPaths:
        parsed_agent_id = _validate_agent_id(agent_id)
        agent_root = self._safe_child(Path("agents") / parsed_agent_id.hex)
        return EnvironmentPaths(
            root=self.root,
            agent_root=agent_root,
            workspace=self._safe_child(Path("agents") / parsed_agent_id.hex / "workspace"),
            browser_profile=self._safe_child(Path("agents") / parsed_agent_id.hex / "browser-profile"),
            state=self._safe_child(Path("agents") / parsed_agent_id.hex / "state"),
            manifest=self._safe_child(Path("agents") / parsed_agent_id.hex / "state" / _MANIFEST_FILE),
        )

    def provision(
        self,
        *,
        agent_id: UUID | str,
        agent_slug: str,
        template_key: str,
    ) -> ReconciliationResult:
        """Idempotently provision one private layout without creating shared mounts."""

        parsed_agent_id = _validate_agent_id(agent_id)
        _validate_slug(agent_slug)
        template = self._template(template_key)
        paths = self.paths_for(parsed_agent_id)
        self._make_private_directory(self.root)
        self._make_private_directory(paths.agent_root.parent)

        if paths.agent_root.exists() and not paths.manifest.exists():
            raise EnvironmentIntegrityError(
                "Refusing to adopt an existing agent directory without its Cyclone environment manifest"
            )

        if paths.manifest.exists():
            current = self._read_record(paths)
            self._assert_identity(current, parsed_agent_id, agent_slug, paths)
            repaired_paths = self._ensure_layout(paths)
            record = self._write_record(
                paths=paths,
                agent_id=parsed_agent_id,
                agent_slug=agent_slug,
                template_key=template.key,
                lifecycle_state=EnvironmentLifecycle.READY,
                health_state=EnvironmentHealth.HEALTHY,
                created_at=current.created_at,
            )
            return ReconciliationResult(record=record, created=False, repaired_paths=tuple(repaired_paths))

        repaired_paths = self._ensure_layout(paths)
        record = self._write_record(
            paths=paths,
            agent_id=parsed_agent_id,
            agent_slug=agent_slug,
            template_key=template.key,
            lifecycle_state=EnvironmentLifecycle.READY,
            health_state=EnvironmentHealth.HEALTHY,
            created_at=_now(),
        )
        return ReconciliationResult(record=record, created=True, repaired_paths=tuple(repaired_paths))

    def reconcile(self, *, agent_id: UUID | str, agent_slug: str) -> ReconciliationResult:
        """Repair only missing private layout directories after a restart.

        It never recreates a missing manifest, deletes unexpected data, or
        follows a directory outside the configured root.  Those conditions are
        integrity failures that need an operator decision rather than silent
        recovery.
        """

        parsed_agent_id = _validate_agent_id(agent_id)
        _validate_slug(agent_slug)
        paths = self.paths_for(parsed_agent_id)
        if not paths.manifest.exists():
            raise EnvironmentIntegrityError("Cannot reconcile an environment with no manifest")
        current = self._read_record(paths)
        self._assert_identity(current, parsed_agent_id, agent_slug, paths)
        if current.lifecycle_state is EnvironmentLifecycle.RETIRED:
            raise EnvironmentValidationError("A retired environment may not be reconciled")
        repaired_paths = self._ensure_layout(paths)
        lifecycle_state = (
            EnvironmentLifecycle.STOPPED
            if current.lifecycle_state is EnvironmentLifecycle.STOPPED
            else EnvironmentLifecycle.READY
        )
        health_state = (
            current.health_state
            if current.lifecycle_state is EnvironmentLifecycle.STOPPED
            else EnvironmentHealth.HEALTHY
        )
        record = self._write_record(
            paths=paths,
            agent_id=parsed_agent_id,
            agent_slug=agent_slug,
            template_key=current.template_key,
            lifecycle_state=lifecycle_state,
            health_state=health_state,
            created_at=current.created_at,
        )
        return ReconciliationResult(record=record, created=False, repaired_paths=tuple(repaired_paths))

    def reconcile_many(self, records: Iterable[AgentEnvironmentRecord]) -> list[ReconciliationResult]:
        """Reconcile repository records in deterministic order during startup."""

        return [
            self.reconcile(agent_id=record.agent_id, agent_slug=record.agent_slug)
            for record in sorted(records, key=lambda item: str(item.agent_id))
        ]

    def transition(
        self,
        *,
        agent_id: UUID | str,
        agent_slug: str,
        lifecycle_state: EnvironmentLifecycle,
        health_state: EnvironmentHealth | None = None,
    ) -> AgentEnvironmentRecord:
        """Persist an explicit lifecycle transition after a runtime action."""

        parsed_agent_id = _validate_agent_id(agent_id)
        _validate_slug(agent_slug)
        paths = self.paths_for(parsed_agent_id)
        current = self._read_record(paths)
        self._assert_identity(current, parsed_agent_id, agent_slug, paths)
        if lifecycle_state not in _ALLOWED_TRANSITIONS[current.lifecycle_state]:
            raise EnvironmentValidationError(
                f"Cannot transition environment from {current.lifecycle_state.value} to {lifecycle_state.value}"
            )
        return self._write_record(
            paths=paths,
            agent_id=parsed_agent_id,
            agent_slug=agent_slug,
            template_key=current.template_key,
            lifecycle_state=lifecycle_state,
            health_state=health_state or current.health_state,
            created_at=current.created_at,
        )

    def _template(self, key: str) -> EnvironmentTemplate:
        template = self.templates.get(key)
        if template is None:
            raise EnvironmentValidationError(f"Unknown agent environment template '{key}'")
        return template

    def _safe_child(self, relative_path: Path) -> Path:
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise EnvironmentValidationError("Environment layout may not escape its configured root")
        candidate = (self.root / relative_path).resolve(strict=False)
        try:
            candidate.relative_to(self.root)
        except ValueError as exc:
            raise EnvironmentIntegrityError("Environment layout resolved outside its configured root") from exc
        return candidate

    def _make_private_directory(self, directory: Path) -> bool:
        if directory.exists() and not directory.is_dir():
            raise EnvironmentIntegrityError(f"Expected directory but found another filesystem object: {directory}")
        created = not directory.exists()
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        resolved = directory.resolve(strict=True)
        try:
            resolved.relative_to(self.root)
        except ValueError as exc:
            raise EnvironmentIntegrityError("Private environment path escapes configured root") from exc
        # This is enforced inside Cyclone's Linux containers. Windows ACL
        # hardening belongs to the host environment provisioner, not chmod.
        if os.name != "nt":
            os.chmod(resolved, 0o700)
        return created

    def _ensure_layout(self, paths: EnvironmentPaths) -> list[str]:
        repaired: list[str] = []
        for label, directory in (
            ("agent_root", paths.agent_root),
            ("workspace", paths.workspace),
            ("browser_profile", paths.browser_profile),
            ("state", paths.state),
        ):
            if self._make_private_directory(directory):
                repaired.append(label)
        return repaired

    def _read_record(self, paths: EnvironmentPaths) -> AgentEnvironmentRecord:
        try:
            payload = json.loads(paths.manifest.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise EnvironmentIntegrityError("Environment manifest cannot be read as valid JSON") from exc
        if not isinstance(payload, dict) or payload.get("layout_version") != LAYOUT_VERSION:
            raise EnvironmentIntegrityError("Environment manifest has an unsupported layout version")
        try:
            agent_id = _validate_agent_id(payload["agent_id"])
            environment_id = _validate_agent_id(payload["environment_id"])
            agent_slug = _validate_slug(payload["agent_slug"])
            template_key = str(payload["template_key"])
            self._template(template_key)
            lifecycle_state = EnvironmentLifecycle(payload["lifecycle_state"])
            health_state = EnvironmentHealth(payload["health_state"])
            relative_root_path = str(payload["relative_root_path"])
            created_at = _timestamp(payload["created_at"], "created_at")
            updated_at = _timestamp(payload["updated_at"], "updated_at")
            last_reconciled_at = _timestamp(payload["last_reconciled_at"], "last_reconciled_at")
        except (KeyError, TypeError, ValueError) as exc:
            raise EnvironmentIntegrityError("Environment manifest has invalid identity or lifecycle data") from exc
        record = AgentEnvironmentRecord(
            id=environment_id,
            agent_id=agent_id,
            agent_slug=agent_slug,
            template_key=template_key,
            relative_root_path=relative_root_path,
            lifecycle_state=lifecycle_state,
            health_state=health_state,
            created_at=created_at,
            updated_at=updated_at,
            last_reconciled_at=last_reconciled_at,
            paths=paths,
        )
        expected_environment_id = self._environment_id(agent_id)
        if record.id != expected_environment_id or record.relative_root_path != paths.relative_root:
            raise EnvironmentIntegrityError("Environment manifest does not match its derived private layout")
        return record

    def _write_record(
        self,
        *,
        paths: EnvironmentPaths,
        agent_id: UUID,
        agent_slug: str,
        template_key: str,
        lifecycle_state: EnvironmentLifecycle,
        health_state: EnvironmentHealth,
        created_at: datetime,
    ) -> AgentEnvironmentRecord:
        now = _now()
        record = AgentEnvironmentRecord(
            id=self._environment_id(agent_id),
            agent_id=agent_id,
            agent_slug=agent_slug,
            template_key=template_key,
            relative_root_path=paths.relative_root,
            lifecycle_state=lifecycle_state,
            health_state=health_state,
            created_at=created_at,
            updated_at=now,
            last_reconciled_at=now,
            paths=paths,
        )
        payload = {
            "layout_version": LAYOUT_VERSION,
            "environment_id": str(record.id),
            "agent_id": str(record.agent_id),
            "agent_slug": record.agent_slug,
            "template_key": record.template_key,
            "relative_root_path": record.relative_root_path,
            "lifecycle_state": record.lifecycle_state.value,
            "health_state": record.health_state.value,
            "created_at": record.created_at.isoformat(),
            "updated_at": record.updated_at.isoformat(),
            "last_reconciled_at": record.last_reconciled_at.isoformat(),
        }
        temporary = paths.state / f".{_MANIFEST_FILE}.{os.getpid()}.{uuid4().hex}.tmp"
        try:
            temporary.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            if os.name != "nt":
                os.chmod(temporary, 0o600)
            os.replace(temporary, paths.manifest)
            if os.name != "nt":
                os.chmod(paths.manifest, 0o600)
        finally:
            if temporary.exists():
                temporary.unlink(missing_ok=True)
        return record

    @staticmethod
    def _environment_id(agent_id: UUID) -> UUID:
        return uuid5(NAMESPACE_URL, f"cyclone-agent-environment:{agent_id}")

    @staticmethod
    def _assert_identity(
        record: AgentEnvironmentRecord,
        agent_id: UUID,
        agent_slug: str,
        paths: EnvironmentPaths,
    ) -> None:
        if record.agent_id != agent_id or record.agent_slug != agent_slug or record.paths.agent_root != paths.agent_root:
            raise EnvironmentIntegrityError("Environment manifest identity does not match the requested agent")
