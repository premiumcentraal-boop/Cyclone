from __future__ import annotations

import json
import tomllib
from pathlib import Path

from cyclone_agent_mcp.audit import SafeAuditLog
from cyclone_agent_mcp.safe import redact
from cyclone_agent_mcp.tool_catalog import TOOL_NAMES

ROOT = Path(__file__).parents[1]


def test_official_mcp_sdk_is_exactly_pinned():
    data = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    assert "mcp==2.0.0" in data["project"]["dependencies"]


def test_no_shell_or_adb_tools_exist():
    lowered = [name.lower() for name in TOOL_NAMES]
    assert not any("shell" in name or "adb" in name or "exec" in name or "command" in name for name in lowered)


def test_redaction_removes_secrets_recursively():
    data = {"token": "abc", "nested": {"api_key": "sk-1234567890123456", "safe": "ok"}, "authorization": "Bearer secret"}
    rendered = json.dumps(redact(data))
    assert "abc" not in rendered
    assert "sk-" not in rendered
    assert "Bearer secret" not in rendered
    assert "ok" in rendered


def test_audit_log_never_receives_argument_values(tmp_path):
    path = tmp_path / "audit.jsonl"
    audit = SafeAuditLog(str(path))
    audit.record("phone.type", ok=False, elapsed_ms=5, error_code="POLICY_DENIED")
    text = path.read_text(encoding="utf-8")
    assert "POLICY_DENIED" in text
    assert "value" not in text.lower()
    assert "token" not in text.lower()

def test_official_mcp_sdk_stdio_tools_list_when_sdk_is_available():
    import pytest
    pytest.importorskip("mcp")
    from cyclone_agent_mcp.connector import verify_tools_list
    result = verify_tools_list()
    assert result["ok"] is True
