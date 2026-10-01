"""Conformance checker: does a running plugin follow ``cyclone.ports/1``?

    python -m cyclone_ports.conformance http://127.0.0.1:8771 --secret dev-secret

Checks the manifest, /health, signature enforcement (none, wrong secret, stale timestamp), unknown ports, a sample
envelope on every out port it serves, a malformed envelope, and an await + cancel on every in port. Exit code 0 when
every required check passes. The real Port Hub runs the same checks when the owner adds a plugin.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

from .catalog import CATALOG, CONTRACT
from .devhub import DevHub
from .sdk import CONTRACT_HEADER, SIGNATURE_HEADER, sign, validate_manifest

SAMPLE_RUN = {"runId": "run_conformance", "taskId": "task_conformance", "rowId": "row_conformance",
              "app": "com.example.app"}
SAMPLE_DATA: dict[str, dict[str, Any]] = {
    "run.event": {"stage": "started"},
    "screen.shot": {"width": 1, "height": 1},
    "account.fields": {"fields": {"name": "Sam Example", "dateOfBirth": "1999-04-02", "email": "sam@example.com"}},
    "page.text": {"text": "Choose a username"},
    "file.out": {"name": "receipt.png"},
    "log.line": {"text": "conformance check"},
}
SAMPLE_MATCH: dict[str, dict[str, Any]] = {
    "code.in": {"from": "Example", "pattern": r"\b\d{6}\b"},
    "link.in": {"from": "Example"},
    "file.in": {"kind": "image"},
    "value.in": {"ask": "a caption"},
}


@dataclass
class Check:
    name: str
    ok: bool
    detail: str = ""
    required: bool = True


def _request(url: str, body: bytes | None = None, headers: dict[str, str] | None = None) -> tuple[int, Any]:
    request = urllib.request.Request(url, data=body, method="POST" if body is not None else "GET",
                                     headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        return error.code, {}
    except (urllib.error.URLError, OSError, ValueError) as error:
        return 0, {"error": str(error)}


def _signed(url: str, secret: str, body: dict[str, Any], t: int | None = None) -> int:
    raw = json.dumps(body).encode()
    return _request(url, raw, {"Content-Type": "application/json", CONTRACT_HEADER: CONTRACT,
                               SIGNATURE_HEADER: sign(secret, raw, t)})[0]


def check_plugin(endpoint: str, secret: str) -> list[Check]:
    endpoint = endpoint.rstrip("/")
    checks: list[Check] = []
    status, manifest = _request(endpoint + "/cyclone-plugin.json")
    if status != 200:
        return [Check("manifest is served at /cyclone-plugin.json", False, f"HTTP {status}")]
    problems = validate_manifest(manifest)
    checks.append(Check("manifest is valid", not problems, "; ".join(problems)))
    if problems:
        return checks
    checks.append(Check("manifest endpoint matches", manifest["endpoint"].rstrip("/") == endpoint,
                        f"manifest says {manifest['endpoint']}", required=False))
    status, health = _request(endpoint + "/health")
    checks.append(Check("/health answers 200 with ok", status == 200 and health.get("ok") is True, f"HTTP {status}"))

    served = [s["port"] for s in manifest["serves"]]
    first = served[0]
    probe = json.dumps({"v": 1}).encode()
    status, _ = _request(f"{endpoint}/ports/{first}", probe, {"Content-Type": "application/json"})
    checks.append(Check("refuses an unsigned request (401)", status == 401, f"HTTP {status}"))
    status = _signed(f"{endpoint}/ports/{first}", secret + "-wrong", {"v": 1})
    checks.append(Check("refuses a wrong secret (401)", status == 401, f"HTTP {status}"))
    status = _signed(f"{endpoint}/ports/{first}", secret, {"v": 1}, int(time.time()) - 3600)
    checks.append(Check("refuses a stale signature (401)", status == 401, f"HTTP {status}"))
    unserved = next(p for p in CATALOG if p not in served and CATALOG[p].plugin_served)
    status = _signed(f"{endpoint}/ports/{unserved}", secret, {"v": 1})
    checks.append(Check("answers 404 for a port it does not serve", status == 404, f"{unserved}: HTTP {status}"))

    hub = DevHub(secret).start()
    try:
        for port in served:
            spec = CATALOG[port]
            if spec.way == "out":
                hub.bind(port, endpoint)
                status = hub.emit(SAMPLE_RUN, port, SAMPLE_DATA.get(port), page_key="conformance:page")
                checks.append(Check(f"{port}: accepts a sample envelope (2xx)", 200 <= status < 300, f"HTTP {status}"))
                bad = {"v": 1, "runId": SAMPLE_RUN["runId"], "port": port, "way": "in"}
                status = _signed(f"{endpoint}/ports/{port}", secret, bad)
                checks.append(Check(f"{port}: rejects a malformed envelope (4xx)", 400 <= status < 500,
                                    f"HTTP {status}", required=False))
            else:
                request = {"v": 1, **SAMPLE_RUN, "port": port, "awaitId": "aw_conformance",
                           "match": SAMPLE_MATCH.get(port, {}), "timeoutS": 5,
                           "deliverUrl": f"{hub.url}/v1/ports/{SAMPLE_RUN['runId']}/{port}/deliver",
                           "token": "conformance-token-not-valid"}
                status = _signed(f"{endpoint}/ports/{port}/await", secret, request)
                checks.append(Check(f"{port}: accepts an await (2xx)", 200 <= status < 300, f"HTTP {status}"))
                status = _signed(f"{endpoint}/ports/{port}/cancel", secret,
                                 {"v": 1, "runId": SAMPLE_RUN["runId"], "port": port, "awaitId": "aw_conformance",
                                  "reason": "conformance"})
                checks.append(Check(f"{port}: accepts a cancel (2xx)", 200 <= status < 300, f"HTTP {status}"))
    finally:
        hub.stop()
    return checks


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cyclone-ports-check", description=__doc__.splitlines()[0])
    parser.add_argument("endpoint", help="the plugin's base URL, e.g. http://127.0.0.1:8771")
    parser.add_argument("--secret", default=os.environ.get("CYCLONE_PLUGIN_SECRET", ""))
    args = parser.parse_args(argv)
    if not args.secret:
        parser.error("--secret or CYCLONE_PLUGIN_SECRET is required")
    checks = check_plugin(args.endpoint, args.secret)
    for c in checks:
        mark = "PASS" if c.ok else ("FAIL" if c.required else "WARN")
        print(f"{mark}  {c.name}" + (f"  ({c.detail})" if c.detail and not c.ok else ""))
    failed = [c for c in checks if c.required and not c.ok]
    print("\nall required checks passed" if not failed else f"\n{len(failed)} required check(s) failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
