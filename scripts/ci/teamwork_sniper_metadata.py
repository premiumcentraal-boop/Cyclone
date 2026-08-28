#!/usr/bin/env python3
"""Identity metadata for the standalone Teamwork Sniper APK."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import tomllib

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_APP_ROOT = ROOT / "apps/teamwork-sniper"
RELEASE_METADATA = ROOT / "release/version.toml"
EXPECTED_APPLICATION_ID = "com.cyclone.teamworksniper"


def exactly_one(pattern: str, text: str, label: str) -> str:
    values = re.findall(pattern, text, flags=re.MULTILINE)
    if len(values) != 1:
        raise ValueError(f"expected exactly one {label} assignment, found {len(values)}")
    return values[0]


def expected_identity() -> tuple[str, int]:
    metadata = tomllib.loads(RELEASE_METADATA.read_text(encoding="utf-8"))
    components = metadata.get("components", {})
    return (
        str(components.get("teamwork_sniper", metadata["product_version"])),
        int(components.get("teamwork_sniper_android_version_code", 1)),
    )


def read_metadata(app_root: Path = DEFAULT_APP_ROOT) -> dict[str, str | int | bool]:
    gradle_path = app_root / "app/build.gradle.kts"
    if not gradle_path.is_file():
        return {"present": False}
    gradle = gradle_path.read_text(encoding="utf-8")
    version_name = exactly_one(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, "versionName")
    version_code = int(exactly_one(r"^\s*versionCode\s*=\s*([0-9]+)\s*$", gradle, "versionCode"))
    application_id = exactly_one(r'^\s*applicationId\s*=\s*"([^"]+)"\s*$', gradle, "applicationId")
    expected_version, expected_code = expected_identity()
    if application_id != EXPECTED_APPLICATION_ID:
        raise ValueError(f"unexpected applicationId: {application_id}")
    if version_name != expected_version:
        raise ValueError(f"unexpected versionName: {version_name}; expected {expected_version}")
    if version_code != expected_code:
        raise ValueError(f"unexpected versionCode: {version_code}; expected {expected_code}")
    safe_version = re.sub(r"[^0-9A-Za-z._-]+", "-", version_name).strip("-")
    return {
        "present": True,
        "version_name": version_name,
        "version_code": version_code,
        "application_id": application_id,
        "apk_name": f"Teamwork-Sniper-{safe_version}.apk",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=DEFAULT_APP_ROOT)
    parser.add_argument("--require-app", action="store_true")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    try:
        metadata = read_metadata(args.root)
        if args.require_app and not metadata["present"]:
            raise ValueError(f"Teamwork Sniper app missing at {args.root}")
    except (OSError, ValueError, tomllib.TOMLDecodeError) as error:
        parser.error(str(error))

    rendered = json.dumps(metadata, indent=2, sort_keys=True) + "\n"
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(rendered, encoding="utf-8")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"sniper_present={str(bool(metadata['present'])).lower()}\n")
            if metadata["present"]:
                for key in ("version_name", "version_code", "application_id", "apk_name"):
                    output.write(f"sniper_{key}={metadata[key]}\n")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
