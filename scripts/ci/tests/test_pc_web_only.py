"""Web-only PC guard (plan 31, alpha.47).

Cyclone for Windows is the runtime + Glass + the `cyclone` command, installed per user from one verified zip. These
rules keep it that way: no desktop window is built, every package and update is checked against the release
manifest, the PC routes need the bearer, and the installer never closes the owner's shell or touches other programs.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
PUBLISH = ROOT / ".github/workflows/v5-publish.yml"
INSTALL = ROOT / "packaging/pc/install.ps1"
BUILD = ROOT / "scripts/pc/build-pc-package.ps1"
PC_API = ROOT / "apps/device-gateway/cyclone_device_gateway/pc/api.py"
SHIM = ROOT / "apps/device-gateway/cyclone_device_gateway/terminal/install.py"
UPDATER = ROOT / "apps/device-gateway/cyclone_device_gateway/terminal/updater.py"


class WebOnlyPcGuards(unittest.TestCase):
    def test_the_publisher_builds_the_package_not_a_desktop_window(self):
        text = PUBLISH.read_text(encoding="utf-8")
        self.assertNotIn("tauri build", text)
        self.assertIn("scripts/pc/build-pc-package.ps1", text)
        self.assertIn("needs: pc-package", text)
        # The manifest lists the package and the installer, which `cyclone` and install.ps1 check against.
        self.assertRegex(text, r"for p in \(apk, glass, pc, installer\)")
        self.assertIn(r"gateway\.env$|fleet\.dpapi$", text)

    def test_the_package_build_proves_install_and_serving_on_windows(self):
        text = BUILD.read_text(encoding="utf-8")
        for needed in ("install.ps1') -Zip", "version 2>&1", "/glass/", "/v1/pc/welcome", "answered without the bearer"):
            self.assertIn(needed, text)
        self.assertIn("'gateway.env', 'fleet.dpapi'", text)
        self.assertNotIn("CycloneLivePhone.spec", text)

    def test_the_installer_verifies_and_never_exits_the_owners_shell(self):
        text = INSTALL.read_text(encoding="utf-8")
        self.assertIn("Get-FileHash -Algorithm SHA256", text)
        self.assertIn("release-manifest.json", text)
        exits = [line.strip() for line in text.splitlines() if re.search(r"(^\s*|[{;]\s*)exit\b", line) and not line.strip().startswith("#")]
        self.assertEqual(exits, ["if ($PSCommandPath) { exit ([int](-not $ok)) }"])
        # Only Cyclone's own adb/fastboot are stopped.
        self.assertIn('Where-Object { $_.Path -like "$Root\\android-platform-tools\\*" }', text)
        self.assertNotRegex(text, r"(?i)HKLM:|RunAs|Set-ExecutionPolicy")

    def test_every_pc_route_needs_the_bearer(self):
        text = PC_API.read_text(encoding="utf-8")
        routes = re.findall(r"@router\.(get|post|put|delete)\((.*)\)\s*$", text, re.M)
        self.assertGreaterEqual(len(routes), 15)
        for method, args in routes:
            self.assertIn("dependencies=[Depends(auth)]", args, f"{method} {args}")

    def test_updates_are_verified_and_the_shim_ends_before_replacing_itself(self):
        updater = UPDATER.read_text(encoding="utf-8")
        self.assertIn("expected_sha256(manifest, release.package_name)", updater)
        self.assertIn("if actual != expected", updater)
        self.assertIn("(goto) 2>nul & powershell.exe", SHIM.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
