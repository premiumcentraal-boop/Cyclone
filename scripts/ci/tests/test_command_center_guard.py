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
        open_routes = [line for line in routes if "dependencies=[Depends(auth)]" not in line]
        # C3: the OAuth redirect from the sign-in page cannot carry the bearer. It is the one exception, it only finishes a
        # sign-in Glass started (single-use state + PKCE verifier kept by the gateway), and it reflects nothing but a name.
        assert open_routes == ['@router.get("/v1/cc/connections/oauth/callback", response_class=HTMLResponse)'], f"unauthenticated Command Center route: {open_routes}"
        callback = api[api.index("def create_oauth_callback_router"):]
        assert "finish_sign_in(state, code" in callback and "html.escape" in callback

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

    # Plan 33 (C2): sealed delivery. The PC relays bytes it cannot open; the phone opens, fills once, forgets.
    def test_the_gateway_cannot_open_a_lease(self):
        delivery = (COMMAND / "delivery.py").read_text(encoding="utf-8")
        for word in ("cryptography", "AESGCM", "decrypt(", "private_key", "exchange("):
            assert word not in delivery, f"the gateway's delivery store must not hold keys or decrypt ({word})"
        schema = delivery[delivery.index('DELIVERY_SCHEMA = """'):delivery.index('"""', delivery.index('DELIVERY_SCHEMA = """') + 25)].lower()
        for word in ("password", "plaintext", "secret", "value"):
            assert word not in schema, f"lease schema must not hold {word}"

    def test_the_phone_binds_uses_once_and_never_persists_the_value(self):
        phone = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/secrets"
        sealed = (phone / "SealedDelivery.kt").read_text(encoding="utf-8")
        for bound in ('"NOT_FOR_THIS_PHONE"', '"AAD_TASK"', '"EXPIRED"', '"REPLAYED"', '"AAD_PLACE"'):
            assert bound in sealed, f"the phone must check {bound}"
        assert "placeMatches(currentPlace, entry.place)" in sealed, "a delivered secret fills only on its app or site"
        assert "OneShotSecretLease(value)" in sealed
        prefs = sealed[sealed.index("class Prefs"):]
        assert 'putStringSet("used"' in prefs and "value" not in prefs.split("override fun claim")[1].split("return true")[0].replace("leaseId", ""), \
            "only lease ids are persisted"
        key = (phone / "DeviceKey.kt").read_text(encoding="utf-8")
        assert "AndroidKeyStore" in key and "PURPOSE_AGREE_KEY" in key and "getEncoded" not in key, "the device key stays in Keystore"
        ports = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/mind/mission/AndroidMindPorts.kt").read_text(encoding="utf-8")
        assert "SealedDelivery.take(missionId, slot, place.id)" in ports and "taken.lease.revoke()" in ports

    def test_glass_seals_to_the_key_it_verified_and_wipes_plaintext(self):
        delivery = (GLASS / "services/delivery.ts").read_text(encoding="utf-8")
        assert "await fingerprint(publicKey)) !== pending.deviceKey.fingerprint" in delivery
        assert "plain.fill(0)" in delivery and "item.moved" in delivery
        hpke = (GLASS / "services/hpke.ts").read_text(encoding="utf-8")
        assert "AEAD_AES256GCM" in hpke and "KEM_P256" in hpke

    # Plan 33 (C3): connections. Fixed MCP methods, owner allowlists and caps, sign-in grants kept out of the database.
    def test_connections_call_only_allowed_tools_through_fixed_methods(self):
        client = (COMMAND / "mcp.py").read_text(encoding="utf-8")
        methods = set(re.findall(r'"method": "([^"]+)"', client)) | set(re.findall(r'self\.request\("([^"]+)"', client))
        assert methods == {"initialize", "notifications/initialized", "tools/list", "tools/call"}, methods
        store = (COMMAND / "connections.py").read_text(encoding="utf-8")
        assert "is not allowed. Allow it in Connections first." in store and '"[]", 10, "always"' in store, "no tool is allowed by default; new connections ask first"
        assert "MAX_PARALLEL" in store and "already waiting for your OK" in store and "daily cap" in store
        for text in (client, store):
            for word in ("subprocess", "os.system", "eval(", "exec(", "print(", "logging"):
                assert word not in text, f"connections must not use {word}"

    def test_sign_in_grants_never_reach_the_database_glass_or_the_audit(self):
        store = (COMMAND / "connections.py").read_text(encoding="utf-8")
        schema = store[store.index('CONNECTIONS_SCHEMA = """'):store.index('"""', store.index('CONNECTIONS_SCHEMA = """') + 25)].lower()
        for word in ("token", "secret", "password", "refresh", "grant"):
            assert word not in schema, f"connection schema must not hold {word}"
        assert "_dpapi_transform" in store and "class GrantStore" in store
        public = store[store.index("    def _public(self, r"):store.index("    def add(self")]
        assert "access_token" not in public and "refresh_token" not in public
        for line in store.splitlines():
            if "_audit(" in line:
                assert "token" not in line.lower() or "tokenendpoint" in line.lower(), f"audit must not carry a token: {line.strip()}"
        for name in ("pages/connectionsView.ts", "services/command.ts"):
            text = (GLASS / name).read_text(encoding="utf-8")
            assert "access_token" not in text and "refresh_token" not in text, f"{name} must never handle sign-in tokens"

    def test_a_posted_file_is_checked_and_its_share_is_a_send(self):
        phone = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
        media = (phone / "gateway/CommandMedia.kt").read_text(encoding="utf-8")
        assert '"MEDIA_CORRUPT"' in media and "MessageDigest.getInstance(\"SHA-256\")" in media and "(video|image|audio)/" in media
        gate = (phone / "policy/GatePolicy.kt").read_text(encoding="utf-8")
        assert "if (PublishGate.gates(selectedLabel)) return GateClass.SEND" in gate
        adapter = PHONE.read_text(encoding="utf-8")
        assert "PublishGate.missionId = if (publish == true) id else null" in adapter
        center = (COMMAND / "center.py").read_text(encoding="utf-8")
        assert 'extra["publish"] = True' in center and "needs the owner's OK" in center

    def test_prepared_leases_are_bound_to_their_own_run(self):
        delivery = (COMMAND / "delivery.py").read_text(encoding="utf-8")
        assert "RUN_WINDOW_MS" in delivery and "MAX_AHEAD_MS" in delivery and 'ahead["due_at"] < expires <= ahead["due_at"] + RUN_WINDOW_MS' in delivery
        center = (COMMAND / "center.py").read_text(encoding="utf-8")
        assert "task_id=reserved" in center and "_clear_slots(routine_id" in center and "_login_approval(task)" in center


if __name__ == "__main__":
    unittest.main()
