"""`cyclone plugin …` (plan 50 §9): a thin client of the gateway's /v1/plugins routes, for owners who prefer a terminal.

    cyclone plugin list
    cyclone plugin add <github link | name> [--allow port,port] [--set key=value ...] [--yes]
    cyclone plugin update <name> [--yes]
    cyclone plugin remove <name> [--keep-data] [--yes]
    cyclone plugin logs <name>
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable

Http = Callable[[str, str, Any], tuple[int, Any]]


def http_with(base: str, token: str) -> Http:
    def call(method: str, path: str, body: Any = None) -> tuple[int, Any]:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(base.rstrip("/") + path, data=data, method=method,
                                         headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as resp:
                return resp.status, json.loads(resp.read() or b"null")
        except urllib.error.HTTPError as exc:
            try:
                return exc.code, json.loads(exc.read() or b"null")
            except ValueError:
                return exc.code, None
    return call


def parse(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="cyclone plugin", description="Install and manage Cyclone plugins.")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("list")
    add = sub.add_parser("add")
    add.add_argument("source")
    add.add_argument("--allow", default="", help="ports to allow, comma separated (default: the public ones)")
    add.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")
    add.add_argument("--yes", action="store_true")
    update = sub.add_parser("update")
    update.add_argument("name")
    update.add_argument("--yes", action="store_true")
    remove = sub.add_parser("remove")
    remove.add_argument("name")
    remove.add_argument("--keep-data", action="store_true")
    remove.add_argument("--yes", action="store_true")
    logs = sub.add_parser("logs")
    logs.add_argument("name")
    return parser.parse_args(argv)


def _message(body: Any) -> str:
    detail = body.get("detail") if isinstance(body, dict) else None
    return detail.get("message", "") if isinstance(detail, dict) else str(detail or body)


def _wait(http: Http, job: dict[str, Any], out: Callable[[str], None], sleep: Callable[[float], None]) -> dict[str, Any]:
    step = ""
    while job.get("state") == "running":
        if job.get("step") != step:
            step = job.get("step", "")
            out(f"  … {step}")
        sleep(0.5)
        status, job = http("GET", f"/v1/plugins/jobs/{urllib.parse.quote(job['id'])}", None)
        if status != 200:
            return {"state": "failed", "detail": _message(job)}
    return job


def _value(text: str) -> Any:
    try:
        return json.loads(text)
    except ValueError:
        return text


def run_plugin(argv: list[str], http: Http, out: Callable[[str], None], ask: Callable[[str], str],
               sleep: Callable[[float], None] = time.sleep) -> int:
    args = parse(argv)
    if args.action == "list":
        status, body = http("GET", "/v1/plugins", None)
        if status != 200:
            out(_message(body))
            return 1
        if not body["plugins"]:
            out("No plugins installed. Add one with: cyclone plugin add <github link>")
        for p in body["plugins"]:
            badge = "verified" if p["verified"] else "unverified"
            update = f" · update {p['latest']} available" if p.get("updateAvailable") else ""
            out(f"{p['name']} {p['version']} · {p['state']} · {badge}{update}")
        return 0
    if args.action == "logs":
        status, body = http("GET", f"/v1/plugins/{urllib.parse.quote(args.name)}/log", None)
        for line in (body or {}).get("lines", []) if status == 200 else [_message(body)]:
            out(line)
        return 0 if status == 200 else 1
    if args.action == "remove":
        if not args.yes and ask(f"Remove {args.name}{' (keeping its data)' if args.keep_data else ' and its data'}? [y/N] ").strip().lower() not in ("y", "yes"):
            out("Nothing removed.")
            return 1
        status, body = http("POST", f"/v1/plugins/{urllib.parse.quote(args.name)}/delete", {"keepData": args.keep_data})
        out(f"Removed {args.name}." if status == 200 else _message(body))
        return 0 if status == 200 else 1
    source = args.source if args.action == "add" else args.name
    status, job = http("POST", "/v1/plugins/resolve", {"source": source})
    if status != 200:
        out(_message(job))
        return 1
    out(f"Looking up {source}")
    job = _wait(http, job, out, sleep)
    if job.get("state") != "done":
        out(job.get("detail") or "That didn't work.")
        return 1
    card = job["result"]
    out(f"{card['title']} {card['version']} from {card['repo']} ({'checked by Cyclone' if card['verified'] else 'UNVERIFIED'})")
    out(f"  {card['summary']}")
    perms = card["permissions"]
    out(f"  Talks to: {', '.join(perms['network']) or 'nothing outside this PC'} · Files: "
        f"{'its own folder' if perms['files'] == 'own' else 'your files'}")
    for s in card["serves"]:
        out(f"  Port {s['port']} ({s['sensitivity']}): {s['summary']}")
    if card.get("conflict"):
        out(card["conflict"])
        return 1
    if args.action == "add":
        allowed = [p.strip() for p in args.allow.split(",") if p.strip()] if args.allow else \
            [s["port"] for s in card["serves"] if s["sensitivity"] == "public"]
        settings = {}
        for pair in args.set:
            key, sep, value = pair.partition("=")
            if not sep:
                out(f"--set takes KEY=VALUE, not {pair!r}")
                return 1
            settings[key] = _value(value)
    else:
        allowed = (card.get("update") or {}).get("consent") or []
        settings = {}
    if not card["verified"]:
        out("This runs code from a source Cyclone hasn't checked, with the same access as your Windows account.")
    if not args.yes and ask("Install? [y/N] ").strip().lower() not in ("y", "yes"):
        out("Nothing installed.")
        return 1
    status, job = http("POST", "/v1/plugins/install", {"sha256": card["sha256"], "accept": True,
                                                       "trustUnverified": True, "allowed": allowed, "settings": settings})
    if status != 200:
        out(_message(job))
        return 1
    job = _wait(http, job, out, sleep)
    if job.get("state") != "done":
        out(job.get("detail") or "That didn't work.")
        return 1
    out(f"{card['title']} {card['version']} is installed and running.")
    return 0
