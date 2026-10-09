"""VMOS Cloud OpenAPI: list the account's cloud phones, open remote ADB on one, and keep it fit for Cyclone.

Signing is VMOS's V2 scheme (OpenAPI "Getting Started"): three headers, `X-Access-Key`, `X-Timestamp` (unix seconds)
and `X-Sign` = lowerHex(SHA-256(SK + timestamp + path + body)). The older HMAC scheme (credential scope
`<date>/armcloud-paas/request`) is kept as a fallback for accounts that still use it: when V2 is refused as a bad
signature, the same call is tried once with HMAC, and the scheme that worked is reported back (`signing`) so the
service can remember it. The paths are the documented defaults and can be overridden per account (`endpoints`), so a
change on VMOS's side is a settings fix, not a release.

Only connectors that serve Cyclone's own rules are here (plan 56 §2): reading phones and their state, opening ADB,
following a changed padCode, checking Cyclone's installed version and keeping its service alive; and, behind the
owner's own confirmed buttons in Glass (alpha.117), renting phones (by the week or month, or pay-for-time), powering
pay-for-time phones on and off, renewing, and backing a phone up or restoring its own backup. Nothing here taps,
types, spoofs a device or reaches an app's accounts: Cyclone acts on the phone only through its own executor.

Remote ADB answers with an SSH command (`ssh … user@host -p port -L local:adb-proxy:port -Nf`), a key (the SSH
password) and an expiry. Cyclone asks for the longest lease VMOS allows (7 days) and renews it before it ends.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import shlex
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Callable
from urllib.parse import urlparse

from ..http import Transport, call_json, urllib_transport
from ..models import AdbLink, CloudPhone, ProviderError, normalize_address

BASE_URL = "https://api.vmoscloud.com"
ENDPOINTS = {
    "list": "/vcpcloud/api/padApi/infos",
    "adb": "/vcpcloud/api/padApi/adb",
    "openAdb": "/vcpcloud/api/padApi/openOnlineAdb",
    "padList": "/vcpcloud/api/padApi/userPadList",
    "padCodeChanges": "/vcpcloud/api/padApi/queryPadIdChangeRecords",
    "installedApps": "/vcpcloud/api/padApi/listInstalledApp",
    "keepAlive": "/vcpcloud/api/padApi/setKeepAliveApp",
    # The owner's buttons (alpha.117): offers, renting, pay-for-time power, renewal, backup.
    "offers": "/vcpcloud/api/padApi/getCloudGoodList",
    "rent": "/vcpcloud/api/padApi/createMoneyOrder",
    "rentTiming": "/vcpcloud/api/padApi/createByTimingOrder",
    "powerOn": "/vcpcloud/api/padApi/timingPadOn",
    "powerOff": "/vcpcloud/api/padApi/timingPadOff",
    "autoRenewOn": "/vcpcloud/api/padApi/openAutoRenew",
    "autoRenewOff": "/vcpcloud/api/padApi/closeAutoRenew",
    "backupSize": "/vcpcloud/api/padApi/backupCalculate",
    "backupSizeResult": "/vcpcloud/api/padApi/queryBackupCalculateResult",
    "backup": "/vcpcloud/api/padApi/addBackup",
    "backupProgress": "/vcpcloud/api/padApi/queryBackupBatch",
    "restore": "/vcpcloud/api/padApi/clonePadBackup",
}
# The few VMOS lists that are GET requests, signed over their raw query string.
GET_ENDPOINTS = {"offers"}
# Android 13 (SDK 33) is Cyclone's floor; VMOS names images "Android13" and so on.
ANDROID_VERSIONS = (13, 14, 15)
# Cyclone's own service, which VMOS's keep-alive guards against being stopped (Android 13–15 images).
CYCLONE_PACKAGE = "com.cyclone.mobile"
CYCLONE_SERVICE = "com.cyclone.mobile/com.cyclone.mobile.CycloneAccessibilityService"
# V2 auth answers (HTTP 401 with one of these codes). Only a signature problem is worth one HMAC try.
AUTH_WORDS = {
    2019: "VMOS didn't accept the request signature.",
    2031: "VMOS doesn't know this access key. Copy it again from Developer → API.",
    2032: "VMOS says a sign-in header is missing.",
    2033: "VMOS says this PC's clock is off. Set Windows to set the time automatically, then try again.",
    1116: "VMOS refused this PC's IP address. Add it to the API IP allow list in the VMOS console.",
}
SIGNATURE_CODES = {2019, 2032}
SERVICE = "armcloud-paas"
CONTENT_TYPE = "application/json;charset=UTF-8"
SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"
MAX_LEASE_MINUTES = 7 * 24 * 60
PAGE_ROWS = 100
MAX_PAGES = 20
# VMOS reports times as Beijing time when it gives no zone; reading them that way is also the earlier (safer) guess.
PROVIDER_TZ = timezone(timedelta(hours=8))
# `padStatus` as VMOS documents it (OpenAPI instance status): 10 running, 11 restarting, 12 resetting, 13 upgrading,
# 14 abnormal, 15 not ready, 16 backing up, 17 restoring, 18 shut down, 19 shutting down, 20 booting, 23 deleting,
# 24 delete failed, 25 deleted, 26 cloning, -1 deleted. Before 2026-10-08 Cyclone read 14 as "stopped" and did not know 18.
RUNNING = {
    10: "running",
    11: "starting", 12: "starting", 13: "starting", 15: "starting", 16: "starting", 17: "starting", 20: "starting", 26: "starting",
    14: "abnormal",
    18: "stopped", 19: "stopped",
    23: "gone", 24: "abnormal", 25: "gone", -1: "gone",
}


def sign_v2(secret_key: str, timestamp: str, path: str, body_or_query: str) -> str:
    """X-Sign: lowerHex(SHA-256(SK + timestamp + path + bodyOrQuery)), plain concatenation. Pure; checked against
    the example in VMOS's own guide."""
    return hashlib.sha256((secret_key + timestamp + path + body_or_query).encode("utf-8")).hexdigest()


def v2_headers(access_key: str, secret_key: str, timestamp: str, path: str, body: str, *, sign_body: bool = True) -> dict[str, str]:
    """VMOS signs a few endpoints (its file upload and command ones) without their body; Cyclone calls none of them
    today, so every call here signs its body."""
    return {"Content-Type": "application/json", "X-Access-Key": access_key, "X-Timestamp": timestamp,
            "X-Sign": sign_v2(secret_key, timestamp, path, body if sign_body else "")}


def sign(access_key: str, secret_key: str, host: str, body: str, x_date: str) -> dict[str, str]:
    """The older HMAC scheme's headers for one call (the fallback). Pure."""
    short_date = x_date[:8]
    content_sha = hashlib.sha256(body.encode("utf-8")).hexdigest()
    canonical = (f"host:{host}\nx-date:{x_date}\ncontent-type:{CONTENT_TYPE}\nsignedHeaders:{SIGNED_HEADERS}\n"
                 f"x-content-sha256:{content_sha}")
    scope = f"{short_date}/{SERVICE}/request"
    to_sign = f"HMAC-SHA256\n{x_date}\n{scope}\n{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"

    def mac(key: bytes, text: str) -> bytes:
        return hmac.new(key, text.encode("utf-8"), hashlib.sha256).digest()

    signing_key = mac(mac(mac(secret_key.encode("utf-8"), short_date), SERVICE), "request")
    signature = hmac.new(signing_key, to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    return {
        "content-type": CONTENT_TYPE,
        "x-date": x_date,
        "x-host": host,
        "x-content-sha256": content_sha,
        "authorization": f"HMAC-SHA256 Credential={access_key}, SignedHeaders={SIGNED_HEADERS}, Signature={signature}",
    }


class VmosCloud:
    name = "VMOS Cloud"

    def __init__(self, access_key: str, secret_key: str, *, base_url: str | None = None, endpoints: dict[str, str] | None = None,
                 transport: Transport = urllib_transport, clock: Callable[[], float] = time.time, signing: str | None = None):
        if not access_key or not secret_key:
            raise ProviderError("PROVIDER_AUTH", "Add the VMOS access key and secret key.", retryable=False)
        self._ak, self._sk = access_key, secret_key
        self.base_url = (base_url or BASE_URL).rstrip("/")
        self.endpoints = {**ENDPOINTS, **{k: v for k, v in (endpoints or {}).items() if k in ENDPOINTS and str(v).startswith("/")}}
        self.transport = transport
        self.clock = clock
        # "v2" (VMOS's current scheme) unless this account is known to use the older HMAC one.
        self.signing = "hmac" if signing == "hmac" else "v2"

    def _call(self, endpoint: str, payload: dict[str, Any] | None = None) -> Any:
        payload = payload or {}
        try:
            return self._signed_call(endpoint, payload, self.signing)
        except _SignatureRefused as refused:
            other = "hmac" if self.signing == "v2" else "v2"
            try:
                data = self._signed_call(endpoint, payload, other)
            except _SignatureRefused:
                raise refused.error from None
            self.signing = other
            return data

    def _signed_call(self, endpoint: str, payload: dict[str, Any], scheme: str) -> Any:
        method = "GET" if endpoint in GET_ENDPOINTS else "POST"
        # A GET is signed over its query exactly as sent: Cyclone builds it from plain numbers and words, unencoded.
        body = "&".join(f"{k}={v}" for k, v in payload.items()) if method == "GET" else \
            json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
        path = self.endpoints[endpoint]
        if scheme == "v2":
            headers = v2_headers(self._ak, self._sk, str(int(self.clock())), path, body)
            if method == "GET":
                headers.pop("Content-Type", None)
        else:
            x_date = datetime.fromtimestamp(self.clock(), timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            headers = sign(self._ak, self._sk, urlparse(self.base_url).netloc, body, x_date)
        answer: dict[str, Any] = {}

        def tap(method: str, url: str, sent: dict[str, str], raw_body: bytes, timeout: float) -> tuple[int, bytes]:
            status, raw = self.transport(method, url, sent, raw_body, timeout)
            answer["code"] = _answer_code(raw)
            return status, raw

        try:
            if method == "GET":
                return call_json(tap, "GET", self.base_url + path + (f"?{body}" if body else ""), headers, b"", provider=self.name)
            return call_json(tap, "POST", self.base_url + path, headers, body.encode("utf-8"), provider=self.name)
        except ProviderError as exc:
            code = answer.get("code")
            if code in AUTH_WORDS:
                exc = ProviderError("PROVIDER_AUTH", AUTH_WORDS[code], retryable=False)
            if exc.code == "PROVIDER_AUTH" and code not in AUTH_WORDS.keys() - SIGNATURE_CODES:
                raise _SignatureRefused(exc) from None
            raise exc from None

    def list_phones(self) -> list[CloudPhone]:
        phones: list[CloudPhone] = []
        for page in range(1, MAX_PAGES + 1):
            data = self._call("list", {"page": page, "rows": PAGE_ROWS})
            rows = _rows(data)
            phones.extend(p for p in (_phone(row) for row in rows) if p is not None)
            total = data.get("total") if isinstance(data, dict) else None
            if len(rows) < PAGE_ROWS or (isinstance(total, int) and len(phones) >= total):
                break
        return phones

    def open_adb(self, phone: CloudPhone, minutes: int = MAX_LEASE_MINUTES) -> AdbLink:
        """A 1–7 day remote-ADB link. When VMOS answers without a complete link, its guide says to switch ADB on
        (`openOnlineAdb`) first: Cyclone does that once and asks again."""
        minutes = max(24 * 60, min(int(minutes), MAX_LEASE_MINUTES))
        link = self._adb_link(phone, minutes)
        if link is None:
            self._call("openAdb", {"padCodes": [phone.remote_id], "openStatus": 1})
            link = self._adb_link(phone, minutes)
        if link is None:
            raise ProviderError("ADB_NOT_OPEN", "VMOS didn't open ADB for this phone. Check that remote ADB is allowed for the account.")
        return link

    def _adb_link(self, phone: CloudPhone, minutes: int) -> AdbLink | None:
        issued = int(self.clock() * 1000)
        data = self._call("adb", {"padCode": phone.remote_id, "enable": True, "expireMinutes": minutes})
        item = data[0] if isinstance(data, list) and data else data
        if not isinstance(item, dict):
            return None
        return parse_adb_answer(item, issued_ms=issued, requested_minutes=minutes)

    def phone_details(self, remote_ids: list[str] | None = None) -> dict[str, dict[str, Any]]:
        """Per padCode: the owner's name for it, its Android version and when its paid time ends (`userPadList`).
        `infos` carries none of these. Best effort: the list works without it."""
        data = self._call("padList", {})
        out: dict[str, dict[str, Any]] = {}
        for row in _rows(data):
            code = str(row.get("padCode") or "").strip()
            if not code or (remote_ids is not None and code not in remote_ids):
                continue
            ends = row.get("signExpirationTimeTamp")
            equipment = row.get("equipmentId")
            out[code] = {
                "name": str(row.get("padName") or "").strip()[:80] or None,
                "android": str(row.get("androidVersion") or "").strip()[:24] or None,
                "paidUntilMs": int(ends) if isinstance(ends, (int, float)) and ends > 0 else parse_time_ms(row.get("signExpirationTime")),
                "equipmentId": int(equipment) if isinstance(equipment, int) and equipment > 0 else None,
                "plan": str(row.get("configName") or "").strip()[:60] or None,
            }
        return out

    def pad_code_changes(self) -> dict[str, str]:
        """old padCode → new padCode for the last three days (VMOS can move a phone to a new code; the phone and its
        data stay the same). Followed to the newest code when it moved twice."""
        data = self._call("padCodeChanges", {})
        moves: dict[str, str] = {}
        for row in _rows(data):
            old, new = str(row.get("oldPadCode") or "").strip(), str(row.get("newPadCode") or "").strip()
            if old and new and old != new and len(new) <= 64:
                moves.setdefault(old, new)  # newest first, as VMOS orders them
        for old in list(moves):
            seen, new = {old}, moves[old]
            while new in moves and new not in seen:
                seen.add(new)
                new = moves[new]
            moves[old] = new
        return moves

    def installed_version(self, phone: CloudPhone, package: str = CYCLONE_PACKAGE) -> dict[str, Any] | None:
        """`{versionName, versionCode, state}` for one package as VMOS sees it right now, or None if it is not there."""
        data = self._call("installedApps", {"padCodes": [phone.remote_id], "appName": ""})
        for row in _rows(data) if isinstance(data, list) else ([data] if isinstance(data, dict) else []):
            if str(row.get("padCode") or phone.remote_id) != phone.remote_id:
                continue
            for app in row.get("apps") or []:
                if isinstance(app, dict) and app.get("packageName") == package:
                    state = {0: "installed", 1: "installing", 2: "downloading"}.get(app.get("appState"), "installed")
                    return {"versionName": str(app.get("versionName") or "")[:40] or None,
                            "versionCode": _int(str(app.get("versionCode") or ""), 0) or None, "state": state}
        return None

    def keep_alive(self, phones: list[CloudPhone], service: str = CYCLONE_SERVICE) -> None:
        """Ask VMOS to keep Cyclone's service running on these phones (Android 13–15 images)."""
        if phones:
            self._call("keepAlive", {"padCodes": [p.remote_id for p in phones], "applyAllInstances": False,
                                     "appInfos": [{"serverName": service}]})


    # The owner's buttons ------------------------------------------------------------------------------------
    # Each is reached only from an owner route in Glass; the service checks the price the owner confirmed first.

    def offers(self, android: int = 13) -> list[dict[str, Any]]:
        """The phones VMOS sells for one Android version: per configuration, its rental periods (a day, a week, a
        month…) and its pay-for-time rates, with prices in cents as VMOS lists them. Sold-out ones are left out."""
        if android not in ANDROID_VERSIONS:
            raise ProviderError("PROVIDER_REJECTED", "Cyclone needs Android 13, 14 or 15.", retryable=False)
        data = self._call("offers", {"androidVersion": android})
        group = data.get("goodId") if isinstance(data, dict) else None
        configs = data.get("configs") if isinstance(data, dict) else None
        out = []
        for config in configs if isinstance(configs, list) else []:
            if not isinstance(config, dict) or config.get("sellOutFlag") is True:
                continue
            config_id = config.get("configId")
            if not isinstance(config_id, int):
                continue
            rentals = [r for r in (_sku(t, "rental") for t in config.get("goodTimes") or []) if r]
            timing = [] if config.get("timingSellOutFlag") is False else \
                [r for r in (_sku(t, "timing") for t in config.get("timingGoodTimes") or []) if r]
            if not rentals and not timing:
                continue
            out.append({"configId": config_id, "name": str(config.get("configName") or f"Plan {config_id}")[:60],
                        "android": android, "group": group if isinstance(group, int) else 1,
                        "rentals": rentals, "timing": timing})
        return out

    def rent(self, sku_id: int, android: int, count: int, *, auto_renew: bool) -> list[int]:
        """Rent new phones for a period (`createMoneyOrder`, `goodId` = the period's SKU). Answers the new phones'
        equipment ids; their padCodes appear in `userPadList` once VMOS has made them."""
        data = self._call("rent", {"androidVersionName": f"Android{android}", "goodId": sku_id, "goodNum": count,
                                   "autoRenew": bool(auto_renew)})
        return [int(r["equipmentId"]) for r in _rows(data) if isinstance(r.get("equipmentId"), int)]

    def renew(self, equipment_id: int, sku_id: int, android: int, *, auto_renew: bool) -> None:
        """Pay another period for one rented phone (`createMoneyOrder` with its `equipmentId`)."""
        self._call("rent", {"androidVersionName": f"Android{android}", "goodId": sku_id, "goodNum": 1,
                            "autoRenew": bool(auto_renew), "equipmentId": str(equipment_id)})

    def set_auto_renew(self, phone: CloudPhone, on: bool) -> None:
        self._call("autoRenewOn" if on else "autoRenewOff", {"padCode": phone.remote_id})

    def rent_timing(self, sku_id: int, android: int, count: int, *, group: int = 1) -> list[str]:
        """Pay-for-time phones (`createByTimingOrder`): billed only while powered on. Answers their padCodes."""
        data = self._call("rentTiming", {"goodId": group, "goodTimeId": sku_id, "goodNum": count, "androidVersion": 20 + android})
        return [str(r["padCode"]) for r in _rows(data) if str(r.get("padCode") or "").strip()]

    def power(self, phone: CloudPhone, on: bool) -> None:
        """Power a pay-for-time phone on or off. On never asks for a "new device" (`defCode` 0); off always keeps
        the phone's environment (`isBackUp` 1), so nothing on it is lost."""
        if on:
            self._call("powerOn", {"padCodes": [phone.remote_id], "defCode": 0})
        else:
            self._call("powerOff", {"padCodes": [phone.remote_id], "isBackUp": 1})

    def backup_size_start(self, phone: CloudPhone) -> None:
        self._call("backupSize", {"padCode": phone.remote_id})

    def backup_size(self, phone: CloudPhone) -> tuple[str, int | None]:
        """("calculating" | "ready" | "failed", bytes)."""
        data = self._call("backupSizeResult", {"padCode": phone.remote_id})
        item = data if isinstance(data, dict) else {}
        size = item.get("totalSize")
        status = str(item.get("status") or "calculating")
        return (status if status in {"calculating", "ready", "failed"} else "calculating",
                int(size) if isinstance(size, (int, float)) and size > 0 else None)

    def backup_start(self, phone: CloudPhone, name: str) -> str:
        data = self._call("backup", {"vcPadBackupList": [{"padCode": phone.remote_id, "name": name[:60]}]})
        batch = str((data or {}).get("batchId") or "") if isinstance(data, dict) else ""
        if not batch:
            raise ProviderError("PROVIDER_ANSWER", "VMOS didn't start the backup.")
        return batch

    def backup_progress(self, batch_id: str, phone: CloudPhone) -> dict[str, Any]:
        """{"state": running | done | failed, "backupId", "message"} for this phone in a backup batch."""
        data = self._call("backupProgress", {"batchId": batch_id})
        items = data.get("items") if isinstance(data, dict) else None
        for item in items if isinstance(items, list) else []:
            if isinstance(item, dict) and item.get("padCode") == phone.remote_id:
                status = item.get("status")
                if status == 2 and item.get("backupId"):
                    return {"state": "done", "backupId": str(item["backupId"])[:120], "message": None}
                if status == 3:
                    return {"state": "failed", "backupId": None, "message": str(item.get("failMsg") or "VMOS couldn't back it up.")[:160]}
                return {"state": "running", "backupId": None, "message": None}
        if isinstance(data, dict) and data.get("taskStatus") == -1:
            return {"state": "failed", "backupId": None, "message": "VMOS couldn't back it up."}
        return {"state": "running", "backupId": None, "message": None}

    def restore(self, backup_id: str, phone: CloudPhone) -> None:
        """Put a backup back onto a phone (`clonePadBackup`). It replaces what is on the phone now."""
        self._call("restore", {"vcPadBackupList": [{"backupId": backup_id}], "pads": [{"padCode": phone.remote_id}]})


def _sku(raw: Any, kind: str) -> dict[str, Any] | None:
    if not isinstance(raw, dict) or not isinstance(raw.get("id"), int):
        return None
    price = raw.get("goodPrice", raw.get("currentPrice"))
    minutes = raw.get("goodTime")
    if not isinstance(price, (int, float)) or price < 0 or not isinstance(minutes, int) or minutes <= 0:
        return None
    return {"skuId": raw["id"], "kind": kind, "label": str(raw.get("showContent") or f"{minutes} min")[:40],
            "minutes": minutes, "priceCents": int(round(price)), "phonesPerOrder": int(raw.get("equipmentNumber") or 1),
            "autoRenew": raw.get("autoRenew") is True}


class _SignatureRefused(Exception):
    def __init__(self, error: ProviderError):
        super().__init__(error.message)
        self.error = error


def _answer_code(raw: bytes) -> int | None:
    try:
        value = json.loads(raw.decode("utf-8")) if raw else None
    except (UnicodeDecodeError, ValueError):
        return None
    code = value.get("code") if isinstance(value, dict) else None
    try:
        return int(code) if code is not None else None
    except (TypeError, ValueError):
        return None


def _rows(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, list):
        return [r for r in data if isinstance(r, dict)]
    if isinstance(data, dict):
        for key in ("pageData", "list", "records", "rows", "data"):
            value = data.get(key)
            if isinstance(value, list):
                return [r for r in value if isinstance(r, dict)]
    return []


def _phone(row: dict[str, Any]) -> CloudPhone | None:
    code = str(row.get("padCode") or "").strip()
    if not code or len(code) > 64:
        return None
    name = str(row.get("padName") or row.get("name") or row.get("remark") or code)[:80]
    android = row.get("androidVersion") or row.get("imageVersion") or row.get("osVersion")
    status = row.get("padStatus")
    try:
        power = RUNNING.get(int(status), "unknown") if status is not None else "unknown"
    except (TypeError, ValueError):
        power = "unknown"
    return CloudPhone("vmos", code, name, str(android)[:24] if android else None, power)


def parse_adb_answer(item: dict[str, Any], *, issued_ms: int, requested_minutes: int) -> AdbLink | None:
    """An AdbLink from VMOS's `{command, key, adb, expireTime, enable}`. The expiry is the earlier of what VMOS says
    and what Cyclone asked for, so a misread time can only make Cyclone renew early, never late."""
    if item.get("enable") is False:
        return None
    ours = issued_ms + requested_minutes * 60_000
    theirs = parse_time_ms(item.get("expireTime"))
    expires = min(ours, theirs) if theirs and theirs > issued_ms else ours
    command = str(item.get("command") or "").strip()
    if command.startswith("ssh"):
        ssh = parse_ssh_command(command)
        if ssh is None:
            return None
        return AdbLink("ssh", issued_ms, expires, ssh_user=ssh["user"], ssh_host=ssh["host"], ssh_port=ssh["port"],
                       target_host=ssh["target_host"], target_port=ssh["target_port"], secret=str(item.get("key") or "") or None)
    address = normalize_address(str(item.get("adb") or command))
    if address and not address.startswith(("localhost:", "127.")):
        return AdbLink("direct", issued_ms, expires, address=address)
    return None


def parse_ssh_command(command: str) -> dict[str, Any] | None:
    """user, host, port and the `-L` target from VMOS's SSH line. Its own local port and `-f` are not used: Cyclone
    picks a stable local port per phone and keeps the tunnel in the foreground so it can watch it."""
    try:
        parts = shlex.split(command, posix=True)
    except ValueError:
        return None
    user = host = target_host = None
    port, target_port = 22, None
    i = 1
    while i < len(parts):
        token = parts[i]
        if token == "-p" and i + 1 < len(parts):
            port = _int(parts[i + 1], 22)
            i += 2
            continue
        if token.startswith("-L"):
            spec = token[2:] or (parts[i + 1] if i + 1 < len(parts) else "")
            i += 1 if token[2:] else 2
            bits = spec.split(":")
            if len(bits) >= 3:
                target_host, target_port = bits[-2], _int(bits[-1], 0) or None
            continue
        if token in {"-o", "-i", "-l", "-F"}:
            i += 2
            continue
        if not token.startswith("-") and "@" in token:
            user, host = token.rsplit("@", 1)
        i += 1
    if not (user and host and target_host and target_port) or not 1 <= port <= 65535:
        return None
    return {"user": user, "host": host, "port": port, "target_host": target_host, "target_port": target_port}


def parse_time_ms(value: Any) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)) or (isinstance(value, str) and value.strip().isdigit()):
        number = int(float(value))
        return number if number > 10**12 else number * 1000
    text = str(value).strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%SZ", "%Y/%m/%d %H:%M:%S"):
        try:
            parsed = datetime.strptime(text, fmt)
        except ValueError:
            continue
        zone = timezone.utc if text.endswith("Z") else PROVIDER_TZ
        return int(parsed.replace(tzinfo=zone).timestamp() * 1000)
    return None


def _int(value: str, fallback: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback
