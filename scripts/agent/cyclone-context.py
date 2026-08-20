#!/usr/bin/env python3
"""Dependency-free Cyclone repository context for coding agents."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    p = ROOT / path
    return p.read_text(encoding="utf-8") if p.exists() else ""


def git(*args: str) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(ROOT), *args], stderr=subprocess.DEVNULL, text=True
        ).strip()
    except Exception:
        return ""


def match(pattern: str, text: str) -> str:
    m = re.search(pattern, text, re.MULTILINE)
    return m.group(1).strip() if m else ""


def pyproject_version(path: str) -> str:
    return match(r'^version\s*=\s*["\']([^"\']+)["\']', read(path))


def build_context() -> dict:
    gradle = read("apps/mobile/app/build.gradle.kts")
    android_version = match(r'versionName\s*=\s*"([^"]+)"', gradle)
    version_code = match(r"versionCode\s*=\s*(\d+)", gradle)
    package = match(r'applicationId\s*=\s*"([^"]+)"', gradle)
    pc_version = pyproject_version("apps/device-gateway/pyproject.toml")
    mcp_version = pyproject_version("tools/codex-phone-mcp/pyproject.toml")

    normalized_android = android_version
    if "-v" in normalized_android:
        normalized_android = normalized_android.rsplit("-v", 1)[-1]

    versions = {
        "android_version_name": android_version,
        "android_product_version": normalized_android,
        "android_version_code": int(version_code) if version_code else None,
        "pc_gateway": pc_version,
        "codex_mcp": mcp_version,
    }
    comparable = [v for v in (normalized_android, pc_version, mcp_version) if v]
    consistent = len(set(comparable)) <= 1

    important = [
        "AGENTS.md",
        "docs/agent-system/README.md",
        "docs/agent-system/CURRENT_STATE.md",
        "docs/agent-system/ARCHITECTURE_AND_CONTRACTS.md",
        "apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolExecutor.kt",
        "apps/mobile/app/src/main/java/com/cyclone/mobile/CycloneAccessibilityService.kt",
        "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway",
        "apps/device-gateway",
        "tools/codex-phone-mcp",
    ]

    dirty = git("status", "--porcelain")
    changed_paths = [line[3:] for line in dirty.splitlines() if len(line) > 3]
    v3_services = {
        "capability_registry": "apps/mobile/app/src/main/java/com/cyclone/mobile/platform/capability",
        "policy_governor": "apps/mobile/app/src/main/java/com/cyclone/mobile/policy",
        "module_supervisor": "apps/mobile/app/src/main/java/com/cyclone/mobile/platform/modules",
        "memory": "apps/mobile/app/src/main/java/com/cyclone/mobile/brain/memory",
        "graph_v2": "apps/mobile/app/src/main/java/com/cyclone/mobile/brain/graphv2",
        "routine_capsules": "apps/mobile/app/src/main/java/com/cyclone/mobile/automation/capsule",
        "context_ledger": "apps/mobile/app/src/main/java/com/cyclone/mobile/observability",
        "vision_router": "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/vision",
        "runtime_updater": "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/update",
        "recovery": "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/recovery",
        "gateway": "apps/device-gateway/cyclone_device_gateway/capabilities",
    }
    android_blast_radius = any(
        path.startswith(("apps/mobile/", "third_party/mobilerun-portal", ".github/workflows/mobile"))
        for path in changed_paths
    )
    return {
        "repo_root": str(ROOT),
        "git": {
            "branch": git("branch", "--show-current"),
            "sha": git("rev-parse", "HEAD"),
            "dirty": bool(dirty),
            "changed_paths": changed_paths,
        },
        "mobile": {
            "package": package,
            "launcher": ".MainActivity",
            "versions": versions,
            "cross_component_version_consistent": consistent,
            "surfaces": ["Home", "Teach", "AI", "Automations", "Brain", "Settings"],
        },
        "mission": "observe -> understand -> act -> verify -> learn -> reuse -> self-heal",
        "infrastructure_v3": {
            "services": {
                name: {"path": path, "present": (ROOT / path).exists()}
                for name, path in v3_services.items()
            },
            "owners": "docs/agent-system/infrastructure-v3/OWNERSHIP.md",
            "contracts": "docs/agent-system/ARCHITECTURE_AND_CONTRACTS.md",
            "health": "service-local diagnostics; Recovery owns runtime promotion/rollback",
            "test_count": len(list((ROOT / "apps/mobile/app/src/test").rglob("*Test.kt"))),
            "blast_radius": "android_apk" if android_blast_radius else "non_android_or_docs",
            "handoff_fields": [
                "base/head SHA", "changed/owned paths", "contracts", "tests/results",
                "health/failures", "blast radius", "CI/physical status",
            ],
        },
        "canonical_docs": [
            "AGENTS.md",
            "docs/agent-system/README.md",
            "docs/agent-system/CURRENT_STATE.md",
            "docs/agent-system/PROJECT_VISION.md",
            "docs/agent-system/ARCHITECTURE_AND_CONTRACTS.md",
            "docs/agent-system/MULTI_AGENT_PROTOCOL.md",
            "docs/agent-system/FAST_RELEASE_PLAYBOOK.md",
            "docs/agent-system/AUTONOMY_ROADMAP.md",
        ],
        "important_paths": {path: (ROOT / path).exists() for path in important},
        "warnings": ([] if consistent else ["Android/PC/MCP product versions do not match"]),
    }


def as_markdown(ctx: dict) -> str:
    g = ctx["git"]
    v = ctx["mobile"]["versions"]
    lines = [
        "# Cyclone agent context",
        "",
        f"- Branch: `{g['branch'] or 'unknown'}`",
        f"- SHA: `{g['sha'] or 'unknown'}`",
        f"- Dirty checkout: `{g['dirty']}`",
        f"- Android package: `{ctx['mobile']['package'] or 'unknown'}`",
        f"- Android versionName: `{v['android_version_name'] or 'unknown'}`",
        f"- Android versionCode: `{v['android_version_code']}`",
        f"- PC gateway version: `{v['pc_gateway'] or 'unknown'}`",
        f"- MCP version: `{v['codex_mcp'] or 'unknown'}`",
        f"- Cross-component product version match: `{ctx['mobile']['cross_component_version_consistent']}`",
        "- Mission loop: `observe → understand → act → verify → learn → reuse → self-heal`",
        "",
        "## Read first",
    ]
    lines += [f"- `{p}`" for p in ctx["canonical_docs"]]
    v3 = ctx["infrastructure_v3"]
    lines += [
        "",
        "## Infrastructure V3",
        f"- Services present: `{sum(1 for item in v3['services'].values() if item['present'])}/{len(v3['services'])}`",
        f"- Focused Kotlin test files: `{v3['test_count']}`",
        f"- Current blast radius: `{v3['blast_radius']}`",
        f"- Ownership: `{v3['owners']}`",
    ]
    if ctx["warnings"]:
        lines += ["", "## Warnings"] + [f"- {w}" for w in ctx["warnings"]]
    if g["changed_paths"]:
        lines += ["", "## Current changed paths"] + [f"- `{p}`" for p in g["changed_paths"]]
    return "\n".join(lines)


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--markdown", action="store_true", help="print compact Markdown instead of JSON")
    args = parser.parse_args()
    ctx = build_context()
    print(as_markdown(ctx) if args.markdown else json.dumps(ctx, indent=2))


if __name__ == "__main__":
    main()
