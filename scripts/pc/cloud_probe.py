"""Plan 56 run 0: test Cyclone's VMOS client against the owner's real account, one stage at a time, and record every
answer (keys removed) so a failure can be debugged from facts.

Run from a repo checkout on the owner's PC (see docs/handoff/VMOS_LIVE_TEST.md):

    python -m pip install -e apps/device-gateway
    python scripts/pc/cloud_probe.py --stage read --out cloud-probe

The keys are read from the environment (CYCLONE_VMOS_AK, CYCLONE_VMOS_SK) or from a JSON file outside the repo
(`--keys-file`, default ~/.cyclone/vmos-keys.json: {"accessKey": "...", "secretKey": "..."}). They are never
printed, never written and never taken as command-line arguments.

Stages, cheapest first:

    read      free      sign in, list phones, names, padCode moves, offers for Android 13/14/15, Cyclone on --pad
    adb       free      open 7-day remote ADB on --pad (switching ADB on if needed); keep-alive if Cyclone is there
    backup    storage   back up --pad, following it to done
    restore   destroys  restore --backup-id onto --pad; needs --replace-everything-on-the-phone
    timing    PAID      rent 1 pay-for-time phone, watch it, power it off and on; needs --confirm-cents
    rental    PAID      rent 1 phone for the cheapest (or --sku) period, wait until it exists; needs --confirm-cents
    duoplus   free      list DuoPlus phones (CYCLONE_DUOPLUS_KEY)

A paid stage first prints what it would buy and the exact price, then stops unless `--confirm-cents` equals that price.
Only the owner gives that number. Nothing here deletes a phone.

Everything is written to --out: `vmos-<stage>.json` (each call: path, request, HTTP status, answer, keys redacted) and
`summary-<stage>.md` (what was checked, what passed, what failed and VMOS's own words).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any, Callable

from cyclone_device_gateway.cloud_fleet.http import urllib_transport
from cyclone_device_gateway.cloud_fleet.models import CloudPhone, ProviderError
from cyclone_device_gateway.cloud_fleet.providers.vmos import VmosCloud

SECRET_KEYS = {"key", "connectKey", "password", "apiKey", "accessKey", "secretKey", "token", "stsToken"}
DEFAULT_KEYS = Path.home() / ".cyclone" / "vmos-keys.json"


def redact(value: Any, secrets: list[str]) -> Any:
    if isinstance(value, dict):
        return {k: ("<redacted>" if k in SECRET_KEYS else redact(v, secrets)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, secrets) for v in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret:
                value = value.replace(secret, "<redacted>")
    return value


class Recorder:
    """The real transport, recording each call with keys removed. Headers are never recorded."""

    def __init__(self, secrets: list[str], transport: Callable[..., tuple[int, bytes]] = urllib_transport):
        self.secrets = secrets
        self.transport = transport
        self.records: list[dict[str, Any]] = []

    def __call__(self, method: str, url: str, headers: dict[str, str], body: bytes, timeout: float) -> tuple[int, bytes]:
        started = time.time()
        status, raw = self.transport(method, url, headers, body, timeout)
        path, _, query = ("/" + url.split("/", 3)[3]).partition("?")
        try:
            answer = json.loads(raw.decode("utf-8"))
        except ValueError:
            answer = {"raw": raw[:400].decode("utf-8", "replace")}
        try:
            request = json.loads(body) if body else {}
        except ValueError:
            request = {"raw": body[:200].decode("utf-8", "replace")}
        self.records.append({
            "at": time.strftime("%Y-%m-%dT%H:%M:%S"), "method": method, "path": path, "query": query or None,
            "signing": "v2" if "X-Sign" in headers else "hmac", "request": redact(request, self.secrets),
            "status": status, "ms": int((time.time() - started) * 1000), "answer": redact(answer, self.secrets),
        })
        return status, raw


class Report:
    def __init__(self, out: Path, stage: str, recorder: Recorder):
        self.out, self.stage, self.recorder = out, stage, recorder
        self.lines: list[str] = [f"# VMOS live test: {stage}", "", time.strftime("Run %Y-%m-%d %H:%M:%S"), ""]
        self.failed = False

    def ok(self, what: str, detail: str = "") -> None:
        self._line("PASS", what, detail)

    def fail(self, what: str, detail: str = "") -> None:
        self.failed = True
        self._line("FAIL", what, detail)

    def note(self, what: str, detail: str = "") -> None:
        self._line("NOTE", what, detail)

    def _line(self, word: str, what: str, detail: str) -> None:
        line = f"- **{word}** {what}" + (f": {detail}" if detail else "")
        self.lines.append(redact(line, self.recorder.secrets))
        print(self.lines[-1].replace("**", ""))

    def write(self) -> None:
        self.out.mkdir(parents=True, exist_ok=True)
        (self.out / f"vmos-{self.stage}.json").write_text(json.dumps(self.recorder.records, indent=2, ensure_ascii=False),
                                                         encoding="utf-8")
        (self.out / f"summary-{self.stage}.md").write_text("\n".join(self.lines) + "\n", encoding="utf-8")
        print(f"Written to {self.out.resolve()} ({len(self.recorder.records)} calls recorded)")


def load_keys(keys_file: Path) -> tuple[str, str]:
    ak, sk = os.getenv("CYCLONE_VMOS_AK", ""), os.getenv("CYCLONE_VMOS_SK", "")
    if ak and sk:
        return ak, sk
    if keys_file.exists():
        data = json.loads(keys_file.read_text(encoding="utf-8"))
        return str(data.get("accessKey") or ""), str(data.get("secretKey") or "")
    return "", ""


def money(cents: int) -> str:
    return f"${cents / 100:,.2f} ({cents} as VMOS lists it)"


def wait_for(report: Report, what: str, check: Callable[[], Any], *, every_s: float, limit_s: float,
             sleep: Callable[[float], None] = time.sleep) -> Any:
    deadline = time.time() + limit_s
    while True:
        value = check()
        if value is not None:
            return value
        if time.time() >= deadline:
            report.fail(what, f"not within {int(limit_s)} s")
            return None
        sleep(every_s)


def phone_state(vmos: VmosCloud, code: str) -> str | None:
    return next((p.power for p in vmos.list_phones() if p.remote_id == code), None)


def running(vmos: VmosCloud, code: str) -> str | None:
    return "running" if phone_state(vmos, code) == "running" else None


# Stages ---------------------------------------------------------------------------------------------------------

def stage_read(vmos: VmosCloud, args: argparse.Namespace, report: Report) -> None:
    phones = vmos.list_phones()
    report.ok("Signed in", f"signing scheme that worked: {vmos.signing}")
    report.ok("Phone list (infos)", f"{len(phones)} phones: " + ", ".join(f"{p.remote_id} [{p.power}]" for p in phones))
    try:
        details = vmos.phone_details([p.remote_id for p in phones])
        report.ok("Phone details (userPadList)", "; ".join(
            f"{code}: name={d['name']!r} android={d['android']} paidUntilMs={d['paidUntilMs']} equipmentId={d['equipmentId']} "
            f"plan={d['plan']!r}" for code, d in details.items()) or "none")
    except ProviderError as exc:
        report.fail("Phone details (userPadList)", f"{exc.code}: {exc.message}")
    try:
        report.ok("padCode moves (queryPadIdChangeRecords)", json.dumps(vmos.pad_code_changes()))
    except ProviderError as exc:
        report.fail("padCode moves", f"{exc.code}: {exc.message}")
    for android in (13, 14, 15):
        try:
            offers = vmos.offers(android)
        except ProviderError as exc:
            report.fail(f"Offers Android {android} (getCloudGoodList)", f"{exc.code}: {exc.message}")
            continue
        parts = []
        for o in offers:
            rentals = ", ".join(f"sku {r['skuId']} {r['label']} {r['priceCents']}" for r in o["rentals"])
            timing = ", ".join(f"sku {t['skuId']} per {t['label']} {t['priceCents']}" for t in o["timing"])
            parts.append(f"{o['name']} (config {o['configId']}, group {o['group']}): rentals [{rentals}] pay-for-time [{timing}]")
        report.ok(f"Offers Android {android}", " | ".join(parts) or "nothing offered")
    report.note("Compare these prices with the VMOS console",
                "Cyclone reads them as US cents (500 = $5.00). If the console shows $500 for that item, they are not cents.")
    if args.pad:
        phone = CloudPhone("vmos", args.pad, args.pad)
        try:
            version = vmos.installed_version(phone)
            report.ok(f"Cyclone on {args.pad} (listInstalledApp)", json.dumps(version) if version else "not installed")
        except ProviderError as exc:
            report.fail("Installed apps (listInstalledApp)", f"{exc.code}: {exc.message}")


def stage_adb(vmos: VmosCloud, args: argparse.Namespace, report: Report) -> None:
    phone = CloudPhone("vmos", need_pad(args), args.pad)
    link = vmos.open_adb(phone)
    switched = any(r["path"].endswith("/openOnlineAdb") for r in report.recorder.records)
    report.ok("Remote ADB (adb)", f"kind={link.kind} ssh={link.ssh_user}@{link.ssh_host}:{link.ssh_port} -> "
              f"{link.target_host}:{link.target_port}, expires "
              f"{time.strftime('%Y-%m-%d %H:%M', time.localtime((link.expires_at_ms or 0) / 1000))}"
              f"{' (ADB was switched on first with openOnlineAdb)' if switched else ''}")
    report.note("Next by hand", "keep this phone connected in Glass and check it reaches Connected")
    if vmos.installed_version(phone):
        vmos.keep_alive([phone])
        report.ok("Keep-alive for Cyclone (setKeepAliveApp)", "accepted")
    else:
        report.note("Keep-alive skipped", "Cyclone is not installed on this phone yet")


def stage_backup(vmos: VmosCloud, args: argparse.Namespace, report: Report) -> None:
    phone = CloudPhone("vmos", need_pad(args), args.pad)
    vmos.backup_size_start(phone)
    sized = wait_for(report, "Backup size (queryBackupCalculateResult)",
                     lambda: (lambda r: r if r[0] != "calculating" else None)(vmos.backup_size(phone)), every_s=10, limit_s=240)
    if sized:
        report.ok("Backup size", f"status={sized[0]} bytes={sized[1]}")
    batch = vmos.backup_start(phone, args.name or "Cyclone live test")
    report.ok("Backup started (addBackup)", f"batchId={batch}")
    done = wait_for(report, "Backup finished (queryBackupBatch)",
                    lambda: (lambda r: r if r["state"] != "running" else None)(vmos.backup_progress(batch, phone)),
                    every_s=30, limit_s=3600)
    if done and done["state"] == "done":
        report.ok("Backup finished", f"backupId={done['backupId']} (use it with --stage restore --backup-id)")
    elif done:
        report.fail("Backup failed", str(done["message"]))


def stage_restore(vmos: VmosCloud, args: argparse.Namespace, report: Report) -> None:
    phone = CloudPhone("vmos", need_pad(args), args.pad)
    if not args.backup_id or not args.replace_everything_on_the_phone:
        report.fail("Restore not run", "it replaces everything on the phone: pass --backup-id and --replace-everything-on-the-phone")
        return
    vmos.restore(args.backup_id, phone)
    report.ok("Restore accepted (clonePadBackup)", "watch the phone come back")
    if wait_for(report, "Phone running again", lambda: running(vmos, phone.remote_id), every_s=20, limit_s=1800):
        report.ok("Phone running again after restore")


def pick(offers: list[dict[str, Any]], kind: str, sku: int | None) -> tuple[dict[str, Any], dict[str, Any]] | None:
    options = [(o, s) for o in offers for s in o["rentals" if kind == "rental" else "timing"]]
    if sku:
        options = [(o, s) for o, s in options if s["skuId"] == sku]
    return min(options, key=lambda pair: pair[1]["priceCents"]) if options else None


def paid_gate(report: Report, args: argparse.Namespace, what: str, cents: int) -> bool:
    report.note("Would buy", f"{what} for {money(cents)}")
    if args.confirm_cents != cents:
        report.note("Stopped before paying", f"the owner must confirm this exact price: rerun with --confirm-cents {cents}")
        return False
    return True


def stage_timing(vmos: VmosCloud, args: argparse.Namespace, report: Report) -> None:
    chosen = pick(vmos.offers(args.android), "timing", args.sku)
    if not chosen:
        report.fail("Nothing pay-for-time offered", f"Android {args.android}")
        return
    config, sku = chosen
    if not paid_gate(report, args, f"1 pay-for-time {config['name']} (sku {sku['skuId']}, per {sku['label']}), "
                     f"Android {args.android}", sku["priceCents"]):
        return
    codes = vmos.rent_timing(sku["skuId"], args.android, 1, group=int(config.get("group") or 1))
    if not codes:
        report.fail("Pay-for-time order (createByTimingOrder)", "no padCode in the answer")
        return
    code = codes[0]
    phone = CloudPhone("vmos", code, code)
    report.ok("Pay-for-time order", f"padCode={code}")
    first = wait_for(report, "New phone listed", lambda: phone_state(vmos, code), every_s=15, limit_s=600)
    report.note("Starts powered on?", f"first state seen: {first}")
    up = wait_for(report, "New phone running", lambda: running(vmos, code), every_s=15, limit_s=900)
    if not up:
        report.note("Not running yet", "trying power on (timingPadOn)")
        vmos.power(phone, True)
        up = wait_for(report, "Running after power on", lambda: running(vmos, code), every_s=15, limit_s=900)
    if up:
        report.ok("Pay-for-time phone running")
    vmos.power(phone, False)
    off = wait_for(report, "Powered off (timingPadOff, keeps data)",
                   lambda: (lambda s: s if s in {"stopped", "gone"} else None)(phone_state(vmos, code)), every_s=15, limit_s=600)
    if off:
        report.ok("Power off", f"state={off}")
    vmos.power(phone, True)
    if wait_for(report, "Powered on again (timingPadOn, defCode 0)", lambda: running(vmos, code), every_s=15, limit_s=900):
        report.ok("Power on again")
    report.note("Owner", f"check the VMOS console: billing while {code} was off, and delete it there if it isn't needed")


def stage_rental(vmos: VmosCloud, args: argparse.Namespace, report: Report) -> None:
    chosen = pick(vmos.offers(args.android), "rental", args.sku)
    if not chosen:
        report.fail("Nothing to rent", f"Android {args.android}")
        return
    config, sku = chosen
    if not paid_gate(report, args, f"1 {config['name']} for {sku['label']} (sku {sku['skuId']}), Android {args.android}, "
                     "no auto-renew", sku["priceCents"]):
        return
    before = set(vmos.phone_details())
    equipment = vmos.rent(sku["skuId"], args.android, 1, auto_renew=False)
    report.ok("Rental order (createMoneyOrder, goodId = the period's sku)", f"equipmentIds={equipment}")
    new = wait_for(report, "Rented phone appears (userPadList)",
                   lambda: next((code for code, d in vmos.phone_details().items()
                                 if code not in before and (not equipment or d.get("equipmentId") in equipment)), None),
                   every_s=20, limit_s=1800)
    if new:
        report.ok("Rented phone", f"padCode={new}")
        report.note("Owner", "check the console's order list: plan and period must match what was printed above")


def stage_duoplus(_vmos: VmosCloud | None, args: argparse.Namespace, report: Report) -> None:
    from cyclone_device_gateway.cloud_fleet.providers.duoplus import DuoPlus

    phones = DuoPlus(os.getenv("CYCLONE_DUOPLUS_KEY", ""), transport=report.recorder).list_phones()
    report.ok("DuoPlus phones", ", ".join(f"{p.remote_id} ({p.power}, {p.android}, adb {p.address})" for p in phones) or "none")


def need_pad(args: argparse.Namespace) -> str:
    if not args.pad:
        raise SystemExit("This stage needs --pad <padCode of a test phone>.")
    return args.pad


STAGES: dict[str, Callable[..., None]] = {"read": stage_read, "adb": stage_adb, "backup": stage_backup,
                                          "restore": stage_restore, "timing": stage_timing, "rental": stage_rental,
                                          "duoplus": stage_duoplus}


def main(argv: list[str] | None = None, *, transport: Callable[..., tuple[int, bytes]] = urllib_transport) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stage", choices=sorted(STAGES), default="read")
    parser.add_argument("--out", type=Path, default=Path("cloud-probe"))
    parser.add_argument("--keys-file", type=Path, default=DEFAULT_KEYS)
    parser.add_argument("--pad", help="padCode of the test phone")
    parser.add_argument("--android", type=int, choices=(13, 14, 15), default=13)
    parser.add_argument("--sku", type=int, help="a specific sku id from the read stage (default: the cheapest)")
    parser.add_argument("--confirm-cents", type=int, help="paid stages: the exact price the owner confirmed")
    parser.add_argument("--backup-id")
    parser.add_argument("--name", help="backup name")
    parser.add_argument("--replace-everything-on-the-phone", action="store_true")
    parser.add_argument("--signing", choices=("v2", "hmac"), help="try this signing scheme first (the other is the fallback)")
    args = parser.parse_args(argv)

    if args.stage == "duoplus":
        recorder = Recorder([os.getenv("CYCLONE_DUOPLUS_KEY", "")], transport)
        vmos = None
    else:
        ak, sk = load_keys(args.keys_file)
        if not ak or not sk:
            print(f"No VMOS keys: set CYCLONE_VMOS_AK and CYCLONE_VMOS_SK, or create {args.keys_file}.")
            return 2
        recorder = Recorder([ak, sk], transport)
        vmos = VmosCloud(ak, sk, transport=recorder, signing=args.signing)
    report = Report(args.out, args.stage, recorder)
    try:
        STAGES[args.stage](vmos, args, report)
    except ProviderError as exc:
        report.fail(f"Stopped on a VMOS error ({exc.code})", exc.message)
    except Exception as exc:  # a bug in Cyclone's client: record it for debugging
        report.fail("Stopped on a Cyclone error", f"{type(exc).__name__}: {exc}")
    finally:
        report.write()
    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())
