"""One secret-shape rule per runtime, and the three runtimes agree (plan 76 consolidation, alpha 157).

The rule refuses a text with a secret keyword followed by ``:`` or ``=`` ("password: …", "api_keys = …"). Its home:

- gateway: ``INLINE_SECRET`` in ``desktop_runtime/v5_contract.py`` (every module imports it);
- Glass: ``INLINE_SECRET`` in ``services/secretGuards.ts``, the gateway's rule plus ``bearer``;
- phone: ``SecretShape.INLINE`` in ``secrets/SecretShape.kt``, the gateway's rule exactly.

A copy elsewhere fails this test; so does a keyword added to one runtime and not the others.
"""
from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GATEWAY = ROOT / "apps/device-gateway/cyclone_device_gateway/desktop_runtime/v5_contract.py"
GLASS = ROOT / "apps/glass/src/services/secretGuards.ts"
PHONE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/secrets/SecretShape.kt"

# A refusal rule's tell: a keyword alternation that includes these, ending in an assignment mark.
# Redaction rules (keyword and value, "\\S*") and keyword hints ("[:=]?") are different jobs and are not matched.
RULE = re.compile(r"\(password\|passcode\|[^)\n]*cvv[^\n]*\[:=\][\"/]")


def tracked(*patterns: str) -> list[Path]:
    out = subprocess.run(["git", "ls-files", *(f":(glob){p}" for p in patterns)], cwd=ROOT, capture_output=True, text=True, check=True)
    return [ROOT / line for line in out.stdout.split() if (ROOT / line).is_file()]


def keywords(text: str) -> str:
    match = RULE.search(text)
    assert match, "rule not found"
    body = match.group(0)
    return body[body.index("(") + 1 : body.rindex(")s?")]


class SecretShapeParity(unittest.TestCase):
    def test_glass_and_phone_match_the_gateway(self):
        gateway = keywords(GATEWAY.read_text(encoding="utf-8"))
        self.assertEqual(gateway + "|bearer", keywords(GLASS.read_text(encoding="utf-8")))
        self.assertEqual(gateway, keywords(PHONE.read_text(encoding="utf-8")))

    def test_the_rule_is_defined_once_per_runtime(self):
        homes = {GATEWAY, GLASS, PHONE}
        sources = tracked(
            "apps/device-gateway/cyclone_device_gateway/**/*.py",
            "apps/glass/src/**/*.ts",
            "apps/mobile/app/src/main/**/*.kt",
        )
        copies = [p.relative_to(ROOT).as_posix() for p in sources if p not in homes and RULE.search(p.read_text(encoding="utf-8"))]
        self.assertEqual([], copies, "import the runtime's one rule instead of copying it")

    def test_the_gateway_rule_refuses_plurals_and_keeps_ordinary_text(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("v5_contract_probe", GATEWAY)
        assert spec and spec.loader
        source = GATEWAY.read_text(encoding="utf-8")
        pattern = re.search(r'INLINE_SECRET = re\.compile\(\s*r"([^"]+)"', source).group(1)
        rule = re.compile(pattern)
        for text in ("password: x", "Passwords = x", "api_keys: x", "typed_text=x", "credentials : x"):
            self.assertTrue(rule.search(text), text)
        for text in ("Open Settings", "Pinned tabs", "reset my password later"):
            self.assertIsNone(rule.search(text), text)


if __name__ == "__main__":
    unittest.main()
