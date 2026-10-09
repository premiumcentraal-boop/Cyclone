"""Plan 57 P0 (alpha.118): truthful profile errors, profile room on rooted phones and the debug file stay inside fixed
limits.

- Profile room touches one system property (`fw.max_users`) and one module folder (`/data/adb/modules/cyclone_profiles`),
  only through fixed, shape-checked commands, with limits 4..16.
- Raising the limit, cleaning up users and restoring are owner buttons in Cyclone's own UI: no Mind tool, Instant
  command, gateway route or MCP reaches them.
- The debug file never copies a connector's own data (`ext` values) and redacts every step it holds.
- Setup messages name the profile being made; none says "Profile B" by itself.

Plan 57 P1 (alpha.119): the switch's way back stays fixed and narrow.

- The dead-man return is one fixed command built from numbers and a hex nonce, armed only by the owner's switch, and
  only after the target's Cyclone says it is listening.
- Root in a new profile is proven from that profile's own Cyclone, not by nested su as another app.
- Android's user switcher is only ever turned on (never off), from an owner button.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile"
WS = APP / "runtime/workspaces"


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def kotlin_sources() -> dict[Path, str]:
    return {p: text(p) for p in APP.rglob("*.kt")}


def test_only_the_cyclone_profiles_module_folder_is_written_under_data_adb():
    for path, body in kotlin_sources().items():
        for literal in re.findall(r'"([^"\n]*?/data/adb/modules[^"\n]*)"', body):
            assert path.name == "ProfileSetupPlan.kt", (path, literal)
            assert literal in {"/data/adb/modules/cyclone_profiles", "/data/adb/modules/cyclone_profiles.new"}, literal
    plan = text(WS / "ProfileSetupPlan.kt")
    assert 'const val ROOM_MODULE = "/data/adb/modules/cyclone_profiles"' in plan
    assert 'const val ROOM_MODULE_STAGING = "/data/adb/modules/cyclone_profiles.new"' in plan


def test_resetprop_only_ever_sets_the_user_limit():
    plan = text(WS / "ProfileSetupPlan.kt")
    assert 'const val ROOM_PROPERTY = "fw.max_users"' in plan
    assert "val ROOM_LIMITS = 4..16" in plan
    # Every resetprop shape check pins the property.
    set_shape = plan[plan.index("ProfileSetupOperation.ROOM_SET_PROP ->"):plan.index("ProfileSetupOperation.ROOM_RESET_PROP ->")]
    assert "tokens[m.resetprop.size] == ROOM_PROPERTY" in set_shape and "in ROOM_LIMITS" in set_shape
    reset_shape = plan[plan.index("ProfileSetupOperation.ROOM_RESET_PROP ->"):plan.index("ProfileSetupOperation.ROOM_CLEAR_MODULE ->")]
    assert 'listOf("--delete", ROOM_PROPERTY)' in reset_shape and "tail[0] == ROOM_PROPERTY" in reset_shape
    for path, body in kotlin_sources().items():
        if "resetprop" in body and path.name not in {"ProfileSetupPlan.kt", "ProfileRoom.kt"}:
            # Comments elsewhere are fine; code that runs resetprop is not.
            code = "\n".join(line for line in body.splitlines() if not line.strip().startswith(("*", "//", "/*")))
            assert "resetprop" not in code, path


def test_profile_room_and_clean_up_are_owner_buttons_only():
    callers = {
        "ProfileRoom.allow(": set(),
        "ProfileRoom.restoreDefault(": set(),
        "ProfileLifecycle.cleanUpUnfinished(": set(),
    }
    for path, body in kotlin_sources().items():
        for call in callers:
            if call in body:
                callers[call].add(path.name)
    for call, files in callers.items():
        assert files <= {"ProfileProblemUi.kt"}, (call, files)
    for tools in (ROOT / "tools/codex-phone-mcp", ROOT / "tools/cyclone-agent-mcp", ROOT / "apps/device-gateway"):
        for path in tools.rglob("*.py"):
            body = text(path)
            assert "fw.max_users" not in body and "cyclone_profiles" not in body, path
    mind = "\n".join(text(p) for p in (APP / "mind").rglob("*.kt"))
    assert "ProfileRoom" not in mind and "cleanUpUnfinished" not in mind


def test_the_debug_file_never_copies_connector_data_and_redacts_steps():
    report = text(WS / "ProfileDebugReport.kt")
    assert 'put("extConnectors", JSONArray(r.ext.keys.sorted()))' in report
    assert "r.ext.values" not in report and "ext[" not in report
    assert "ProfileDebugRedaction.text(it.output" in report
    journal = text(WS / "ProfileStepJournal.kt")
    assert "ProfileDebugRedaction.text(output, OUTPUT_LIMIT)" in journal
    bootstrap = text(WS / "ProfileBootstrapRuntime.kt")
    # The bootstrap's stdin can carry sealed bundles: only the command and Android's answer are journaled.
    assert 'ProfileStepJournal.record("BOOTSTRAP", command,' in bootstrap and "input" not in bootstrap.split('ProfileStepJournal.record("BOOTSTRAP"')[1].split(")")[0]


def test_setup_messages_name_the_profile():
    for name in ("ProfileSetupRuntime.kt", "ProfileProvisioningContract.kt"):
        body = text(WS / name)
        assert "Profile B" not in body, name
    contract = text(WS / "ProfileProvisioningContract.kt")
    # Limits are read before a duplicate, so Android's "Maximum number of that type already exists" is a limit.
    create = contract[contract.index("operation in setOf(ProfileSetupOperation.CREATE_MANAGED_PROFILE"):]
    assert create.index("createLimit(lower)") < create.index('lower.contains("already exists")')


def test_the_debug_file_has_its_own_narrow_sharer():
    import xml.etree.ElementTree as ET
    paths = ET.parse(ROOT / "apps/mobile/app/src/main/res/xml/profile_debug_paths.xml").getroot()
    assert [(p.tag, p.get("path")) for p in paths] == [("cache-path", "profile-debug/")]
    manifest = text(ROOT / "apps/mobile/app/src/main/AndroidManifest.xml")
    block = manifest[manifest.index(".runtime.workspaces.ProfileDebugFileProvider"):]
    block = block[:block.index("</provider>")]
    assert 'android:exported="false"' in block and "${applicationId}.profile-debug" in block
    ui = text(APP / "ui/ProfileProblemUi.kt")
    assert "ProfileDebugReport.AUTHORITY_SUFFIX" in ui and "setup-helper" not in ui


def test_the_dead_man_return_is_fixed_and_armed_only_by_the_owner_switch():
    switch = text(WS / "ProfileSwitch.kt")
    body = switch[switch.index("fun returnCommand("):switch.index("fun rootGuidance(")]
    assert "require(source >= 0 && target >= 0 && source != target && validNonce(nonce) && seconds in 10..120)" in body
    assert 'Regex("[a-f0-9]{32}")' in switch
    # Its only interpolations are numbers and the validated hello path.
    assert set(re.findall(r"\$\{?([A-Za-z_]+)", body)) <= {"seconds", "target", "hello", "source"}, body
    callers = {path.name for path, code in kotlin_sources().items() if "armReturn(" in code}
    # The owner's switch on the phone, and (alpha.122) the owner's switch from the PC (bearer route, ProfileApps).
    assert callers == {"ProfileBootstrapRuntime.kt", "ProfileSetupRuntime.kt", "ProfileApps.kt"}, callers
    apps = text(WS / "ProfileApps.kt")
    way_back = apps[apps.index("internal var armWayBack"):]
    assert way_back.index("requestHello(to, nonce)") < way_back.index("armReturn(from, to, nonce)")
    bootstrap = text(WS / "ProfileBootstrapRuntime.kt")
    hello = bootstrap[bootstrap.index("fun requestHello("):bootstrap.index("fun helloArrived(")]
    assert "check(listening)" in hello
    setup = text(WS / "ProfileSetupRuntime.kt")
    armed = setup[setup.index("ProfileBootstrapRuntime.requestHello(user, nonce)"):]
    assert armed.index("requestHello") < armed.index("armReturn")
    # Only Cyclone's own screens can switch: never the Mind, gateway or MCP.
    for path, code in kotlin_sources().items():
        if "openProfile(" in code and path.name != "ProfileSetupRuntime.kt":
            assert "/mind/" not in str(path) and "/gateway/" not in str(path), path
    for tools in (ROOT / "tools/codex-phone-mcp", ROOT / "tools/cyclone-agent-mcp", ROOT / "apps/device-gateway"):
        for path in tools.rglob("*.py"):
            code = text(path)
            assert "switch-user" not in code and "user_switcher_enabled" not in code, path


def test_root_in_a_new_profile_is_proven_from_its_own_cyclone():
    bootstrap = text(WS / "ProfileBootstrapRuntime.kt")
    # Plan 57 P2: the result is recorded for "Profile C has", then still enforced.
    assert "val rooted = rootFromTarget(target)" in bootstrap and "check(rooted) { ProfileSwitch.rootGuidance(manager, label) }" in bootstrap
    assert '"su", "$targetUid"' not in bootstrap
    service = text(WS / "ProfileBootstrapService.kt")
    check = service[service.index("private fun checkRoot("):]
    assert 'ProcessBuilder("su", "-c", "/system/bin/id")' in check


def test_the_user_switcher_is_only_turned_on_from_an_owner_button():
    plan = text(WS / "ProfileSetupPlan.kt")
    assert '"/system/bin/settings", "put", "global", "user_switcher_enabled", "1")' in plan
    assert '"user_switcher_enabled", "0"' not in plan
    callers = {path.name for path, code in kotlin_sources().items() if "ProfileSetupRuntime.enableUserSwitcher(" in code}
    assert callers == {"ProfileProblemUi.kt"}, callers
