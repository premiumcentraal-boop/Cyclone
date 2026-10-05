"""`cyclone-testbench`: run Cyclone Lab missions round the clock, keep every result, and keep one findings ledger.

    cyclone-testbench doctor                      is Cyclone running, which phone, are the missions installed
    cyclone-testbench install                     copy the mission packs into Cyclone's Lab missions folder
    cyclone-testbench validate FILE...            check mission files with the Lab's own rules
    cyclone-testbench catalog [--suite S]         list the missions Cyclone knows
    cyclone-testbench next                        what the next batch should be (and why)
    cyclone-testbench run --next --wait           run that batch, wait, write the report, update the ledger
    cyclone-testbench run --missions a,b --reps 3 --wait
    cyclone-testbench report EXP_ID               (re)write the report and ledger for an experiment
    cyclone-testbench findings [--all]            the ledger, most severe first
    cyclone-testbench finding F-1234abcd --status fixed --note "alpha.108: ..."
    cyclone-testbench dashboard                   write DASHBOARD.md: recent runs and open findings
    cyclone-testbench accounts-map --package com.example.app --basis mine
    cyclone-testbench task TASK_ID                a Command Center task's state

Results go to ./testbench-results (or --results). Nothing here approves anything, types a secret, or talks to a phone
except through the Lab and Command Center routes of the Cyclone gateway on this PC.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path
from typing import Any

from . import findings as ledger_ops
from .gateway import Gateway, GatewayError, find_connection
from .report import dashboard, render, summarize
from .results import Results, now_ms
from .rotation import DEFAULT_CAMPAIGN, plan_batch, validate_campaign

PACKAGE_DIR = Path(__file__).resolve().parent.parent
PACKS_DIR = PACKAGE_DIR / "missions"
MAX_LAB_FILES = 50   # the Lab reads at most 50 files from its missions folder


def _out(text: str) -> None:
    sys.stdout.write(text if text.endswith("\n") else text + "\n")


def _gateway(args: argparse.Namespace) -> Gateway:
    return Gateway(find_connection(args.url, None, args.runtime))


def _results(args: argparse.Namespace) -> Results:
    return Results(Path(args.results).resolve())


def _campaign(args: argparse.Namespace) -> dict[str, Any]:
    path = getattr(args, "campaign", None)
    if not path:
        default = PACKAGE_DIR / "campaigns" / "round-the-clock.json"
        return validate_campaign(json.loads(default.read_text(encoding="utf-8"))) if default.is_file() else dict(DEFAULT_CAMPAIGN)
    return validate_campaign(json.loads(Path(path).read_text(encoding="utf-8")))


def _catalog(gw: Gateway) -> dict[str, Any]:
    return gw.get("/v1/lab/missions")


def _expectations(catalog: dict[str, Any]) -> dict[str, bool]:
    """Missions with an 'owner asked' check, and what that check expects (true: must ask; false: must just do it)."""
    out: dict[str, bool] = {}
    for mission in catalog.get("missions") or []:
        for check in mission.get("checks") or []:
            if check.get("check") == "owner" and isinstance(check.get("asked"), bool):
                out[mission["id"]] = check["asked"]
    return out


def _pick_device(gw: Gateway, wanted: str | None) -> str:
    devices = (gw.get("/v1/devices") or {}).get("devices") or []
    if wanted:
        if not any(d.get("deviceId") == wanted for d in devices):
            raise GatewayError(f"No phone with id {wanted}. Run `cyclone-testbench doctor` to see them.")
        return wanted
    ready = [d for d in devices if d.get("paired") and str(d.get("state") or "").upper() == "READY"]
    if len(ready) == 1:
        return str(ready[0]["deviceId"])
    if not ready:
        raise GatewayError("No paired phone is ready. Connect the phone, unlock it and open Cyclone.")
    raise GatewayError("More than one phone is ready; pass --device (see `cyclone-testbench doctor`).")


# ---- commands --------------------------------------------------------------------------------------------------------

def cmd_doctor(args: argparse.Namespace) -> int:
    try:
        connection = find_connection(args.url, None, args.runtime)
    except GatewayError as exc:
        _out(f"✗ {exc}")
        return 2
    _out(f"✓ gateway {connection.url}  (runtime {connection.runtime or 'unknown'})")
    gw = Gateway(connection)
    try:
        devices = (gw.get("/v1/devices") or {}).get("devices") or []
    except GatewayError as exc:
        _out(f"✗ {exc}")
        return 2
    if not devices:
        _out("✗ no phones. Connect the phone over USB (or pair it over Wi-Fi) and open Cyclone on it.")
    for d in devices:
        health = d.get("health") or {}
        _out(f"{'✓' if str(d.get('state')).upper() == 'READY' and d.get('paired') else '•'} {d.get('deviceId')}  {d.get('name')}  "
             f"{d.get('connectionLabel') or d.get('state')}  app {d.get('mobileVersion') or '?'}"
             + (f"  battery {health.get('batteryPercent')}%" if health.get("batteryPercent") is not None else ""))
    catalog = _catalog(gw)
    ours = [m for m in catalog.get("missions") or [] if str(m.get("id", "")).startswith("tb.")]
    _out(f"{'✓' if ours else '✗'} {len(catalog.get('missions') or [])} Lab missions, {len(ours)} from the testbench"
         + ("" if ours else " (run `cyclone-testbench install`)"))
    for problem in catalog.get("problems") or []:
        _out(f"✗ mission file problem: {problem}")
    running = [e for e in (gw.get("/v1/lab/experiments") or {}).get("experiments", []) if e.get("status") == "running"]
    if running:
        _out(f"• an experiment is running: {running[0].get('id')} ({running[0].get('done')}/{running[0].get('total')})")
    _out(f"✓ results folder {Path(args.results).resolve()}")
    return 0


def cmd_install(args: argparse.Namespace) -> int:
    target = Path(args.missions_dir) if args.missions_dir else None
    if target is None:
        connection = find_connection(args.url, None, args.runtime)
        if connection.runtime is None:
            raise GatewayError("Cyclone's runtime folder is unknown; pass --missions-dir <runtime>\\lab\\missions.")
        target = connection.runtime / "lab" / "missions"
    target.mkdir(parents=True, exist_ok=True)
    sources = sorted(Path(args.packs).glob("*.json"))
    problems = _validate_files(sources)
    if problems:
        for p in problems:
            _out(f"✗ {p}")
        return 1
    for old in target.glob("testbench-*.json"):
        if not (Path(args.packs) / old.name).is_file():
            old.unlink()                             # a pack that was removed or renamed
    for path in sources:
        shutil.copyfile(path, target / path.name)
    total = len(list(target.glob("*.json")))
    _out(f"✓ {len(sources)} mission files in {target}" + (f"  ✗ {total} files: the Lab reads only {MAX_LAB_FILES}" if total > MAX_LAB_FILES else ""))
    try:
        catalog = _catalog(_gateway(args))
        for problem in catalog.get("problems") or []:
            _out(f"✗ {problem}")
        _out(f"✓ Cyclone now knows {len(catalog.get('missions') or [])} missions")
    except GatewayError as exc:
        _out(f"• installed; Cyclone isn't reachable to confirm ({exc})")
    return 0


def _validate_files(paths: list[Path]) -> list[str]:
    try:
        from cyclone_device_gateway.lab.missions import builtin_missions, parse_mission
    except Exception:  # noqa: BLE001 - without the gateway package only the JSON can be checked
        parse_mission, builtin_missions = None, lambda: []
    seen = {m.id for m in builtin_missions()}
    problems = []
    for path in paths:
        if not path.name.startswith(("testbench-", "generated-")):
            problems.append(f"{path.name}: mission files are named testbench-*.json or generated-*.json")
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            problems.append(f"{path.name}: {exc}")
            continue
        for raw in data if isinstance(data, list) else [data]:
            mid = raw.get("id") if isinstance(raw, dict) else None
            if not isinstance(mid, str) or not mid.startswith("tb."):
                problems.append(f"{path.name}: {mid}: testbench mission ids start with 'tb.'")
            if mid in seen:
                problems.append(f"{path.name}: {mid}: duplicate id")
            seen.add(mid)
            if parse_mission is not None:
                try:
                    parse_mission(raw)
                except ValueError as exc:
                    problems.append(f"{path.name}: {exc}")
    if len(paths) > MAX_LAB_FILES - 5:
        problems.append(f"{len(paths)} files: keep packs under {MAX_LAB_FILES - 5} so your own Lab files still fit")
    return problems


def cmd_validate(args: argparse.Namespace) -> int:
    paths = [Path(p) for p in args.files] or sorted(Path(args.packs).glob("*.json"))
    problems = _validate_files(paths)
    for p in problems:
        _out(f"✗ {p}")
    if not problems:
        count = sum(len(json.loads(p.read_text(encoding="utf-8"))) if p.read_text(encoding="utf-8").lstrip().startswith("[") else 1 for p in paths)
        _out(f"✓ {count} missions in {len(paths)} files are valid")
    return 1 if problems else 0


def cmd_catalog(args: argparse.Namespace) -> int:
    catalog = _catalog(_gateway(args))
    for m in catalog.get("missions") or []:
        if args.suite and args.suite not in (m.get("suites") or []):
            continue
        _out(f"{m['id']:<34} {','.join(m.get('suites') or []):<22} {m.get('goal')}")
    return 0


def cmd_next(args: argparse.Namespace) -> int:
    gw = _gateway(args)
    res = _results(args)
    plan = plan_batch(_campaign(args), _catalog(gw).get("missions") or [], res.runs(), res.read_jsonl("findings.jsonl"))
    if args.json:
        _out(json.dumps(plan, indent=1))
        return 0
    _out(f"Next: {plan['name']}  ({len(plan['missions'])} missions)")
    for mission_id in plan["missions"]:
        _out(f"  {mission_id:<34} {plan['reasons'][mission_id]}")
    return 0


def _variants(raw: list[str] | None, campaign: dict[str, Any]) -> list[dict[str, Any]]:
    if not raw:
        return campaign["variants"]
    out = []
    for item in raw:
        value = json.loads(item) if item.lstrip().startswith("{") else {"name": item}
        out.append(value)
    return out


def cmd_run(args: argparse.Namespace) -> int:
    gw = _gateway(args)
    res = _results(args)
    campaign = _campaign(args)
    catalog = _catalog(gw)
    slot = None
    if args.next:
        plan = plan_batch(campaign, catalog.get("missions") or [], res.runs(), res.read_jsonl("findings.jsonl"))
        missions, name, slot = plan["missions"], plan["name"], plan["slot"]
    elif args.suite:
        missions = [m["id"] for m in catalog.get("missions") or [] if args.suite in (m.get("suites") or [])]
        name = f"suite {args.suite}"
    else:
        missions = [m.strip() for m in (args.missions or "").split(",") if m.strip()]
        name = "missions " + ", ".join(missions[:3]) + ("…" if len(missions) > 3 else "")
    if not missions:
        _out("Nothing to run.")
        return 1
    body = {"deviceId": _pick_device(gw, args.device), "name": (args.name or f"testbench · {name}")[:80], "missions": missions,
            "variants": _variants(args.variant, campaign), "repetitions": args.reps or campaign["repetitions"]}
    experiment = gw.post("/v1/lab/experiments", body)
    exp_id = experiment.get("id")
    pending = res.read_jsonl("pending.jsonl")
    res.write_jsonl("pending.jsonl", pending + [{"experimentId": exp_id, "slot": slot, "startedAt": now_ms()}])
    _out(f"Started {exp_id}: {len(missions)} missions × {len(body['variants'])} variant(s) × {body['repetitions']}")
    if not args.wait:
        return 0
    status = _watch(gw, exp_id, args.poll)
    _report(gw, res, exp_id, catalog)
    return 0 if status == "done" else 3


def _watch(gw: Gateway, exp_id: str, poll: float) -> str:
    last = None
    while True:
        payload = gw.get(f"/v1/lab/experiments/{exp_id}")
        experiment = payload.get("experiment") or {}
        current = experiment.get("current") or {}
        line = f"{experiment.get('done')}/{experiment.get('total')} {current.get('missionId') or ''} {current.get('phase') or ''}".strip()
        if line != last:
            _out(f"[{time.strftime('%H:%M:%S')}] {line}")
            last = line
        if experiment.get("status") != "running":
            _out(f"Experiment {experiment.get('status')}" + (f": {experiment.get('reason')}" if experiment.get("reason") else ""))
            return str(experiment.get("status"))
        time.sleep(poll)


def _report(gw: Gateway, res: Results, exp_id: str, catalog: dict[str, Any] | None = None) -> Path:
    payload = gw.get(f"/v1/lab/experiments/{exp_id}")
    catalog = catalog or _catalog(gw)
    expectations = _expectations(catalog)
    trials = payload.get("trials") or []
    experiment = payload.get("experiment") or {}
    at = now_ms()
    candidates = [c for c in (ledger_ops.classify(t) for t in trials) if c]
    old = res.read_jsonl("findings.jsonl")
    old_keys = {f["key"] for f in old}
    ledger, counts = ledger_ops.merge(old, candidates, at=at, app_version=experiment.get("appVersion"))
    res.write_jsonl("findings.jsonl", ledger)
    new = [f for f in ledger if f["key"] not in old_keys]
    report_md = render(payload, expectations, counts, new)
    folder = res.save_experiment(exp_id, report_md, trials)
    pending = res.read_jsonl("pending.jsonl")
    slot = next((p.get("slot") for p in pending if p.get("experimentId") == exp_id), None)
    res.write_jsonl("pending.jsonl", [p for p in pending if p.get("experimentId") != exp_id])
    res.record_run({**summarize(payload, expectations), "slot": slot})
    (res.root / "DASHBOARD.md").write_text(dashboard(res.runs(), ledger), encoding="utf-8")
    _out(f"Report: {folder / 'report.md'}")
    _out(report_md.split("\n## ", 2)[1] if "\n## " in report_md else report_md)
    return folder


def cmd_watch(args: argparse.Namespace) -> int:
    return 0 if _watch(_gateway(args), args.experiment, args.poll) == "done" else 3


def cmd_report(args: argparse.Namespace) -> int:
    _report(_gateway(args), _results(args), args.experiment)
    return 0


def cmd_findings(args: argparse.Namespace) -> int:
    ledger = _results(args).read_jsonl("findings.jsonl")
    shown = ledger if args.all else [f for f in ledger if f.get("status") in {"open", "fixing", "regressed"}]
    if args.area:
        shown = [f for f in shown if f.get("area") == args.area]
    if not shown:
        _out("No open findings.")
    for f in shown:
        _out(f"{f['id']}  {f.get('severity'):<8} {f.get('status'):<9} x{f.get('count'):<3} {f.get('title')}")
    return 0


def cmd_finding(args: argparse.Namespace) -> int:
    res = _results(args)
    ledger = res.read_jsonl("findings.jsonl")
    try:
        item = ledger_ops.update(ledger, args.id, status=args.status, note=args.note, at=now_ms())
    except KeyError:
        _out(f"No finding {args.id}.")
        return 1
    res.write_jsonl("findings.jsonl", ledger)
    _out(json.dumps(item, indent=1, ensure_ascii=False))
    return 0


def cmd_dashboard(args: argparse.Namespace) -> int:
    res = _results(args)
    text = dashboard(res.runs(), res.read_jsonl("findings.jsonl"))
    res.ensure()
    (res.root / "DASHBOARD.md").write_text(text, encoding="utf-8")
    _out(text)
    return 0


def cmd_accounts_map(args: argparse.Namespace) -> int:
    """Start Cyclone's own sign-up mapping for an app (the phone walks the sign-up; nothing is created)."""
    gw = _gateway(args)
    body = {"deviceId": _pick_device(gw, args.device), "package": args.package, "app": args.app or args.package, "ownerBasis": args.basis}
    result = gw.post("/v1/cc/signup/map", body)
    _out(json.dumps(result, indent=1, ensure_ascii=False)[:2000])
    return 0


def cmd_task(args: argparse.Namespace) -> int:
    task = _gateway(args).get(f"/v1/cc/tasks/{args.task}")
    run = task.get("run") or {}
    _out(f"{task.get('id')}  {task.get('status')}  {task.get('title')}\n  cause: {task.get('cause') or '–'}\n  "
         f"summary: {run.get('summary') or '–'}  turns {run.get('turns', '–')}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cyclone-testbench", description="Round-the-clock Cyclone testing on a real phone.")
    parser.add_argument("--url", help="gateway URL (default: what `cyclone` saved, else http://127.0.0.1:8765)")
    parser.add_argument("--runtime", help="Cyclone runtime folder (default: what `cyclone` saved)")
    parser.add_argument("--results", default="testbench-results", help="results folder (default ./testbench-results)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("doctor").set_defaults(fn=cmd_doctor)
    p = sub.add_parser("install")
    p.add_argument("--packs", default=str(PACKS_DIR))
    p.add_argument("--missions-dir")
    p.set_defaults(fn=cmd_install)
    p = sub.add_parser("validate")
    p.add_argument("files", nargs="*")
    p.add_argument("--packs", default=str(PACKS_DIR))
    p.set_defaults(fn=cmd_validate)
    p = sub.add_parser("catalog")
    p.add_argument("--suite")
    p.set_defaults(fn=cmd_catalog)
    p = sub.add_parser("next")
    p.add_argument("--campaign")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_next)
    p = sub.add_parser("run")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--next", action="store_true", help="the batch `next` would pick")
    group.add_argument("--suite")
    group.add_argument("--missions", help="comma-separated mission ids")
    p.add_argument("--reps", type=int)
    p.add_argument("--variant", action="append", help='a variant name, or JSON like {"name":"B","effort":"high"}; repeat for A/B')
    p.add_argument("--device")
    p.add_argument("--name")
    p.add_argument("--campaign")
    p.add_argument("--wait", action="store_true")
    p.add_argument("--poll", type=float, default=10.0)
    p.set_defaults(fn=cmd_run)
    p = sub.add_parser("watch")
    p.add_argument("experiment")
    p.add_argument("--poll", type=float, default=10.0)
    p.set_defaults(fn=cmd_watch)
    p = sub.add_parser("report")
    p.add_argument("experiment")
    p.set_defaults(fn=cmd_report)
    p = sub.add_parser("findings")
    p.add_argument("--all", action="store_true")
    p.add_argument("--area", choices=["safety", "honesty", "judgement", "reliability", "speed", "infra"])
    p.set_defaults(fn=cmd_findings)
    p = sub.add_parser("finding")
    p.add_argument("id")
    p.add_argument("--status", choices=sorted(ledger_ops.STATUSES))
    p.add_argument("--note")
    p.set_defaults(fn=cmd_finding)
    sub.add_parser("dashboard").set_defaults(fn=cmd_dashboard)
    p = sub.add_parser("accounts-map")
    p.add_argument("--package", required=True)
    p.add_argument("--app")
    p.add_argument("--basis", choices=["mine", "company", "client"], required=True, help="whose account it would be")
    p.add_argument("--device")
    p.set_defaults(fn=cmd_accounts_map)
    p = sub.add_parser("task")
    p.add_argument("task")
    p.set_defaults(fn=cmd_task)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return int(args.fn(args) or 0)
    except GatewayError as exc:
        _out(f"✗ {exc}")
        return 2
    except ValueError as exc:
        _out(f"✗ {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
