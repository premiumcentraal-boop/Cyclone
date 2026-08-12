from pathlib import Path

import pytest

from app.memory import VaultMemoryService


def test_memory_write_creates_human_readable_markdown_in_allowed_category(tmp_path: Path) -> None:
    service = VaultMemoryService(tmp_path)

    entry = service.write(
        title="Decision: use Core as the integration boundary",
        category="Decisions",
        content="Cyclone Core owns the adapter boundary for Hermes and n8n.",
        project_key="cyclone",
    )

    saved = tmp_path / entry.vault_path
    assert saved.exists()
    assert saved.read_text(encoding="utf-8").startswith("---\ntitle: Decision: use Core as the integration boundary")
    assert "Cyclone Core owns" in saved.read_text(encoding="utf-8")


def test_memory_write_rejects_path_traversal_category_or_title(tmp_path: Path) -> None:
    service = VaultMemoryService(tmp_path)

    with pytest.raises(ValueError, match="category"):
        service.write(title="Safe", category="../System", content="Nope")

    with pytest.raises(ValueError, match="title"):
        service.write(title="../../escape", category="Knowledge", content="Nope")
