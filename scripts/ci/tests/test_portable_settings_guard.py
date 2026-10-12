"""Plan 57 P2 (alpha.120): every settings file Cyclone keeps is classified, and what travels between profiles stays narrow.

- Every `getSharedPreferences` file in the app is in `PortableSettings.table` (CARRY, PER_PROFILE or NEVER). A new
  file fails this guard until someone decides where it belongs.
- Secrets, pairing and sessions are NEVER; the AI settings travel only once, at setup.
- Carried files (Market installs, owner skills, app manuals) are fixed paths, merged without deleting.
- Cornerstone apps: Cyclone Cloak is found by its connector id, never a guessed package; the owner's marks are set only
  from Cyclone's own Profiles screen.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
WS = APP / "runtime/workspaces"

CALL = re.compile(r'getSharedPreferences\(\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_.]*))\s*,')
# Call sites that open files by a variable taken from the table itself.
DYNAMIC = {"ProfileCarry.kt"}


def sources() -> dict[Path, str]:
    return {p: p.read_text(encoding="utf-8") for p in APP.rglob("*.kt")}


def constant(body: str, name: str) -> str | None:
    found = re.search(r'(?:const\s+)?val\s+' + re.escape(name) + r'\s*(?::\s*String)?\s*=\s*"([^"]+)"', body)
    return found.group(1) if found else None


def used_files() -> dict[str, set[str]]:
    all_sources = sources()
    by_object = {}
    for path, body in all_sources.items():
        for name in re.findall(r'(?:object|class)\s+([A-Za-z_][A-Za-z0-9_]*)', body):
            by_object.setdefault(name, []).append(body)
    used: dict[str, set[str]] = {}
    for path, body in all_sources.items():
        for literal, ident in CALL.findall(body):
            if literal:
                name = literal
            else:
                parts = ident.split(".")
                name = constant(body, parts[-1]) if len(parts) == 1 else None
                if name is None and len(parts) > 1:
                    name = next((c for b in by_object.get(parts[-2], []) if (c := constant(b, parts[-1]))), None)
                if name is None:
                    assert path.name in DYNAMIC, (path, ident)
                    continue
            used.setdefault(name, set()).add(path.name)
    return used


def table() -> dict[str, str]:
    body = (WS / "PortableSettings.kt").read_text(encoding="utf-8")
    rows = re.findall(r'\b(carry|mine|never)\("([a-z0-9_]+)"', body)
    kinds = {"carry": "CARRY", "mine": "PER_PROFILE", "never": "NEVER"}
    names = [name for _, name in rows]
    assert len(names) == len(set(names)), "a settings file is classified twice"
    return {name: kinds[kind] for kind, name in rows}


def test_every_settings_file_is_classified():
    used = used_files()
    classified = table()
    missing = sorted(set(used) - set(classified))
    assert not missing, f"Classify these in PortableSettings.table: {missing} (used in {[sorted(used[m]) for m in missing]})"
    assert len(used) >= 30, used


def test_secrets_pairing_and_sessions_never_travel():
    classified = table()
    for name in ("cyclone_vault_secrets_v1", "cyclone_ai_secrets", "cyclone_codes", "cyclone_sealed_leases",
                 "cyclone_gateway_trust_v33", "cyclone_pc_gateway_v293", "cyclone_desktop_gateway_v1"):
        assert classified[name] == "NEVER", name
    assert classified["cyclone_ai"] == "PER_PROFILE"
    for name, kind in classified.items():
        if kind == "CARRY":
            assert not re.search(r"secret|vault|code|lease|gateway|session|token|key", name), name
    rules = (WS / "CarryRules.kt").read_text(encoding="utf-8")
    assert "PortableSettings.carried" in rules
    assert "MindMemory.looksSecret" in rules[rules.index("fun carriesSetting("):rules.index("const val ID_ARRAY_MAX_CHARS")]


def test_carried_files_are_fixed_and_merged_without_deleting():
    files = (WS / "PortableFiles.kt").read_text(encoding="utf-8")
    paths = set(re.findall(r'const val [A-Z_]+ = "([^"]+/[^"]+)"', files))
    assert paths == {
        "Cyclone Brain/Marketplace/installed.json",
        "Cyclone Brain/Marketplace/owner-skills.json",
        "Cyclone Brain/Marketplace/owner-skills-anchors.json",
        "manual/dictionaries",
    }, paths
    assert "MindMemory.looksSecret" in files
    carry = (WS / "ProfileCarry.kt").read_text(encoding="utf-8")
    take_in = carry[carry.index("private fun applyFiles("):]
    assert ".delete(" not in take_in and "deleteRecursively" not in take_in
    assert "PortableFiles.manualsToAdd(" in take_in


def test_cornerstones_are_found_honestly_and_marked_only_by_the_owner():
    corner = (WS / "ProfileCornerstones.kt").read_text(encoding="utf-8")
    assert 'CLOAK_CONNECTOR_ID = "cyclone-cloak"' in corner
    assert "ConnectorDiscovery.discover(context)" in corner
    assert not re.search(r'"[a-z0-9_.]*cloak[a-z0-9_.]*\.[a-z0-9_.]+"', corner), "Cloak is found by its connector id"
    callers = {p.name for p, body in sources().items() if "ProfileCornerstones.setMarked(" in body}
    assert callers == {"ProfileCornerstonesUi.kt"}, callers
    for folder in ("mind", "gateway", "agent", "ai"):
        for path in (APP / folder).rglob("*.kt"):
            body = path.read_text(encoding="utf-8")
            assert "ProfileCornerstones" not in body and "ProfileInventoryStore" not in body, path
    bootstrap = (WS / "ProfileBootstrapRuntime.kt").read_text(encoding="utf-8")
    prepare = bootstrap[bootstrap.index("private fun prepareInternal("):bootstrap.index("private fun rootFromTarget(")]
    # Every app installed into a profile comes from the resolved cornerstones.
    assert prepare.count('"install-existing"') == 1 and "items.forEach { item ->" in prepare
    assert "if (item.required) installed.getOrThrow()" in prepare
