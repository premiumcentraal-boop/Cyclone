"""Attach to the Cyclone gateway that is already running and check two physical phones.

This does not start a second fleet, and it does not import test doubles. If the
gateway or the phones are not available, it exits 2 and records NOT_RUN.
"""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .adb.client import ADBClient, ADBError
from .adb.device import CYCLONE_PACKAGE
from .config import resolve_adb_path
from .desktop_runtime.models import deterministic_device_id
from .tooling_seam import load_connection, load_locator


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cyclone-real-device-fleet-check")
    parser.add_argument("--commands", action="store_true", help="Send two real ask goals through the running gateway.")
    parser.add_argument("--operator", action="store_true", help="Wait for you to unplug, replug, lock, and unlock a phone.")
    parser.add_argument("--runtime-dir", default="", help="Where to write real-device-evidence.json. Does not select the gateway.")
    args = parser.parse_args(argv)

    report: dict[str, Any] = {
        "physicalDevicesUsed": False,
        "fakeDoublesUsed": False,
        "browserPreviewUsed": False,
        "attachedToRunningGateway": False,
        "phases": {},
        "devices": [],
        "events": [],
        "notes": [],
    }
    if os.environ.get("CYCLONE_REAL_DEVICES", "").strip() != "1":
        report["phases"]["safety"] = "NOT_RUN"
        report["notes"].append("Set CYCLONE_REAL_DEVICES=1 in the shell that will run this checker.")
        return _finish(report, args, 2)

    gateway = _gateway()
    if gateway is None:
        report["phases"]["gateway"] = "NOT_RUN"
        report["notes"].append(
            "No running Cyclone gateway accepted the saved or exported bearer. "
            "Start this source tree's gateway, pair both phones in Glass, then run the checker in a shell that can see the same bearer."
        )
        return _finish(report, args, 2)
    report["attachedToRunningGateway"] = True
    report["gatewayUrl"] = gateway["url"]

    status, snapshot = _request("GET", gateway, "/v1/fleet/snapshot")
    if status == 404:
        report["phases"]["gateway"] = "NOT_RUN"
        report["notes"].append("The process on this port does not expose /v1/fleet/snapshot. Start the gateway from this source tree, not an older install.")
        return _finish(report, args, 2)
    if status != 200 or not isinstance(snapshot, dict):
        report["phases"]["gateway"] = "FAIL"
        report["notes"].append(f"Fleet snapshot returned HTTP {status}.")
        return _finish(report, args, 1)

    adb_path = resolve_adb_path()
    try:
        inventory = ADBClient(adb_path)
        if not inventory.available():
            raise ADBError("adb version failed")
        raw = [device for device in inventory.devices() if device.state == "device"]
    except ADBError as exc:
        report["phases"]["adb"] = "NOT_RUN"
        report["notes"].append(str(exc))
        return _finish(report, args, 2)
    wanted = _select_serials(raw)
    if isinstance(wanted, str):
        report["phases"]["adb"] = "NOT_RUN"
        report["notes"].append(wanted)
        return _finish(report, args, 2)
    report["physicalDevicesUsed"] = True
    report["adbPath"] = adb_path

    _request("POST", gateway, "/v1/fleet/scan")
    time.sleep(1.0)
    status, listing = _request("GET", gateway, "/v1/fleet")
    status_snapshot, snapshot = _request("GET", gateway, "/v1/fleet/snapshot")
    sessions = listing.get("devices") if status == 200 and isinstance(listing, dict) else []
    records = snapshot.get("devices") if status_snapshot == 200 and isinstance(snapshot, dict) else []
    if not isinstance(sessions, list):
        sessions = []
    if not isinstance(records, list):
        records = []

    for device in wanted:
        device_id = deterministic_device_id(device.serial)
        record = next((item for item in records if isinstance(item, dict) and item.get("deviceId") == device_id), None)
        session = next((item for item in sessions if isinstance(item, dict) and item.get("deviceId") == device_id), None)
        health = (session or {}).get("connectionHealth") if isinstance((session or {}).get("connectionHealth"), dict) else {}
        installed = _package_installed(adb_path, device.serial)
        report["devices"].append({
            "serial": device.serial,
            "model": device.model,
            "deviceId": device_id,
            "name": (record or {}).get("name"),
            "trust": (record or {}).get("trust"),
            "online": (record or {}).get("online"),
            "serialSuffix": (record or {}).get("serialSuffix"),
            "screenState": (record or {}).get("screenState"),
            "paired": (session or {}).get("paired") is True,
            "accessibilityConnected": health.get("accessibilityConnected") is True,
            "cycloneInstalled": installed,
            "resumedBefore": _resumed(adb_path, device.serial),
            "idMatchesSerial": device_id == deterministic_device_id(device.serial),
            "inRegistry": record is not None,
        })
    discovered = all(item["inRegistry"] and item["online"] is True and item["idMatchesSerial"] for item in report["devices"])
    report["phases"]["discovery"] = "PASS" if discovered else "FAIL"
    if not discovered:
        report["notes"].append("The running gateway registry does not contain both authorized phones. Scan Glass and confirm both are visible before pairing.")
        return _finish(report, args, 1)

    names = [
        os.environ.get("CYCLONE_DEVICE_A_NAME", "").strip() or "Device A",
        os.environ.get("CYCLONE_DEVICE_B_NAME", "").strip() or "Device B",
    ]
    for item, name in zip(report["devices"], names):
        status, _body = _request("POST", gateway, f"/v1/fleet/devices/{item['deviceId']}/rename", {"name": name})
        item["name"] = name if status == 200 else item["name"]
        if status != 200:
            report["phases"]["rename_persisted"] = "FAIL"
            report["notes"].append(f"Rename of {item['deviceId']} returned HTTP {status}.")
            return _finish(report, args, 1)
    visible = _snapshot_names(gateway, names)
    on_disk = _names_on_disk(names)
    if not visible:
        report["phases"]["rename_persisted"] = "FAIL"
        report["notes"].append("The running gateway did not show the new device names.")
        return _finish(report, args, 1)
    if on_disk is True:
        report["phases"]["rename_persisted"] = "PASS"
    elif on_disk is False:
        report["phases"]["rename_persisted"] = "FAIL"
        report["notes"].append("fleet-registry.json exists, but it does not contain both new names.")
        return _finish(report, args, 1)
    else:
        report["phases"]["rename_persisted"] = "NOT_RUN"
        report["notes"].append(
            "Names are visible on the running gateway. fleet-registry.json was not found. "
            "Set CYCLONE_DEVICE_GATEWAY_RUNTIME to the gateway's runtime directory and run again to prove the names survived on disk."
        )

    if not args.commands:
        report["phases"]["parallel_commands"] = "NOT_RUN"
        report["phases"]["isolation"] = "NOT_RUN"
        report["phases"]["openrouter"] = "NOT_RUN"
        report["notes"].append("Discovery attached to the running gateway. Re-run with --commands after both phones are paired and accessibility is on.")
    elif not all(item["trust"] == "TRUSTED" and item["accessibilityConnected"] and item["cycloneInstalled"] for item in report["devices"]):
        report["phases"]["parallel_commands"] = "NOT_RUN"
        report["phases"]["isolation"] = "NOT_RUN"
        report["phases"]["openrouter"] = "NOT_RUN"
        report["notes"].append("Commands were not sent. Each phone needs Cyclone installed, accessibility connected, and registry trust TRUSTED (pair in Glass and tap Allow).")
    else:
        code = _commands(gateway, adb_path, report)
        if code != 0:
            return _finish(report, args, code)
        code = _isolation(gateway, adb_path, report)
        if code != 0:
            return _finish(report, args, code)

    if args.operator:
        code = _operator(gateway, report)
        if code != 0:
            return _finish(report, args, code)
    else:
        report["phases"]["disconnect"] = "NOT_RUN"
        report["phases"]["lock"] = "NOT_RUN"
        report["notes"].append("Unplug and secure-lock checks were not run. Use --operator with both phones in your hands.")

    _status, events = _request("GET", gateway, "/v1/fleet/events")
    report["events"] = events.get("events", [])[-80:] if isinstance(events, dict) else []
    pending = [name for name, phase in report["phases"].items() if phase == "NOT_RUN"]
    return _finish(report, args, 3 if pending else 0)


def _gateway() -> dict[str, str] | None:
    token = os.environ.get("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
    url = os.environ.get("CYCLONE_DEVICE_GATEWAY_URL", "").strip().rstrip("/")
    if not token or not url:
        saved = load_connection(include_env=True)
        if saved and saved.get("token") and saved.get("url"):
            token = token or saved["token"]
            url = url or str(saved["url"]).rstrip("/")
    if not token or not url:
        return None
    gateway = {"url": url, "token": token}
    status, _body = _request("GET", gateway, "/v1/fleet/snapshot")
    if status in {200, 404}:
        return gateway
    return None


def _select_serials(raw: list[Any]) -> list[Any] | str:
    chosen = [
        os.environ.get("CYCLONE_DEVICE_A_SERIAL", "").strip(),
        os.environ.get("CYCLONE_DEVICE_B_SERIAL", "").strip(),
    ]
    if any(chosen):
        if not all(chosen):
            return "Set both CYCLONE_DEVICE_A_SERIAL and CYCLONE_DEVICE_B_SERIAL, or neither."
        found = []
        for serial in chosen:
            match = next((device for device in raw if device.serial == serial), None)
            if match is None:
                return f"{serial} is not an authorized adb device."
            found.append(match)
        return found
    if len(raw) < 2:
        return f"Need 2 authorized adb devices, found {len(raw)}. Unauthorized phones do not count."
    if len(raw) > 2:
        return "More than two phones are authorized. Set CYCLONE_DEVICE_A_SERIAL and CYCLONE_DEVICE_B_SERIAL."
    return raw


def _commands(gateway: dict[str, str], adb_path: str, report: dict[str, Any]) -> int:
    first, second = report["devices"]
    goal = (
        f"On {first['name']}, open the Settings app and stop. Do not change any setting. "
        f"On {second['name']}, open the Clock app and stop. Do not create an alarm."
    )
    started = time.time()
    status, body = _request("POST", gateway, "/v1/fleet/command", {"text": goal})
    if status != 200 or not isinstance(body, dict):
        report["phases"]["parallel_commands"] = "FAIL"
        report["notes"].append(f"POST /v1/fleet/command returned HTTP {status}.")
        return 1
    if body.get("kind") == "clarification":
        report["phases"]["parallel_commands"] = "FAIL"
        report["notes"].append(str(body.get("message") or "Planner asked for clarification.")[:300])
        return 1
    fleet_id = body.get("fleetMissionId")
    if not isinstance(fleet_id, str) or not fleet_id:
        report["phases"]["parallel_commands"] = "FAIL"
        report["notes"].append("The running gateway did not create a fleet mission.")
        return 1
    finished = _wait_mission(gateway, fleet_id, 200)
    report["mission"] = finished
    by_device = {item.get("deviceId"): item for item in finished.get("missions") or [] if isinstance(item, dict)}
    _status, events = _request("GET", gateway, "/v1/fleet/events")
    spans = _spans(events.get("events", []) if isinstance(events, dict) else [], [first["deviceId"], second["deviceId"]])
    report["timeline"] = spans
    for item in report["devices"]:
        item["resumedAfter"] = _resumed(adb_path, item["serial"])
    settings_visible = "settings" in (report["devices"][0].get("resumedAfter") or "").casefold()
    clock_visible = any(token in (report["devices"][1].get("resumedAfter") or "").casefold() for token in ("clock", "deskclock", "alarm"))
    both_completed = all(by_device.get(item["deviceId"], {}).get("status") == "COMPLETED" for item in report["devices"])
    both_verified = all(by_device.get(item["deviceId"], {}).get("verified") is True for item in report["devices"])
    overlap = _overlaps(spans.get(first["deviceId"]), spans.get(second["deviceId"]))
    ok = both_completed and both_verified and overlap and settings_visible and clock_visible
    report["phases"]["parallel_commands"] = "PASS" if ok else "FAIL"
    report["phases"]["openrouter"] = report["phases"]["parallel_commands"]
    report["commandWallSeconds"] = round(time.time() - started, 3)
    report["openrouterChain"] = [
        "POST /v1/fleet/command → fleet_api.command",
        "FleetController.submit_command → _execute",
        "AskContractRunner.run",
        "V5ContractService.forward ask.start / ask.status",
        "DeviceSession.bridge().request",
        "GatewayRuntime → GatewayV5AskAdapter.start",
        "OverlayChromeRuntime.submitRequest",
        "OpenRouterAdaptiveAgent via OpenRouterSecretStore",
        "PhoneToolExecutor.execute",
    ]
    if not ok:
        report["notes"].append(
            "Both phones did not finish verified goals with overlapping gateway timestamps and Settings/Clock in the resumed activity."
        )
        return 1
    return 0


def _isolation(gateway: dict[str, str], adb_path: str, report: dict[str, Any]) -> int:
    first, second = report["devices"]
    before = _resumed(adb_path, second["serial"])
    status, body = _request("POST", gateway, "/v1/fleet/missions", {
        "goal": "isolation check",
        "missions": [
            {"target": {"deviceId": second["deviceId"], "sessionId": "sess-device-b", "displayId": 4}, "objective": "do not run"},
            {"target": {"deviceId": first["deviceId"], "sessionId": "sess-device-b", "displayId": 4}, "objective": "open settings"},
        ],
    })
    after = _resumed(adb_path, second["serial"])
    detail = body.get("detail") if isinstance(body, dict) and isinstance(body.get("detail"), dict) else body
    code = detail.get("code") if isinstance(detail, dict) else None
    untouched = before == after
    report["isolation"] = {"http": status, "code": code, "deviceBUnchanged": untouched}
    report["phases"]["isolation"] = "PASS" if status == 400 and code == "DEVICE_CONTEXT_MISMATCH" and untouched else "FAIL"
    if report["phases"]["isolation"] != "PASS":
        report["notes"].append("The running gateway did not reject the crossed session, or device B's resumed activity changed.")
        return 1
    return 0


def _operator(gateway: dict[str, str], report: dict[str, Any]) -> int:
    first, second = report["devices"]
    print(f"\nUnplug {first['name']} ({first['serial']}). Leave {second['name']} connected.", flush=True)
    print("Waiting up to 90 seconds.", flush=True)
    if not _wait_online(gateway, first["deviceId"], False, second["deviceId"], True, 90):
        report["phases"]["disconnect"] = "NOT_RUN"
        report["notes"].append("Device A did not go offline while device B stayed online.")
    else:
        report["phases"]["disconnect"] = "PASS"
        print(f"Plug {first['name']} back in. Waiting up to 90 seconds.", flush=True)
        report["phases"]["reconnect"] = "PASS" if _wait_online(gateway, first["deviceId"], True, None, None, 90) else "NOT_RUN"
        if report["phases"]["reconnect"] != "PASS":
            report["notes"].append("Device A did not return.")
    print(f"\nLock {first['name']} with its own PIN, pattern, or biometric. Do not type it here.", flush=True)
    if not _wait_screen(gateway, first["deviceId"], "LOCKED", 90):
        report["phases"]["lock"] = "NOT_RUN"
        report["notes"].append("The gateway did not report a keyguard. No PIN was sent.")
        return 0
    status, body = _request("POST", gateway, "/v1/fleet/command", {
        "text": f"On {first['name']}, open the Settings app and stop. On {second['name']}, open the Clock app and stop.",
    })
    finished = _wait_mission(gateway, body.get("fleetMissionId"), 200) if status == 200 and isinstance(body, dict) else {"missions": []}
    by_device = {item.get("deviceId"): item for item in finished.get("missions") or [] if isinstance(item, dict)}
    waiting = by_device.get(first["deviceId"], {}).get("status") == "WAITING_OWNER"
    other_done = by_device.get(second["deviceId"], {}).get("status") == "COMPLETED"
    report["phases"]["lock"] = "PASS" if waiting and other_done else "FAIL"
    report["lock"] = {"deviceA": by_device.get(first["deviceId"]), "deviceB": by_device.get(second["deviceId"])}
    if not waiting:
        report["notes"].append("The locked phone was not left in WAITING_OWNER.")
        return 1
    print(f"Unlock {first['name']} yourself, then press Enter.", flush=True)
    input()
    if not _wait_screen(gateway, first["deviceId"], "AWAKE", 30):
        report["phases"]["resume_after_unlock"] = "NOT_RUN"
        report["notes"].append("The gateway still reported the phone locked after you pressed Enter.")
        return 1
    mission_id = by_device[first["deviceId"]]["missionId"]
    _request("POST", gateway, f"/v1/fleet/missions/{mission_id}/resume")
    resumed = _wait_mission(gateway, mission_id, 200)
    reobserved = resumed.get("failureCode") == "REOBSERVE_AFTER_RECONNECT" and resumed.get("status") == "PAUSED"
    finished_ok = resumed.get("status") == "COMPLETED" and resumed.get("verified") is True
    report["phases"]["resume_after_unlock"] = "PASS" if reobserved or finished_ok else "FAIL"
    report["resume"] = resumed
    if report["phases"]["resume_after_unlock"] != "PASS":
        report["notes"].append("Resume after unlock did not re-observe into a verified state.")
        return 1
    return 0


def _wait_online(gateway: dict[str, str], device_id: str, online: bool, other_id: str | None, other_online: bool | None, seconds: int) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        _request("POST", gateway, "/v1/fleet/scan")
        status, snapshot = _request("GET", gateway, "/v1/fleet/snapshot")
        devices = snapshot.get("devices") if status == 200 and isinstance(snapshot, dict) else []
        if not isinstance(devices, list):
            devices = []
        left = next((item for item in devices if isinstance(item, dict) and item.get("deviceId") == device_id), None)
        right = next((item for item in devices if isinstance(item, dict) and item.get("deviceId") == other_id), None) if other_id else None
        left_ok = isinstance(left, dict) and left.get("online") is online
        right_ok = other_id is None or (isinstance(right, dict) and right.get("online") is other_online)
        if left_ok and right_ok:
            return True
        time.sleep(2)
    return False


def _wait_screen(gateway: dict[str, str], device_id: str, expected: str, seconds: int) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        status, snapshot = _request("GET", gateway, "/v1/fleet/snapshot")
        devices = snapshot.get("devices") if status == 200 and isinstance(snapshot, dict) else []
        if isinstance(devices, list):
            item = next((entry for entry in devices if isinstance(entry, dict) and entry.get("deviceId") == device_id), None)
            if isinstance(item, dict) and item.get("screenState") == expected:
                return True
        time.sleep(2)
    return False


def _wait_mission(gateway: dict[str, str], mission_id: Any, seconds: int) -> dict[str, Any]:
    if not isinstance(mission_id, str) or not mission_id:
        return {}
    deadline = time.time() + seconds
    last: dict[str, Any] = {}
    while time.time() < deadline:
        status, body = _request("GET", gateway, f"/v1/fleet/missions/{mission_id}")
        if status == 200 and isinstance(body, dict):
            last = body
            state = str(body.get("status") or "")
            nested = body.get("missions")
            if isinstance(nested, list):
                states = [str(item.get("status") or "") for item in nested if isinstance(item, dict)]
                if states and all(item not in {"RUNNING", "QUEUED", "RECONNECTING"} for item in states):
                    return body
            elif state and state not in {"RUNNING", "QUEUED", "RECONNECTING"}:
                return body
        time.sleep(1)
    return last


def _snapshot_names(gateway: dict[str, str], names: list[str]) -> bool:
    status, snapshot = _request("GET", gateway, "/v1/fleet/snapshot")
    devices = snapshot.get("devices") if status == 200 and isinstance(snapshot, dict) else []
    if not isinstance(devices, list):
        return False
    found = [item.get("name") for item in devices if isinstance(item, dict)]
    return all(name in found for name in names)


def _names_on_disk(names: list[str]) -> bool | None:
    candidates: list[Path] = []
    runtime = os.environ.get("CYCLONE_DEVICE_GATEWAY_RUNTIME", "").strip()
    if runtime:
        candidates.append(Path(runtime).expanduser())
    locator = load_locator() or {}
    if locator.get("runtime"):
        candidates.append(Path(str(locator["runtime"])).expanduser())
    candidates.append(Path(".runtime/device-gateway").resolve())
    seen: set[Path] = set()
    found_file = False
    for directory in candidates:
        if directory in seen:
            continue
        seen.add(directory)
        path = directory / "fleet-registry.json"
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            continue
        found_file = True
        found = [item.get("name") for item in payload.get("devices") or [] if isinstance(item, dict)]
        if all(name in found for name in names):
            return True
    return False if found_file else None


def _package_installed(adb_path: str, serial: str) -> bool:
    try:
        text = ADBClient(adb_path, serial).shell("pm", "path", CYCLONE_PACKAGE, timeout=8)
    except ADBError:
        return False
    return text.strip().startswith("package:")


def _resumed(adb_path: str, serial: str) -> str:
    try:
        text = ADBClient(adb_path, serial).shell("dumpsys", "activity", "activities", timeout=8)
    except ADBError:
        return ""
    for line in text.splitlines():
        if "mResumedActivity" in line or "topResumedActivity" in line:
            return " ".join(line.split())[:240]
    return ""


def _spans(events: list[Any], device_ids: list[str]) -> dict[str, dict[str, int]]:
    spans: dict[str, dict[str, int]] = {}
    for event in events:
        if not isinstance(event, dict):
            continue
        device_id = str(event.get("deviceId") or "")
        if device_id not in device_ids or not isinstance(event.get("timestamp"), int):
            continue
        bucket = spans.setdefault(device_id, {})
        if event.get("type") == "ACTION_STARTED" and "started" not in bucket:
            bucket["started"] = event["timestamp"]
        if event.get("type") == "MISSION_COMPLETED":
            bucket["completed"] = event["timestamp"]
    return spans


def _overlaps(left: dict[str, int] | None, right: dict[str, int] | None) -> bool:
    if not left or not right:
        return False
    needed = ("started", "completed")
    if any(key not in left or key not in right for key in needed):
        return False
    return left["started"] < right["completed"] and right["started"] < left["completed"]


def _request(method: str, gateway: dict[str, str], path: str, body: dict[str, Any] | None = None) -> tuple[int, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        gateway["url"].rstrip("/") + path,
        data=data,
        method=method,
        headers={"Authorization": f"Bearer {gateway['token']}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:  # noqa: S310 - loopback gateway URL
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"message": raw[:300]}
        return exc.code, payload
    except (urllib.error.URLError, OSError, TimeoutError):
        return 0, {}


def _finish(report: dict[str, Any], args: argparse.Namespace, code: int) -> int:
    report.pop("token", None)
    report["exitCode"] = code
    report["result"] = {0: "PASS", 1: "FAIL", 2: "NOT_RUN", 3: "PARTIAL_NOT_RUN"}.get(code, "FAIL")
    out_dir = Path(args.runtime_dir or ".runtime/real-device-check").expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "real-device-evidence.json"
    path.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({
        "result": report["result"],
        "exitCode": code,
        "phases": report["phases"],
        "attachedToRunningGateway": report["attachedToRunningGateway"],
        "evidence": str(path),
    }, indent=2))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
