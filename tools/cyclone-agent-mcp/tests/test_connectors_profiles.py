from __future__ import annotations

import json
from pathlib import Path

import cyclone_agent_mcp.connector as connector
from cyclone_agent_mcp.profiles import (
    codex_toml,
    copilot_profile,
    deepseek_copilot_notes,
    deepseek_opencode_notes,
    generic_profile,
    opencode_profile,
)


def test_codex_config_generation_has_no_gateway_token():
    snippet = codex_toml(r"C:\Program Files\Cyclone\CycloneAgentMCP.exe", ["serve"])
    assert "CycloneAgentMCP.exe" in snippet
    assert "CYCLONE_DEVICE_GATEWAY_TOKEN" not in snippet
    assert "Bearer" not in snippet


def test_codex_dry_run_does_not_write(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    monkeypatch.setattr(connector, "codex_config_path", lambda: path)
    result = connector.connect("codex", dry_run=True, executable=str(tmp_path / "CycloneAgentMCP.exe"))
    assert result["dry_run"] is True
    assert not path.exists()


def test_codex_disconnect_removes_only_cyclone_block(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    monkeypatch.setattr(connector, "codex_config_path", lambda: path)
    path.write_text('model = "gpt"\n\n' + codex_toml("CycloneAgentMCP.exe", ["serve"]) + '\n[other]\nx = 1\n', encoding="utf-8")
    result = connector.disconnect("codex")
    text = path.read_text(encoding="utf-8")
    assert result["changed"] is True
    assert "CYCLONE AGENT MCP" not in text
    assert 'model = "gpt"' in text and "[other]" in text


def test_opencode_deepseek_profile_is_mcp_only_and_secret_free():
    profile = opencode_profile("CycloneAgentMCP.exe", ["serve"])
    rendered = json.dumps(profile)
    assert profile["mcp"]["servers"]["cyclone-phone"]["command"][0] == "CycloneAgentMCP.exe"
    assert "DEEPSEEK_API_KEY" not in rendered
    notes = deepseek_opencode_notes()
    assert notes["secrets_in_mcp_config"] is False
    assert "/connect" in notes["credential_setup"]


def test_copilot_profile_and_deepseek_byok_notes_are_secret_free():
    profile = copilot_profile("CycloneAgentMCP.exe", ["serve"])
    assert profile["mcpServers"]["cyclone-phone"]["env"] == {}
    notes = deepseek_copilot_notes()
    assert "COPILOT_PROVIDER_API_KEY" in notes["provider_environment"]
    assert notes["credential_value_embedded"] is False
    assert "sk-" not in json.dumps(notes)


def test_generic_mcp_profile_uses_same_server_surface():
    profile = generic_profile("CycloneAgentMCP.exe", ["serve"])
    assert set(profile["mcpServers"]) == {"cyclone-phone"}
    assert profile == copilot_profile("CycloneAgentMCP.exe", ["serve"])
