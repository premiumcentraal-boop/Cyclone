from __future__ import annotations
import importlib.util, json
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]

def load_script(name: str):
    path = REPO / "scripts" / "pc-companion" / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module

def test_sidecar_lock_is_exact_and_specs_exist():
    lock = json.loads((REPO / "packaging/pc-companion/sidecar-build.lock.json").read_text())
    assert lock["python"] == "3.13.7"
    assert lock["pyinstaller"] == "6.22.2"
    assert lock["mcp"] == "2.0.0"
    assert lock["websockets"] == "15.0.1"
    assert {x["name"] for x in lock["sidecars"]} == {"CyclonePCRuntime.exe", "CycloneAgentMCP.exe", "CycloneLivePhone.exe"}
    assert all((REPO / x["spec"]).is_file() for x in lock["sidecars"])

def test_frozen_gateway_declares_websocket_runtime():
    gateway = (REPO / "apps/device-gateway/pyproject.toml").read_text()
    build = (REPO / "scripts/pc/build-pc-package.ps1").read_text()
    lock = json.loads((REPO / "packaging/pc-companion/sidecar-build.lock.json").read_text())
    assert f'"websockets=={lock["websockets"]}"' in gateway
    assert build.count("PyInstaller --clean --noconfirm") == 2  # the runtime and the agent MCP (plan 31)

def test_pinned_scrcpy_notice_is_deterministic_and_complete(tmp_path):
    script = load_script("generate-third-party-notices.py")
    data = script.load_lock(REPO / "packaging/pc-companion/third-party-binaries.lock.json")
    a = script.render(data)
    b = script.render(data)
    assert a == b
    assert "scrcpy-server 4.0" in a
    assert "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a" in a
    assert "Apache-2.0" in a
    assert "latest" not in a.lower()

def test_invalid_unpinned_third_party_binary_is_rejected(tmp_path):
    script = load_script("generate-third-party-notices.py")
    p = tmp_path / "lock.json"
    p.write_text(json.dumps({"schema_version": 1, "binaries": [{"name":"scrcpy","version":"latest"}]}))
    try:
        script.load_lock(p)
    except ValueError:
        pass
    else:
        raise AssertionError("unpinned incomplete binary was accepted")

def test_pc_ci_tests_the_stack_and_builds_the_real_package():
    text = (REPO / ".github/workflows/pc-ci.yml").read_text()
    assert "runs-on: windows-latest" in text
    assert "tools/cyclone-agent-mcp/tests" in text
    assert "tools/cyclone-ports-sdk/tests" in text
    assert "scripts/pc/build-pc-package.ps1" in text
    assert "actions/upload-artifact" in text
    # The retired desktop window is not built.
    assert "tauri build" not in text
    assert "apps/pc-companion/package" not in text
