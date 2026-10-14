"""Plan 59 guard: saved accounts (identities) stay encrypted in Main, never logged, never carried or sent in the clear,
and only Cyclone's own code paths may hand one to Main or open an app in another profile."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MOBILE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
IDENTITY = MOBILE / "identity"
GATEWAY = ROOT / "apps/device-gateway/cyclone_device_gateway"


def sources() -> dict[str, str]:
    return {p.relative_to(MOBILE).as_posix(): p.read_text(encoding="utf-8") for p in MOBILE.rglob("*.kt")}


class IdentityGuard(unittest.TestCase):
    def test_the_identity_package_never_logs(self):
        for path in IDENTITY.glob("*.kt"):
            text = path.read_text(encoding="utf-8")
            self.assertNotRegex(text, r"\bLog\.[a-z]\(|println\(|printStackTrace", path.name)

    def test_the_book_is_one_keystore_box_and_never_carried(self):
        vault = (IDENTITY / "IdentityVault.kt").read_text(encoding="utf-8")
        self.assertIn('"AndroidKeyStore"', vault)
        self.assertIn('"AES/GCM/NoPadding"', vault)
        self.assertIn("check(isMain(context))", vault)
        portable = (MOBILE / "runtime/workspaces/PortableSettings.kt").read_text(encoding="utf-8")
        self.assertIn('never("cyclone_identities_v1"', portable)
        carry = (MOBILE / "runtime/workspaces/ProfileCarry.kt").read_text(encoding="utf-8")
        self.assertNotIn("Identity", carry[carry.index("fun pack("):carry.index("fun absorb(")])

    def test_a_password_never_shows_in_to_string(self):
        book = (IDENTITY / "IdentityBook.kt").read_text(encoding="utf-8")
        to_string = book[book.index("override fun toString()"):].split("\n", 1)[0]
        self.assertNotIn("password", to_string.split("=", 1)[1])

    def test_only_cyclone_paths_reach_the_handoff_and_the_launcher(self):
        src = sources()
        hand = {p for p, t in src.items() if "handIdentityToMain(" in t}
        self.assertEqual(hand, {"identity/IdentitySaver.kt", "runtime/workspaces/ProfileBootstrapRuntime.kt"}, hand)
        launch = {p for p, t in src.items() if "launchAppIn(" in t}
        self.assertEqual(launch, {"ui/v32/AccountsSettingsPanel.kt", "runtime/workspaces/ProfileBootstrapRuntime.kt"}, launch)
        for path, text in src.items():
            if path.startswith(("mind/", "gateway/", "agent/", "ai/")):
                self.assertNotIn("IdentityVault", text, path)

    def test_the_handoff_is_sealed_and_bound_to_main_and_its_nonce(self):
        runtime = (MOBILE / "runtime/workspaces/ProfileBootstrapRuntime.kt").read_text(encoding="utf-8")
        hand = runtime[runtime.index("fun handIdentityToMain("):runtime.index("fun launchAppIn(")]
        self.assertIn("ProfileTransferCipher.seal(publicKey, plain, identityContext(target, nonce))", hand)
        self.assertIn("plain.fill(0)", hand)
        service = (MOBILE / "runtime/workspaces/ProfileBootstrapService.kt").read_text(encoding="utf-8")
        take = service[service.index("private fun takeInIdentity("):service.index("private suspend fun sayHello(")]
        self.assertIn("ProfileBootstrapRuntime.identityContext(me, nonce)", take)
        self.assertIn('envelope.getInt("target") == me', take)
        self.assertNotIn("Log.", take)

    def test_the_pc_holds_no_identity_values(self):
        identities = (GATEWAY / "command/identities.py").read_text(encoding="utf-8")
        schema = identities[identities.index("IDENTITY_SCHEMA"):identities.index('"""', identities.index("IDENTITY_SCHEMA") + 22)]
        for word in ("password", "birthday", "email", "phone", "first_name", "full_name", "secret"):
            self.assertNotIn(word, schema.lower(), word)
        self.assertNotRegex(identities, r"\bprint\(|logging\.")


if __name__ == "__main__":
    unittest.main()
