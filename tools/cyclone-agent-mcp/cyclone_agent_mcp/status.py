from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from .connector import resolve_server_command
from .profiles import SERVER_KEY, codex_config_path, copilot_config_path, opencode_config_path

HOST_STATES = {"CONNECTED", "READY", "NOT_INSTALLED", "ATTENTION"}
GENERIC_STATES = {"READY", "ATTENTION"}


def connection_status() -> dict[str, str]:
    server = resolve_server_command()
    server_ready = _command_exists(server.command)
    codex = _host_state("codex", codex_config_path(), _codex_configured, server_ready)
    opencode = _host_state("opencode", opencode_config_path(), _json_opencode_configured, server_ready)
    copilot = _host_state("copilot", copilot_config_path(), _json_copilot_configured, server_ready)
    deepseek = "READY" if server_ready and (opencode in {"CONNECTED", "READY"} or copilot in {"CONNECTED", "READY"}) else (
        "NOT_INSTALLED" if opencode == "NOT_INSTALLED" and copilot == "NOT_INSTALLED" else "ATTENTION"
    )
    generic = "READY" if server_ready else "ATTENTION"
    return {"codex": codex, "deepseek_harness": deepseek, "generic_mcp": generic}


def _host_state(host: str, path: Path, configured, server_ready: bool) -> str:
    installed = shutil.which(host) is not None
    if not installed:
        return "NOT_INSTALLED"
    try:
        has_config = configured(path)
    except Exception:
        return "ATTENTION"
    if has_config and server_ready:
        return "READY"
    if has_config:
        return "CONNECTED"
    return "ATTENTION"


def _command_exists(command: str) -> bool:
    path = Path(command)
    return path.exists() if path.is_absolute() else shutil.which(command) is not None


def _codex_configured(path: Path) -> bool:
    return path.exists() and f"[mcp_servers.{SERVER_KEY}]" in path.read_text(encoding="utf-8")


def _json_opencode_configured(path: Path) -> bool:
    data = _json(path)
    return SERVER_KEY in data.get("mcp", {}).get("servers", {})


def _json_copilot_configured(path: Path) -> bool:
    data = _json(path)
    return SERVER_KEY in data.get("mcpServers", {})


def _json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    value = json.loads(path.read_text(encoding="utf-8"))
    return value if isinstance(value, dict) else {}
