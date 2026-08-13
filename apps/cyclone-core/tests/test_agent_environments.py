from __future__ import annotations

import json
import os
import stat
from uuid import uuid4

import pytest

from app.agent_environments import (
    ENVIRONMENT_TEMPLATES,
    EnvironmentHealth,
    EnvironmentIntegrityError,
    EnvironmentLifecycle,
    EnvironmentValidationError,
    PrivateEnvironmentManager,
)


def test_provision_creates_private_agent_workspace_and_browser_profile(tmp_path) -> None:
    manager = PrivateEnvironmentManager(tmp_path / "agent-environments")
    agent_id = uuid4()

    result = manager.provision(agent_id=agent_id, agent_slug="research", template_key="research")

    paths = result.record.paths
    assert result.created is True
    assert paths.workspace.is_dir()
    assert paths.browser_profile.is_dir()
    assert paths.state.is_dir()
    assert {entry.name for entry in paths.agent_root.iterdir()} == {"workspace", "browser-profile", "state"}
    assert not (paths.agent_root / "shared").exists()
    assert paths.relative_root == f"agents/{agent_id.hex}"
    assert result.record.template_key == "research"
    assert result.record.lifecycle_state is EnvironmentLifecycle.READY
    assert result.record.health_state is EnvironmentHealth.HEALTHY
    assert set(ENVIRONMENT_TEMPLATES) == {"research", "developer", "reviewer"}
    manifest = json.loads(paths.manifest.read_text(encoding="utf-8"))
    assert manifest["agent_id"] == str(agent_id)
    assert manifest["relative_root_path"] == f"agents/{agent_id.hex}"
    if os.name != "nt":
        assert stat.S_IMODE(paths.workspace.stat().st_mode) == 0o700
        assert stat.S_IMODE(paths.manifest.stat().st_mode) == 0o600


def test_provision_is_idempotent_and_preserves_private_workspace_files(tmp_path) -> None:
    manager = PrivateEnvironmentManager(tmp_path / "agent-environments")
    agent_id = uuid4()
    first = manager.provision(agent_id=agent_id, agent_slug="developer", template_key="developer")
    private_file = first.record.paths.workspace / "keep-me.txt"
    private_file.write_text("private work", encoding="utf-8")

    second = manager.provision(agent_id=agent_id, agent_slug="developer", template_key="developer")

    assert second.created is False
    assert private_file.read_text(encoding="utf-8") == "private work"
    assert second.record.id == first.record.id
    assert second.record.repository_values()["relative_root_path"] == f"agents/{agent_id.hex}"


def test_reconcile_repairs_missing_profile_after_restart_without_touching_workspace(tmp_path) -> None:
    manager = PrivateEnvironmentManager(tmp_path / "agent-environments")
    agent_id = uuid4()
    provisioned = manager.provision(agent_id=agent_id, agent_slug="reviewer", template_key="reviewer")
    private_file = provisioned.record.paths.workspace / "review.md"
    private_file.write_text("review evidence", encoding="utf-8")
    provisioned.record.paths.browser_profile.rmdir()

    reconciled = PrivateEnvironmentManager(tmp_path / "agent-environments").reconcile(
        agent_id=agent_id,
        agent_slug="reviewer",
    )

    assert reconciled.created is False
    assert "browser_profile" in reconciled.repaired_paths
    assert reconciled.record.paths.browser_profile.is_dir()
    assert private_file.read_text(encoding="utf-8") == "review evidence"
    assert reconciled.record.lifecycle_state is EnvironmentLifecycle.READY
    assert reconciled.record.health_state is EnvironmentHealth.HEALTHY


@pytest.mark.parametrize(
    ("agent_id", "agent_slug"),
    [
        ("../../not-an-agent", "research"),
        (str(uuid4()), "../research"),
        (str(uuid4()), "Research"),
    ],
)
def test_provision_rejects_unsafe_agent_identifiers(tmp_path, agent_id: str, agent_slug: str) -> None:
    manager = PrivateEnvironmentManager(tmp_path / "agent-environments")

    with pytest.raises(EnvironmentValidationError):
        manager.provision(agent_id=agent_id, agent_slug=agent_slug, template_key="research")


def test_reconcile_refuses_to_adopt_missing_or_corrupt_manifest(tmp_path) -> None:
    manager = PrivateEnvironmentManager(tmp_path / "agent-environments")
    agent_id = uuid4()
    paths = manager.paths_for(agent_id)
    paths.agent_root.mkdir(parents=True)
    with pytest.raises(EnvironmentIntegrityError, match="without its Cyclone environment manifest"):
        manager.provision(agent_id=agent_id, agent_slug="research", template_key="research")

    paths.state.mkdir()
    paths.manifest.write_text("not-json", encoding="utf-8")
    with pytest.raises(EnvironmentIntegrityError, match="valid JSON"):
        manager.reconcile(agent_id=agent_id, agent_slug="research")


def test_lifecycle_transitions_are_explicit_and_invalid_jumps_are_rejected(tmp_path) -> None:
    manager = PrivateEnvironmentManager(tmp_path / "agent-environments")
    agent_id = uuid4()
    manager.provision(agent_id=agent_id, agent_slug="research", template_key="research")

    stopped = manager.transition(
        agent_id=agent_id,
        agent_slug="research",
        lifecycle_state=EnvironmentLifecycle.STOPPED,
        health_state=EnvironmentHealth.UNKNOWN,
    )

    assert stopped.lifecycle_state is EnvironmentLifecycle.STOPPED
    assert stopped.health_state is EnvironmentHealth.UNKNOWN
    after_restart = manager.reconcile(agent_id=agent_id, agent_slug="research")
    assert after_restart.record.lifecycle_state is EnvironmentLifecycle.STOPPED
    assert after_restart.record.health_state is EnvironmentHealth.UNKNOWN
    with pytest.raises(EnvironmentValidationError, match="Cannot transition"):
        manager.transition(
            agent_id=agent_id,
            agent_slug="research",
            lifecycle_state=EnvironmentLifecycle.READY,
        )
