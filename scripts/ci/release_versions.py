#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[2]
METADATA = ROOT / "release" / "version.toml"


def read_text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def extract(pattern: str, text: str, label: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise ValueError(f"Could not read {label}")
    return match.group(1)


def collect() -> dict[str, str | int]:
    metadata = tomllib.loads(METADATA.read_text(encoding="utf-8"))
    product = str(metadata["product_version"])
    python_version = str(metadata["python_version"])
    version_code = int(metadata["android_version_code"])

    gradle = read_text("apps/mobile/app/build.gradle.kts")
    gateway = read_text("apps/device-gateway/pyproject.toml")
    mcp = read_text("tools/codex-phone-mcp/pyproject.toml")
    agent_mcp = read_text("tools/cyclone-agent-mcp/pyproject.toml")
    package_json = read_text("apps/pc-companion/package.json")
    package_lock = read_text("apps/pc-companion/package-lock.json")
    cargo = read_text("apps/pc-companion/src-tauri/Cargo.toml")
    tauri = read_text("apps/pc-companion/src-tauri/tauri.conf.json")

    return {
        "product": product,
        "python": python_version,
        "androidVersionName": extract(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, "Android versionName"),
        "androidVersionCode": int(extract(r'^\s*versionCode\s*=\s*(\d+)', gradle, "Android versionCode")),
        "gatewayPython": extract(r'^version\s*=\s*"([^"]+)"', gateway, "gateway package version"),
        "mcpPython": extract(r'^version\s*=\s*"([^"]+)"', mcp, "MCP package version"),
        "agentMcpPython": extract(r'^version\s*=\s*"([^"]+)"', agent_mcp, "agent MCP package version"),
        "pcPackage": extract(r'^\s*"version"\s*:\s*"([^"]+)"', package_json, "PC package version"),
        "pcPackageLock": extract(r'^\s*"version"\s*:\s*"([^"]+)"', package_lock, "PC lockfile version"),
        "pcCargo": extract(r'^version\s*=\s*"([^"]+)"', cargo, "PC Cargo version"),
        "pcTauri": extract(r'^\s*"version"\s*:\s*"([^"]+)"', tauri, "Tauri version"),
        "expectedAndroidVersionCode": version_code,
    }


def check(values: dict[str, str | int]) -> list[str]:
    product = str(values["product"])
    python_version = str(values["python"])
    expected_code = int(values["expectedAndroidVersionCode"])
    errors: list[str] = []
    product_fields = ("androidVersionName", "pcPackage", "pcPackageLock", "pcCargo", "pcTauri")
    python_fields = ("gatewayPython", "mcpPython", "agentMcpPython")
    for field in product_fields:
        if values[field] != product:
            errors.append(f"{field}={values[field]!r} expected {product!r}")
    for field in python_fields:
        if values[field] != python_version:
            errors.append(f"{field}={values[field]!r} expected {python_version!r}")
    if int(values["androidVersionCode"]) != expected_code:
        errors.append(
            f"androidVersionCode={values['androidVersionCode']} expected {expected_code}"
        )
    if expected_code <= 35:
        errors.append("Cyclone 3.5 distributed Android builds require versionCode > 35")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Cyclone release component versions")
    parser.add_argument("--check", action="store_true", help="fail when component versions drift")
    args = parser.parse_args()
    values = collect()
    errors = check(values)
    for key, value in values.items():
        print(f"{key}={value}")
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1 if args.check else 0
    print("Cyclone release version metadata is coherent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
