from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from .connector import host_installed, resolve_server_command
from .gateway import GatewayClient, GatewayError
from .profiles import SERVER_KEY, codex_config_path, copilot_config_path, opencode_config_path
from .tool_catalog import TOOL_NAMES

HOST_STATES = {"CONNECTED", "READY", "NOT_INSTALLED", "ATTENTION"}
GENERIC_STATES = {"READY", "ATTENTION"}


def connection_status(*, probe_gateway: bool = False) -> dict[str, Any]:
    server = resolve_server_command()
    server_ready = _command_exists(server.command)
    codex = _host_state("codex", codex_config_path(), _codex_configured, server_ready)
    opencode = _host_state("opencode", opencode_config_path(), _json_opencode_configured, server_ready)
    copilot = _host_state("copilot", copilot_config_path(), _json_copilot_configured, server_ready)
    deepseek = "READY" if server_ready and (opencode in {"CONNECTED", "READY"} or copilot in {"CONNECTED", "READY"}) else (
        "NOT_INSTALLED" if opencode == "NOT_INSTALLED" and copilot == "NOT_INSTALLED" else "ATTENTION"
    )
    generic = "READY" if server_ready else "ATTENTION"
    result: dict[str, Any] = {"codex": codex, "deepseek_harness": deepseek, "generic_mcp": generic}
    if probe_gateway:
        result["details"] = {
            "codex": {
                "state": codex,
                "detected": host_installed("codex"),
                "configured": _safe_configured(codex_config_path(), _codex_configured),
                "config_path": str(codex_config_path()),
                "server_ready": server_ready,
                "approval_mode": "writes",
            },
            "gateway": _gateway_status(),
            "mcp": {
                "server": SERVER_KEY,
                "tool_count": len(TOOL_NAMES),
                "transport": "stdio",
            },
        }
    return result


def _host_state(host: str, path: Path, configured, server_ready: bool) -> str:
    installed = host_installed(host)
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


def _safe_configured(path: Path, configured) -> bool:
    try:
        return bool(configured(path))
    except Exception:
        return False


def _gateway_status() -> dict[str, Any]:
    try:
        devices = GatewayClient(timeout=2.0).list_devices()
    except GatewayError as exc:
        code = "GATEWAY_UNAVAILABLE"
        if isinstance(exc.body, dict):
            error = exc.body.get("error")
            if isinstance(error, dict) and isinstance(error.get("code"), str):
                code = error["code"][:80]
        return {
            "state": "OFFLINE",
            "reachable": False,
            "ready_device_count": 0,
            "device_count": 0,
            "error_code": code,
        }
    except Exception:
        # Connector diagnostics must never make the desktop shell unusable.
        # Keep implementation details out of the model-facing status payload.
        return {
            "state": "OFFLINE",
            "reachable": False,
            "ready_device_count": 0,
            "device_count": 0,
            "error_code": "GATEWAY_UNAVAILABLE",
        }
    safe_devices = [device.safe_dict() for device in devices]
    ready = [device for device in devices if device.ready]
    return {
        "state": "READY" if ready else "NO_READY_PHONE",
        "reachable": True,
        "ready_device_count": len(ready),
        "device_count": len(devices),
        "devices": safe_devices,
    }


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
