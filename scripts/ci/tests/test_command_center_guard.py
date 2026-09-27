"""Plan 33 (C0) guard: the Command Center keeps no secrets, approvals stay a person's act in Glass, and every route
needs the gateway bearer."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
COMMAND = ROOT / "apps/device-gateway/cyclone_device_gateway/command"
GLASS = ROOT / "apps/glass/src"
MCP_DIRS = [ROOT / "tools/codex-phone-mcp", ROOT / "tools/cyclone-agent-mcp"]
PHONE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayV5CommandAdapter.kt"


class CommandCenterGuard(unittest.TestCase):
    def test_the_store_has_no_secret_columns(self):
        center = (COMMAND / "center.py").read_text(encoding="utf-8")
        schema = center[center.index("SCHEMA = "):center.index('"""', center.index("SCHEMA = ") + 12)]
        for word in ("password", "passcode", "otp", "totp_seed", "secret", "token", "cookie", "cvv"):
            assert word not in schema.lower(), f"Command Center schema must not hold {word}"

    def test_every_route_needs_the_bearer(self):
        api = (COMMAND / "api.py").read_text(encoding="utf-8")
        routes = [line.strip() for line in api.splitlines() if re.match(r"\s*@router\.(get|post|put|delete)\(", line)]
        assert len(routes) >= 15, "Command Center routes not found"
        for line in routes:
            assert "dependencies=[Depends(auth)]" in line, f"unauthenticated Command Center route: {line}"

    def test_agent_mcp_servers_never_reach_the_command_center(self):
        for folder in MCP_DIRS:
            if not folder.exists():
                continue
            for path in folder.rglob("*.py"):
                text = path.read_text(encoding="utf-8", errors="ignore")
                assert "/v1/cc/" not in text, f"{path}: agent MCP must not call Command Center routes (approvals stay human)"
                assert "cc.answer" not in text, f"{path}: agent MCP must not answer Owner Moments"

    def test_glass_never_asks_for_a_password(self):
        for name in ("pages/commandPage.ts", "services/command.ts"):
            text = (GLASS / name).read_text(encoding="utf-8")
            assert 'type = "password"' not in text and "type=\"password\"" not in text, f"{name} must not have password fields"

    def test_the_phone_approves_only_the_request_it_showed(self):
        text = PHONE.read_text(encoding="utf-8")
        assert "it.requestId == requestId" in text
        assert "MomentKind.SECRET" in text and "ANSWER_ON_PHONE" in text
        assert "TaskCommands.send" in text, "answers go through Task Kit"

    # Plan 33 (C1): the vault is zero-knowledge.
    def test_the_vault_store_holds_ciphertext_only(self):
        vault = (COMMAND / "vault.py").read_text(encoding="utf-8")
        schema = vault[vault.index('VAULT_SCHEMA = """'):vault.index('"""', vault.index('VAULT_SCHEMA = """') + 20)].lower()
        for word in ("password", "passphrase", "plaintext", "secret", "username", "label", "totp_seed", "recovery_key"):
            assert word not in schema, f"vault schema must not hold {word}"
        assert "print(" not in vault and "logging" not in vault and "logger" not in vault, "the vault store never logs"

    def test_glass_encrypts_before_sending_and_keeps_nothing(self):
        crypto = (GLASS / "services/vault.ts").read_text(encoding="utf-8")
        view = (GLASS / "pages/vaultView.ts").read_text(encoding="utf-8")
        assert "600_000" in crypto and '"AES-GCM"' in crypto and "additionalData" in crypto
        assert 'importKey("raw", raw as BufferSource, { name: "AES-GCM" }, false' in crypto, "keys are non-extractable"
        for text in (crypto, view):
            assert "sessionStorage" not in text and "indexedDB" not in text, "vault keys and items live in memory only"
        start = crypto.index("export const vaultApi")
        api = crypto[start:crypto.index("\n};", start)]
        assert "passphrase" not in api.lower() and "recovery" not in api.lower(), "the gateway API never takes a passphrase or recovery key"
        assert "IDLE_LOCK_MS" in view and "STEP_UP_MS" in view


if __name__ == "__main__":
    unittest.main()
