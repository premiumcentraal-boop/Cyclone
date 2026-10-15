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



class AccountUseGuard(unittest.TestCase):
    """Plan 59 run 2c: a task logs in with a saved account only through one path, and never without a yes when the
    account is bound elsewhere."""

    def test_only_the_mission_port_takes_a_value_and_only_accountuse_asks_main(self):
        src = sources()
        take = {p for p, t in src.items() if "AccountUse.take(" in t}
        self.assertEqual(take, {"mind/mission/AndroidMindPorts.kt"}, take)
        ask = {p for p, t in src.items() if "askMainForAccount(" in t}
        self.assertEqual(ask, {"identity/AccountUse.kt", "runtime/workspaces/ProfileBootstrapRuntime.kt"}, ask)
        toolbox = src["mind/PhoneMindToolbox.kt"]
        self.assertNotIn("AccountUse", toolbox, "the Mind's toolbox only names a handle; the port fills")

    def test_a_value_is_served_only_bound_here_or_after_a_yes(self):
        use = (IDENTITY / "AccountUse.kt").read_text(encoding="utf-8")
        serve = use[use.index("fun serve("):use.index("class Refused")]
        self.assertIn('if (!here && !confirmed) return Result.failure(Refused("NEEDS_CONFIRMATION"))', serve)
        self.assertIn("identity.boundHere(book.me) && identity.profileId == profileId", serve)
        self.assertNotRegex(use, r"\bLog\.[a-z]\(|println\(")
        port = (MOBILE / "mind/mission/AndroidMindPorts.kt").read_text(encoding="utf-8")
        fill = port[port.index("private fun accountFill("):port.index("private fun appLabel(")]
        self.assertIn("gate = com.cyclone.mobile.identity.AccountUse.GATE", fill)
        self.assertIn("OwnerResponse.Approve -> confirmedAccounts += chosen.id", fill)
        self.assertIn("confirmed = chosen.id in confirmedAccounts", fill)

    def test_main_checks_who_asks_and_seals_the_answer_and_nothing_is_journaled(self):
        service = (MOBILE / "runtime/workspaces/ProfileBootstrapService.kt").read_text(encoding="utf-8")
        answer = service[service.index("private fun answerAccount("):service.index("private suspend fun sayHello(")]
        self.assertIn("it.androidUserId == asker", answer)
        self.assertIn("record.id == envelope.getString(\"profile\")", answer)
        self.assertIn("ProfileBootstrapRuntime.accountContext(asker, nonce)", answer)
        self.assertNotIn("Log.", answer)
        runtime = (MOBILE / "runtime/workspaces/ProfileBootstrapRuntime.kt").read_text(encoding="utf-8")
        ask = runtime[runtime.index("fun askMainForAccount("):runtime.index("fun launchAppIn(")]
        self.assertIn('runQuiet("/system/bin/cat", "$folder/account-reply-$nonce.json")', ask)

    def test_no_one_but_the_owner_answers_the_card_and_it_is_never_batched(self):
        lab = (MOBILE / "gateway/GatewayV5LabAdapter.kt").read_text(encoding="utf-8")
        may = lab[lab.index("internal fun labMayApprove("):lab.index("internal fun recordJson(")]
        self.assertNotIn('"account"', may)
        automation = (GATEWAY / "command/automation.py").read_text(encoding="utf-8")
        self.assertIn('row["gate"] != "account"', automation)
        glass = (ROOT / "apps/glass/src/services/command.ts").read_text(encoding="utf-8")
        self.assertIn('a.gate !== "account"', glass)


class HiveGuard(unittest.TestCase):
    """Plan 59 build 2: the PC relays the hive key and never holds it in the clear."""

    def test_the_pc_has_no_way_to_open_the_hive(self):
        hive = (GATEWAY / "command/hive.py").read_text(encoding="utf-8")
        for word in ("cryptography", "AESGCM", "decrypt(", "private_key", "exchange("):
            self.assertNotIn(word, hive, word)
        schema = hive[hive.index("HIVE_SCHEMA"):hive.index('"""', hive.index("HIVE_SCHEMA") + 16)]
        for word in ("plain", "secret", "password", "raw_key"):
            self.assertNotIn(word, schema.lower(), word)
        self.assertNotRegex(hive, r"\bprint\(|logging\.")

    def test_the_phone_keeps_the_hive_key_in_main_and_never_carries_it(self):
        keyring = (IDENTITY / "HiveKeyring.kt").read_text(encoding="utf-8")
        self.assertIn("IdentityVault.isMain(context)", keyring)
        self.assertIn('bound.optString("deviceKey") != ownFingerprint()', keyring)
        self.assertNotRegex(keyring, r"\bLog\.[a-z]\(")
        portable = (MOBILE / "runtime/workspaces/PortableSettings.kt").read_text(encoding="utf-8")
        self.assertIn('never("cyclone_hive_v1"', portable)
        for path, text in sources().items():
            if path.startswith(("mind/", "agent/", "ai/")):
                self.assertNotIn("HiveKeyring", text, path)

    def test_hive_items_are_bound_the_same_way_on_the_phone_and_in_the_browser(self):
        """Run 2b: the AAD binds id, kind, key version, time, writer and tombstone, identically on both sides."""
        kotlin = (IDENTITY / "HiveSync.kt").read_text(encoding="utf-8")
        glass = (ROOT / "apps/glass/src/services/hiveItems.ts").read_text(encoding="utf-8")
        self.assertIn('"cyclone-hive-item/v1\\n$itemId\\n$kind\\n$keyVersion\\n$at\\n$by\\n${if (gone) 1 else 0}"', kotlin)
        self.assertIn("`cyclone-hive-item/v1\\n${itemId}\\n${kind}\\n${keyVersion}\\n${at}\\n${by}\\n${gone ? 1 : 0}`", glass)
        self.assertNotRegex(kotlin, r"\bLog\.[a-z]\(|println\(")
        self.assertNotRegex(glass, r"localStorage|sessionStorage|indexedDB|console\.")
        for path, text in sources().items():
            if path.startswith(("mind/", "agent/", "ai/")):
                self.assertNotIn("HiveSync", text, path)

    def test_the_pc_keeps_hive_items_as_ciphertext_only(self):
        hive = (GATEWAY / "command/hive.py").read_text(encoding="utf-8")
        schema = hive[hive.index("HIVE_SCHEMA"):hive.index('"""', hive.index("HIVE_SCHEMA") + 16)]
        self.assertIn("hive_item", schema)
        for word in ("handle", "email", "app ", "profile", "card"):
            self.assertNotIn(word, schema.lower(), word)

    def test_the_browser_seals_the_hive_key_to_a_recomputed_fingerprint(self):
        glass = (ROOT / "apps/glass/src/services/hive.ts").read_text(encoding="utf-8")
        self.assertIn("(await fingerprint(publicKey)) !== phone.fingerprint", glass)
        self.assertIn('HIVE_INFO = "cyclone-hive-key/v1"', glass)


if __name__ == "__main__":
    unittest.main()
