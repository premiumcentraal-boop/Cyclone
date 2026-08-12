from pathlib import Path

import pytest
from pydantic import ValidationError

from app.settings import Settings


def test_settings_reads_explicit_runtime_paths_and_origins(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CYCLONE_HERMES_BASE_URL", "http://hermes:8642/")
    monkeypatch.setenv("CYCLONE_VAULT_PATH", "/vault")
    monkeypatch.setenv("CYCLONE_WORKSPACE_PATH", "/workspace")
    monkeypatch.setenv("CYCLONE_CORS_ORIGINS", "http://127.0.0.1:1420, http://localhost:1420")

    settings = Settings.from_environment()

    assert settings.hermes_base_url == "http://hermes:8642"
    assert settings.vault_path == Path("/vault")
    assert settings.workspace_path == Path("/workspace")
    assert settings.cors_origins == ["http://127.0.0.1:1420", "http://localhost:1420"]


def test_settings_rejects_non_http_hermes_endpoint() -> None:
    with pytest.raises(ValidationError, match="CYCLONE_HERMES_BASE_URL"):
        Settings(hermes_base_url="hermes:8642")
