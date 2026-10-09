"""The cloud phones this PC keeps connected: the accounts, the phones picked from them, and one keeper loop that opens,
renews and repairs each phone's remote-ADB link. A connected cloud phone is an ordinary fleet phone from there on."""
from __future__ import annotations

import re
import secrets
import socket
import subprocess
import threading
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable

from ..adb.client import ADBClient, ADBError
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from . import lease as L
from .http import Transport, urllib_transport
from .models import PROVIDER_LABELS, PROVIDERS, AdbLink, CloudPhone, ProviderError, normalize_address, phone_key
from .providers import make_provider
from .tunnel import SshTunnel, find_ssh
from .vault import CloudVault

TICK_S = 10.0
LIST_EVERY_S = 300.0
MAX_ACCOUNTS = 20
MAX_KEPT = 32  # the fleet's own limit
LEASE_MINUTES = 7 * 24 * 60
SECRET_LIMIT = 400
PRINTABLE = re.compile(r"^[\x21-\x7e]+$")
ENDPOINT_KEYS = {"vmos": {"list", "adb", "openAdb", "padList", "padCodeChanges", "installedApps", "keepAlive"},
                 "duoplus": {"list"}, "adb": set()}
# The owner's buying, power and backup buttons (alpha.117).
MAX_ORDER = 5  # phones per order
OFFERS_TTL_S = 300.0
PENDING_RENTAL_S = 24 * 3600.0
PENDING_LIST_EVERY_S = 60.0
BACKUP_STEP_S = 15.0
BACKUP_SIZE_WAIT_S = 240.0  # VMOS wants addBackup within 5 minutes of a ready size
BACKUP_GIVE_UP_S = 3 * 3600.0
KEEP_ORDERS = 20
KEEP_BACKUPS = 50
BILLING = ("rental", "timing")


@dataclass
class Link:
    """One kept phone's live state. `lease` holds the provider's secret and never leaves this module."""

    key: str
    state: str = "waiting"
    message: str = "Getting ready…"
    lease: AdbLink | None = None
    tunnel: SshTunnel | None = None
    serial: str | None = None
    attempts: int = 0
    connect_failures: int = 0
    tunnel_deaths: int = 0
    next_try_ms: int = 0
    connected_since_ms: int | None = None
    error_code: str | None = None
    installed_checked: bool = False
    keep_alive_asked: bool = False


def _port_free(port: int) -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


class CloudFleetService:
    def __init__(self, root: Path, fleet: Any, *, care: Any = None, vault: CloudVault | None = None,
                 transport: Transport = urllib_transport, clock: Callable[[], float] = time.time,
                 adb: ADBClient | None = None, ssh: str | None = None, spawn: Callable[..., Any] = subprocess.Popen,
                 port_free: Callable[[int], bool] = _port_free, provider_factory: Callable[..., Any] = make_provider):
        self.root = root
        self.fleet = fleet
        self.care = care
        self.vault = vault or CloudVault(root / "cloud-accounts.dpapi")
        self.transport = transport
        self.clock = clock
        self.adb = adb or getattr(fleet, "inventory_adb", None) or ADBClient("adb", None)
        self.ssh = ssh if ssh is not None else find_ssh()
        self.spawn = spawn
        self.port_free = port_free
        self.provider_factory = provider_factory
        self._lock = threading.RLock()
        self._links: dict[str, Link] = {}
        self._phones: dict[str, dict[str, CloudPhone]] = {}
        self._listed_at: dict[str, float] = {}
        self._account_errors: dict[str, dict[str, Any]] = {}
        self._details: dict[str, dict[str, dict[str, Any]]] = {}
        self._offers: dict[tuple[str, int], tuple[float, list[dict[str, Any]]]] = {}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    # Lifecycle ------------------------------------------------------------------------------------------------

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="cyclone-cloud-fleet", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        with self._lock:
            links = list(self._links.values())
        for link in links:
            if link.tunnel is not None:
                link.tunnel.stop()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.tick()
            except Exception:
                pass
            self._stop.wait(TICK_S)

    def _now_ms(self) -> int:
        return int(self.clock() * 1000)

    # Accounts -------------------------------------------------------------------------------------------------

    def add_account(self, body: dict[str, Any]) -> dict[str, Any]:
        provider = str(body.get("provider") or "")
        if provider not in PROVIDERS:
            raise _bad("Pick VMOS Cloud, DuoPlus or Remote ADB.")
        if len(self.vault.accounts()) >= MAX_ACCOUNTS:
            raise _bad(f"Cyclone keeps at most {MAX_ACCOUNTS} cloud accounts.")
        label = str(body.get("label") or "").strip()[:40] or PROVIDER_LABELS[provider]
        raw = body.get("secrets") if isinstance(body.get("secrets"), dict) else {}
        needed = {"vmos": ("accessKey", "secretKey"), "duoplus": ("apiKey",), "adb": ()}[provider]
        kept: dict[str, str] = {}
        for name in needed:
            value = str(raw.get(name) or "").strip()
            if not value or len(value) > SECRET_LIMIT or not PRINTABLE.match(value):
                raise _bad("Paste the key exactly as the provider shows it.")
            kept[name] = value
        account: dict[str, Any] = {
            "id": "acc_" + secrets.token_hex(6), "provider": provider, "label": label, "createdAtMs": self._now_ms(),
            "installCyclone": body.get("installCyclone") is not False, "secrets": kept, "phones": {}, "addresses": {},
        }
        base_url = str(body.get("baseUrl") or "").strip()
        if base_url:
            if not re.match(r"^https://[A-Za-z0-9.\-]+(:[0-9]{1,5})?$", base_url):
                raise _bad("The API address must be https://host with nothing after it.")
            account["baseUrl"] = base_url
        endpoints = body.get("endpoints") if isinstance(body.get("endpoints"), dict) else {}
        clean = {k: str(v) for k, v in endpoints.items() if k in ENDPOINT_KEYS[provider] and re.match(r"^/[A-Za-z0-9/_\-.]{1,120}$", str(v))}
        if clean:
            account["endpoints"] = clean
        self.vault.put(account)
        if provider != "adb":
            try:
                self.refresh_phones(account["id"])
            except ProviderError:
                pass  # shown on the account; the key is kept so the owner can retry after fixing it on the provider
        return self.account_public(account["id"])

    def remove_account(self, account_id: str) -> dict[str, Any]:
        account = self._require(account_id)
        for remote_id in list((account.get("phones") or {}).keys()):
            self._release(phone_key(account["provider"], account_id, remote_id))
        self.vault.remove(account_id)
        with self._lock:
            self._phones.pop(account_id, None)
            self._account_errors.pop(account_id, None)
        return {"ok": True, "removed": account_id}

    def refresh_phones(self, account_id: str) -> list[dict[str, Any]]:
        account = self._require(account_id)
        provider = self._provider(account)
        try:
            phones = provider.list_phones()
        except ProviderError as exc:
            with self._lock:
                self._account_errors[account_id] = exc.to_dict()
            self._listed_at[account_id] = self.clock()
            raise
        signing = getattr(provider, "signing", None)
        if signing and signing != account.get("signing"):
            self.vault.update(account_id, lambda a: a.update({"signing": signing}))
        phones, details = self._with_details(provider, phones)
        with self._lock:
            self._details[account_id] = details
        self._follow_moves(account, provider, {p.remote_id for p in phones})
        self._adopt_rentals(account_id, details, {p.remote_id: p for p in phones})
        with self._lock:
            self._phones[account_id] = {p.remote_id: p for p in phones}
            self._account_errors.pop(account_id, None)
        self._listed_at[account_id] = self.clock()
        names = {p.remote_id: p.name for p in phones}
        self.vault.update(account_id, lambda a: [a["phones"][r].update({"name": names[r]}) for r in a.get("phones", {}) if r in names])
        return [p.public() for p in phones]

    @staticmethod
    def _with_details(provider: Any, phones: list[CloudPhone]) -> tuple[list[CloudPhone], dict[str, dict[str, Any]]]:
        """The owner's own names, Android versions and paid-until times, where the provider has a second list."""
        if not phones or not hasattr(provider, "phone_details"):
            return phones, {}
        try:
            details = provider.phone_details([p.remote_id for p in phones])
        except ProviderError:
            return phones, {}
        out = []
        for phone in phones:
            extra = details.get(phone.remote_id) or {}
            out.append(replace(phone, name=extra.get("name") or phone.name, android=phone.android or extra.get("android"),
                               paid_until_ms=extra.get("paidUntilMs") or phone.paid_until_ms))
        return out, details

    def _adopt_rentals(self, account_id: str, details: dict[str, dict[str, Any]], listed: dict[str, CloudPhone]) -> None:
        """Phones the owner rented here appear on the account a little later, by equipment id: keep them connected
        (so Cyclone and the skills arrive) and remember they are rentals."""
        account = self._require(account_id)
        pending = account.get("pendingRentals") or []
        if not pending:
            return
        by_equipment = {d.get("equipmentId"): code for code, d in details.items() if d.get("equipmentId") and code in listed}
        now = self._now_ms()

        def change(a: dict[str, Any]) -> None:
            kept = []
            for order in a.get("pendingRentals") or []:
                waiting = []
                for equipment in order.get("equipmentIds") or []:
                    code = by_equipment.get(equipment)
                    if code is None:
                        waiting.append(equipment)
                        continue
                    entry = a["phones"].setdefault(code, {"name": listed[code].name})
                    entry.update({"keep": True, "billing": "rental", "autoRenew": bool(order.get("autoRenew"))})
                if waiting and now - int(order.get("atMs") or now) < PENDING_RENTAL_S * 1000:
                    kept.append({**order, "equipmentIds": waiting})
            a["pendingRentals"] = kept

        self.vault.update(account_id, change)

    def _follow_moves(self, account: dict[str, Any], provider: Any, listed: set[str]) -> None:
        """A kept phone whose padCode VMOS changed is the same phone: its keep, name and local port move to the new
        code, so it reconnects on the same serial and stays the same fleet phone."""
        saved = account.get("phones") or {}
        missing = [r for r, p in saved.items() if p.get("keep") and r not in listed]
        if not missing or not hasattr(provider, "pad_code_changes"):
            return
        try:
            moves = provider.pad_code_changes()
        except ProviderError:
            return
        for old in missing:
            new = moves.get(old)
            if not new or new not in listed or new in saved:
                continue

            def move(a: dict[str, Any], old: str = old, new: str = new) -> None:
                a["phones"][new] = {**a["phones"].pop(old), "movedFrom": old}
                if old in a.get("addresses", {}):
                    a["addresses"][new] = a["addresses"].pop(old)

            self.vault.update(account["id"], move)
            self._release(phone_key(account["provider"], account["id"], old))

    def add_address(self, account_id: str, address: str, name: str | None = None) -> dict[str, Any]:
        """Remote ADB: add a phone by address (it's kept connected). DuoPlus: the address pasted for a listed phone."""
        account = self._require(account_id)
        clean = normalize_address(address)
        if clean is None:
            raise _bad("That isn't an ADB address. It looks like 203.0.113.7:5555.")
        if account["provider"] != "adb":
            raise _bad("Paste the address on the phone it belongs to.")
        remote = clean

        def change(a: dict[str, Any]) -> None:
            a["addresses"][remote] = clean
            a["phones"][remote] = {"keep": True, "name": (name or clean)[:80]}

        self.vault.update(account_id, change)
        return self.account_public(account_id)

    def set_phone(self, account_id: str, remote_id: str, *, keep: bool | None = None, address: str | None = None,
                  billing: str | None = None) -> dict[str, Any]:
        account = self._require(account_id)
        known = self._phones.get(account_id, {}).get(remote_id)
        if known is None and remote_id not in (account.get("phones") or {}) and remote_id not in (account.get("addresses") or {}):
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "That cloud phone isn't in this account.")
        clean = None
        if address is not None:
            clean = normalize_address(address) if address.strip() else ""
            if clean is None:
                raise _bad("That isn't an ADB address. It looks like 203.0.113.7:5555.")
        if keep and sum(1 for a in self.vault.accounts().values() for p in (a.get("phones") or {}).values() if p.get("keep")) >= MAX_KEPT:
            raise _bad(f"Cyclone keeps at most {MAX_KEPT} phones connected.")

        if billing is not None and billing not in BILLING:
            raise _bad("A phone is either rented by the period or pay-for-time.")

        def change(a: dict[str, Any]) -> None:
            entry = a["phones"].setdefault(remote_id, {"keep": False, "name": known.name if known else remote_id})
            if keep is not None:
                entry["keep"] = keep
            if billing is not None:
                entry["billing"] = billing
            if clean is not None:
                if clean:
                    a["addresses"][remote_id] = clean
                else:
                    a["addresses"].pop(remote_id, None)

        self.vault.update(account_id, change)
        key = phone_key(account["provider"], account_id, remote_id)
        if keep is False or clean is not None:
            self._release(key)
        with self._lock:
            link = self._links.get(key)
            if link is not None:
                link.next_try_ms = 0
        return self.account_public(account_id)

    # The owner's buttons: renting, power, renewal, backup ---------------------------------------------------
    # Reached only from the owner's Glass page (the `/v1/cloud` routes); no model, MCP or agent tool reaches them.
    # Money moves only when the owner confirmed the exact total VMOS asks right now.

    def offers(self, account_id: str, android: int = 13, *, fresh: bool = False) -> list[dict[str, Any]]:
        account = self._require(account_id)
        provider = self._rent_provider(account)
        cached = self._offers.get((account_id, android))
        if cached and not fresh and self.clock() - cached[0] < OFFERS_TTL_S:
            return cached[1]
        offers = provider.offers(android)
        self._offers[(account_id, android)] = (self.clock(), offers)
        return offers

    def rent(self, account_id: str, body: dict[str, Any]) -> dict[str, Any]:
        """Rent new phones: `kind` "rental" (a period: a day, a week, a month…) or "timing" (pay-for-time). The
        new phones are kept connected, so Cyclone and the skills arrive on them by themselves."""
        account = self._require(account_id)
        provider = self._rent_provider(account)
        kind, android = str(body.get("kind") or ""), int(body.get("android") or 13)
        count = int(body.get("count") or 1)
        if kind not in BILLING:
            raise _bad("Pick a rental period or pay-for-time.")
        if not 1 <= count <= MAX_ORDER:
            raise _bad(f"Rent 1 to {MAX_ORDER} phones at a time.")
        kept = sum(1 for a in self.vault.accounts().values() for p in (a.get("phones") or {}).values() if p.get("keep"))
        if kept + count > MAX_KEPT:
            raise _bad(f"Cyclone keeps at most {MAX_KEPT} phones connected.")
        config, sku = self._find_sku(self.offers(account_id, android, fresh=True), kind, int(body.get("skuId") or 0))
        total = sku["priceCents"] * count
        self._check_price(total, body.get("expectedPriceCents"))
        now = self._now_ms()
        order = {"atMs": now, "kind": kind, "plan": config["name"], "period": sku["label"], "count": count,
                 "android": android, "totalCents": total}
        if kind == "rental":
            auto_renew = bool(body.get("autoRenew", False))
            equipment = provider.rent(sku["skuId"], android, count, auto_renew=auto_renew)

            def change(a: dict[str, Any]) -> None:
                a.setdefault("pendingRentals", []).append({"atMs": now, "equipmentIds": equipment, "autoRenew": auto_renew})
                a["orders"] = ((a.get("orders") or []) + [order])[-KEEP_ORDERS:]
        else:
            codes = provider.rent_timing(sku["skuId"], android, count, group=int(config.get("group") or 1))

            def change(a: dict[str, Any]) -> None:
                for code in codes:
                    a["phones"].setdefault(code, {"name": code}).update(
                        {"keep": True, "billing": "timing", "poweredOff": False, "poweredOnAtMs": now})
                a["orders"] = ((a.get("orders") or []) + [order])[-KEEP_ORDERS:]

        self.vault.update(account_id, change)
        try:
            self.refresh_phones(account_id)
        except ProviderError:
            pass
        return self.account_public(account_id)

    def renew(self, account_id: str, remote_id: str, sku_id: int, expected_cents: Any) -> dict[str, Any]:
        """Pay another period for one rented phone."""
        account = self._require(account_id)
        provider = self._rent_provider(account)
        phone = self._listed(account_id, remote_id)
        detail = (self._details.get(account_id) or {}).get(remote_id) or {}
        if not detail.get("equipmentId"):
            raise _bad("VMOS hasn't said which device this is yet. Refresh, then try again.")
        android = _android_number(phone.android)
        _config, sku = self._find_sku(self.offers(account_id, android, fresh=True), "rental", int(sku_id or 0))
        self._check_price(sku["priceCents"], expected_cents)
        entry = (account.get("phones") or {}).get(remote_id) or {}
        provider.renew(int(detail["equipmentId"]), sku["skuId"], android, auto_renew=bool(entry.get("autoRenew")))
        order = {"atMs": self._now_ms(), "kind": "renewal", "plan": phone.name, "period": sku["label"], "count": 1,
                 "android": android, "totalCents": sku["priceCents"]}
        self.vault.update(account_id, lambda a: a.update({"orders": ((a.get("orders") or []) + [order])[-KEEP_ORDERS:]}))
        try:
            self.refresh_phones(account_id)
        except ProviderError:
            pass
        return self.account_public(account_id)

    def set_auto_renew(self, account_id: str, remote_id: str, on: bool) -> dict[str, Any]:
        account = self._require(account_id)
        self._rent_provider(account).set_auto_renew(self._known(account, remote_id), bool(on))
        self.vault.update(account_id, lambda a: a["phones"].setdefault(remote_id, {"keep": False, "name": remote_id})
                          .update({"autoRenew": bool(on)}))
        return self.account_public(account_id)

    def power(self, account_id: str, remote_id: str, on: bool) -> dict[str, Any]:
        """Power a pay-for-time phone on or off. Off keeps everything on it; Cyclone lets go of its link first."""
        account = self._require(account_id)
        provider = self._rent_provider(account)
        entry = (account.get("phones") or {}).get(remote_id) or {}
        if entry.get("billing") != "timing":
            raise _bad("Only pay-for-time phones are powered on and off here.")
        phone = self._known(account, remote_id)
        key = phone_key(account["provider"], account_id, remote_id)
        if not on:
            self._release(key)
        provider.power(phone, bool(on))
        now = self._now_ms()
        self.vault.update(account_id, lambda a: a["phones"].setdefault(remote_id, {"keep": True, "name": phone.name})
                          .update({"poweredOff": not on, "poweredOnAtMs": now if on else None}))
        if on:
            self._listed_at[account_id] = 0  # look again soon: the phone is starting
            with self._lock:
                link = self._links.get(key)
                if link is not None:
                    link.next_try_ms = 0
        return self.account_public(account_id)

    def start_backup(self, account_id: str, remote_id: str, name: str | None = None) -> dict[str, Any]:
        """Back up one phone at VMOS (cloud storage), when the owner asks. VMOS runs one backup per account at a
        time; Cyclone sizes it first, as VMOS asks, then follows it to done."""
        account = self._require(account_id)
        provider = self._rent_provider(account)
        if account.get("backupJob"):
            raise _bad("A backup is already running on this account. VMOS does one at a time.")
        phone = self._listed(account_id, remote_id)
        if phone.power != "running":
            raise _bad("The phone has to be running to be backed up.")
        now = self._now_ms()
        label = (name or "").strip()[:60] or f"{phone.name} · Cyclone backup"
        provider.backup_size_start(phone)
        job = {"remoteId": remote_id, "stage": "sizing", "startedAtMs": now, "nextMs": now + int(BACKUP_STEP_S * 1000),
               "name": label}
        self.vault.update(account_id, lambda a: a.update({"backupJob": job}))
        return self.account_public(account_id)

    def _advance_backup(self, account: dict[str, Any]) -> None:
        job = dict(account.get("backupJob") or {})
        now = self._now_ms()
        if not job or now < int(job.get("nextMs") or 0):
            return
        provider = self._provider(account)
        remote_id = str(job.get("remoteId"))
        phone = self._phones.get(account["id"], {}).get(remote_id) or CloudPhone(account["provider"], remote_id, remote_id)
        started = int(job.get("startedAtMs") or now)
        try:
            if job.get("stage") == "sizing":
                status, size = provider.backup_size(phone)
                if status == "calculating" and now - started < BACKUP_SIZE_WAIT_S * 1000:
                    job["nextMs"] = now + int(BACKUP_STEP_S * 1000)
                else:
                    job.update({"stage": "saving", "batchId": provider.backup_start(phone, str(job.get("name") or "")),
                                "sizeBytes": size, "nextMs": now + int(BACKUP_STEP_S * 1000)})
            else:
                progress = provider.backup_progress(str(job.get("batchId") or ""), phone)
                if progress["state"] == "done":
                    record = {"backupId": progress["backupId"], "remoteId": remote_id, "name": job.get("name"), "atMs": now,
                              "sizeBytes": job.get("sizeBytes")}
                    return self._finish_backup(account["id"], True, None, remote_id, record)
                if progress["state"] == "failed":
                    return self._finish_backup(account["id"], False, progress["message"], remote_id)
                if now - started > BACKUP_GIVE_UP_S * 1000:
                    return self._finish_backup(account["id"], False, "VMOS is taking very long. Check the backup in the VMOS console.", remote_id)
                job["nextMs"] = now + int(BACKUP_STEP_S * 2000)
        except ProviderError as exc:
            # VMOS saying no (no storage left, phone not running) ends the backup; only a network hiccup waits.
            if not exc.retryable or exc.code == "PROVIDER_REFUSED":
                return self._finish_backup(account["id"], False, exc.message, remote_id)
            job["nextMs"] = now + int(BACKUP_STEP_S * 4000)
        self.vault.update(account["id"], lambda a: a.update({"backupJob": job}))

    def _finish_backup(self, account_id: str, ok: bool, message: str | None, remote_id: str,
                       record: dict[str, Any] | None = None) -> None:
        now = self._now_ms()

        def change(a: dict[str, Any]) -> None:
            a["backupJob"] = None
            a["lastBackup"] = {"remoteId": remote_id, "ok": ok, "message": message, "atMs": now}
            if record:
                a["backups"] = ((a.get("backups") or []) + [record])[-KEEP_BACKUPS:]

        self.vault.update(account_id, change)

    def restore(self, account_id: str, remote_id: str, backup_id: str) -> dict[str, Any]:
        """Put one of this phone's own backups back onto it. It replaces what is on the phone now. A backup is never
        put onto another phone here: that would copy one phone's Cyclone pairing onto another (plan 56 V3 does
        clones, with re-enrollment)."""
        account = self._require(account_id)
        provider = self._rent_provider(account)
        own = [b for b in account.get("backups") or [] if b.get("backupId") == backup_id and b.get("remoteId") == remote_id]
        if not own:
            raise _bad("Cyclone restores a phone only from a backup it made of that same phone.")
        if account.get("backupJob"):
            raise _bad("Wait for the backup that is running to finish.")
        phone = self._known(account, remote_id)
        self._release(phone_key(account["provider"], account_id, remote_id))
        provider.restore(backup_id, phone)
        self._listed_at[account_id] = 0
        return self.account_public(account_id)

    def _rent_provider(self, account: dict[str, Any]) -> Any:
        provider = self._provider(account)
        if account["provider"] != "vmos" or not hasattr(provider, "offers"):
            raise _bad("Renting, power and backups are for VMOS Cloud accounts.")
        return provider

    def _known(self, account: dict[str, Any], remote_id: str) -> CloudPhone:
        """A listed phone, or one this account already holds (a just-rented phone VMOS doesn't list yet)."""
        phone = self._phones.get(account["id"], {}).get(remote_id)
        if phone is not None:
            return phone
        entry = (account.get("phones") or {}).get(remote_id)
        if entry is None:
            return self._listed(account["id"], remote_id)
        return CloudPhone(account["provider"], remote_id, entry.get("name") or remote_id)

    def _listed(self, account_id: str, remote_id: str) -> CloudPhone:
        phone = self._phones.get(account_id, {}).get(remote_id)
        if phone is None:
            self.refresh_phones(account_id)
            phone = self._phones.get(account_id, {}).get(remote_id)
        if phone is None:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "That cloud phone isn't in this account.")
        return phone

    @staticmethod
    def _find_sku(offers: list[dict[str, Any]], kind: str, sku_id: int) -> tuple[dict[str, Any], dict[str, Any]]:
        for config in offers:
            for sku in config["rentals" if kind == "rental" else "timing"]:
                if sku["skuId"] == sku_id:
                    return config, sku
        raise _bad("VMOS doesn't offer that any more. Look at the offers again.")

    @staticmethod
    def _check_price(total: int, expected: Any) -> None:
        if not isinstance(expected, int) or isinstance(expected, bool) or expected != total:
            raise _bad(f"VMOS now asks {_money(total)}. Check the price and confirm again.")

    # Status ---------------------------------------------------------------------------------------------------

    def status(self) -> dict[str, Any]:
        return {"ssh": bool(self.ssh), "security": self.vault.security_mode,
                "accounts": [self.account_public(account_id) for account_id in self.vault.accounts()]}

    def account_public(self, account_id: str) -> dict[str, Any]:
        account = self._require(account_id)
        listed = self._phones.get(account_id, {})
        saved = account.get("phones") or {}
        phones = []
        for remote_id in sorted(set(listed) | set(saved), key=lambda r: (listed.get(r).name if r in listed else saved[r].get("name", r)).lower()):
            phone = listed.get(remote_id)
            entry = saved.get(remote_id) or {}
            item = phone.public() if phone else {"provider": account["provider"], "remoteId": remote_id,
                                                 "name": entry.get("name") or remote_id, "android": None, "power": "unknown",
                                                 "address": None}
            item["address"] = (account.get("addresses") or {}).get(remote_id) or item.get("address")
            item["keep"] = bool(entry.get("keep"))
            item.update(self._phone_extras(account, remote_id, entry))
            item.update(self._link_public(phone_key(account["provider"], account_id, remote_id), bool(entry.get("keep"))))
            phones.append(item)
        return {"id": account_id, "provider": account["provider"], "providerLabel": PROVIDER_LABELS[account["provider"]],
                "label": account.get("label"), "installCyclone": bool(account.get("installCyclone", True)),
                "hasKey": bool(account.get("secrets")), "error": self._account_errors.get(account_id), "phones": phones,
                "canRent": account["provider"] == "vmos",
                "pendingRentals": sum(len(o.get("equipmentIds") or []) for o in account.get("pendingRentals") or []),
                "orders": list(reversed((account.get("orders") or [])[-5:]))}

    @staticmethod
    def _phone_extras(account: dict[str, Any], remote_id: str, entry: dict[str, Any]) -> dict[str, Any]:
        job = account.get("backupJob") if (account.get("backupJob") or {}).get("remoteId") == remote_id else None
        last = account.get("lastBackup") if (account.get("lastBackup") or {}).get("remoteId") == remote_id else None
        return {
            "billing": entry.get("billing") if entry.get("billing") in BILLING else None,
            "poweredOff": bool(entry.get("poweredOff")),
            "poweredOnAtMs": entry.get("poweredOnAtMs") if isinstance(entry.get("poweredOnAtMs"), int) else None,
            "autoRenew": entry.get("autoRenew") if isinstance(entry.get("autoRenew"), bool) else None,
            "backups": [{"backupId": b["backupId"], "name": b.get("name"), "atMs": b.get("atMs"), "sizeBytes": b.get("sizeBytes")}
                        for b in reversed(account.get("backups") or []) if b.get("remoteId") == remote_id][:10],
            "backup": {"stage": job.get("stage"), "startedAtMs": job.get("startedAtMs")} if job else None,
            "lastBackup": {"ok": bool(last.get("ok")), "message": last.get("message"), "atMs": last.get("atMs")} if last else None,
        }

    def _link_public(self, key: str, keep: bool) -> dict[str, Any]:
        with self._lock:
            link = self._links.get(key)
        if not keep:
            return {"state": "off", "message": "Not kept connected.", "serial": None, "deviceId": None, "expiresAtMs": None}
        if link is None:
            return {"state": "waiting", "message": "Connecting…", "serial": None, "deviceId": None, "expiresAtMs": None}
        device_id = None
        if link.serial:
            session = self.fleet.find_by_serial(link.serial) if hasattr(self.fleet, "find_by_serial") else None
            device_id = getattr(session, "device_id", None)
        return {"state": link.state, "message": link.message, "serial": link.serial, "deviceId": device_id,
                "expiresAtMs": link.lease.expires_at_ms if link.lease else None, "errorCode": link.error_code}

    def metadata_for_serial(self, serial: str) -> dict[str, str] | None:
        """For the fleet: a serial that is one of Cyclone's cloud links is a CLOUD phone of that provider."""
        with self._lock:
            for key, link in self._links.items():
                if link.serial and link.serial == serial:
                    provider, _account, remote = key.split(":", 2)
                    return {"source": "CLOUD", "provider": provider, "instanceId": remote}
        return None

    # The keeper ----------------------------------------------------------------------------------------------

    def tick(self) -> None:
        accounts = self.vault.accounts()
        wanted: set[str] = set()
        adb_states: dict[str, str] | None = None
        for account_id, account in accounts.items():
            kept = [r for r, p in (account.get("phones") or {}).items() if p.get("keep")]
            pending = bool(account.get("pendingRentals"))
            every = PENDING_LIST_EVERY_S if pending else LIST_EVERY_S
            if account["provider"] != "adb" and (kept or pending or account_id not in self._phones) \
                    and self.clock() - self._listed_at.get(account_id, 0) >= every:
                try:
                    self.refresh_phones(account_id)
                except ProviderError:
                    pass
                account = self.vault.account(account_id) or account
                kept = [r for r, p in (account.get("phones") or {}).items() if p.get("keep")]
            if account.get("backupJob"):
                self._advance_backup(account)
            for remote_id in kept:
                key = phone_key(account["provider"], account_id, remote_id)
                wanted.add(key)
                if adb_states is None:
                    adb_states = self._adb_states()
                self._keep(account, remote_id, key, adb_states)
        with self._lock:
            stale = [key for key in self._links if key not in wanted]
        for key in stale:
            self._release(key)

    def _keep(self, account: dict[str, Any], remote_id: str, key: str, adb_states: dict[str, str]) -> None:
        now = self._now_ms()
        with self._lock:
            link = self._links.setdefault(key, Link(key))
        if link.next_try_ms > now:
            return
        phone = self._phones.get(account["id"], {}).get(remote_id) or CloudPhone(
            account["provider"], remote_id, (account.get("phones", {}).get(remote_id) or {}).get("name") or remote_id)
        entry = (account.get("phones") or {}).get(remote_id) or {}
        if entry.get("billing") == "timing" and (entry.get("poweredOff") or phone.power == "stopped"):
            return self._set(link, "off", "Powered off: a pay-for-time phone costs nothing while it is off. Power it on here.",
                             wait_s=60)
        if phone.power == "stopped":
            return self._set(link, "off", "This cloud phone is off. Turn it on at the provider.", wait_s=60)
        # The provider's own state first: opening ADB on a phone that is starting, broken or deleted only fails.
        if phone.power == "starting":
            return self._set(link, "waiting", "The provider is still starting this cloud phone…", wait_s=30)
        if phone.power == "abnormal":
            return self._set(link, "off", "The provider reports this cloud phone as abnormal. Restart it there.", wait_s=60)
        if phone.power == "gone":
            return self._set(link, "off", "This cloud phone was deleted at the provider. Remove it here.", wait_s=300)
        try:
            renew = link.lease is not None and L.renew_at_ms(link.lease) is not None and now >= L.renew_at_ms(link.lease)
            if link.lease is None or renew:
                self._set(link, "renewing" if renew else "opening",
                          "Renewing the ADB key…" if renew else f"Opening ADB on {PROVIDER_LABELS[account['provider']]}…")
                lease = self._provider(account).open_adb(phone, LEASE_MINUTES)
                self._use(link, account, remote_id, lease)
            if link.lease is not None and link.lease.kind == "ssh":
                if link.tunnel is None or not link.tunnel.alive():
                    if link.tunnel is not None:
                        code, words = link.tunnel.failure()
                        link.tunnel.stop()
                        link.tunnel_deaths += 1
                        if code == "TUNNEL_KEY_REFUSED" or link.tunnel_deaths >= 3:
                            link.lease, link.tunnel_deaths = None, 0
                        raise ProviderError(code, words)
                    self._start_tunnel(link, account, remote_id)
                    return self._set(link, "tunnel", "Starting the secure tunnel…", wait_s=3)
            serial = link.serial
            if serial is None:
                raise ProviderError("ADB_NO_ADDRESS", "The provider gave no ADB address.")
            if adb_states.get(serial) != "device":
                self._set(link, "connecting", "Connecting adb…")
                if not self._adb_connect(serial):
                    link.connect_failures += 1
                    if link.connect_failures >= 3 and link.lease is not None and link.lease.expires_at_ms is not None:
                        link.lease, link.connect_failures = None, 0  # a fresh key and tunnel, not the same dead address
                    raise ProviderError("ADB_CONNECT_FAILED", "adb couldn't reach the phone yet.")
                adb_states[serial] = "device"
            self._connected(link, account, now)
            self._ask_keep_alive(link, account, phone)
        except ProviderError as exc:
            link.attempts += 1
            wait = L.backoff_s(link.attempts) if exc.retryable else L.NEEDS_YOU_RETRY_S
            state = "waiting" if exc.retryable else "needs_you"
            suffix = f" Trying again in {L.in_words(wait * 1000)}." if exc.retryable else ""
            link.error_code = exc.code
            self._set(link, state, exc.message + suffix, wait_s=wait)

    def _use(self, link: Link, account: dict[str, Any], remote_id: str, lease: AdbLink) -> None:
        old_serial = link.serial
        if link.tunnel is not None:
            link.tunnel.stop()
            link.tunnel = None
        link.lease, link.tunnel_deaths = lease, 0
        if lease.kind == "direct":
            link.serial = lease.address
        else:
            port = self._local_port(account, remote_id, link.key)
            link.serial = f"127.0.0.1:{port}"
        if old_serial and old_serial != link.serial:
            self._disconnect(old_serial)

    def _start_tunnel(self, link: Link, account: dict[str, Any], remote_id: str) -> None:
        if not self.ssh:
            raise ProviderError("SSH_MISSING", "This PC has no OpenSSH client. Turn on Windows' optional feature "
                                "\"OpenSSH Client\", then try again.", retryable=False)
        port = int(str(link.serial).rsplit(":", 1)[1])
        tunnel = SshTunnel(link.lease, port, ssh=self.ssh, scratch=self.root / "tunnels", spawn=self.spawn)
        tunnel.start()
        link.tunnel = tunnel

    def _connected(self, link: Link, account: dict[str, Any], now: int) -> None:
        if link.state != "connected":
            link.connected_since_ms = now
        link.attempts, link.connect_failures, link.error_code = 0, 0, None
        left = ""
        if link.lease is not None and link.lease.expires_at_ms is not None:
            renew = L.renew_at_ms(link.lease) or link.lease.expires_at_ms
            left = f" · key renews in {L.in_words(renew - now)}"
        self._set(link, "connected", "Connected" + left, wait_s=TICK_S)
        if account.get("installCyclone", True) and not link.installed_checked:
            self._install_if_missing(link)

    def _ask_keep_alive(self, link: Link, account: dict[str, Any], phone: CloudPhone) -> None:
        """Once Cyclone is on a provider phone, ask the provider to keep Cyclone's service running (VMOS
        `setKeepAliveApp`), once per connection. Best effort: a phone without it still works, it may only be stopped
        under memory pressure."""
        if link.keep_alive_asked or not link.installed_checked or not account.get("installCyclone", True):
            return
        provider = self._provider(account)
        if not hasattr(provider, "keep_alive"):
            link.keep_alive_asked = True
            return
        try:
            provider.keep_alive([phone])
            link.keep_alive_asked = True
        except ProviderError as exc:
            link.keep_alive_asked = not exc.retryable

    def _install_if_missing(self, link: Link) -> None:
        """A cloud phone without Cyclone gets this PC's verified build through phone care, once."""
        if self.care is None or not hasattr(self.fleet, "find_by_serial"):
            link.installed_checked = True
            return
        session = self.fleet.find_by_serial(link.serial or "")
        if session is None:
            try:
                self.fleet.refresh_once(source="cloud")
            except Exception:
                return
            session = self.fleet.find_by_serial(link.serial or "")
            if session is None:
                return
        link.installed_checked = True
        try:
            from ..phone_care.updater import read_installed

            if read_installed(session.adb) is None:
                self.care.start_update(session.device_id)
                link.message = link.message.replace("Connected", "Connected · installing Cyclone", 1)
        except Exception:
            link.installed_checked = False

    def _release(self, key: str) -> None:
        with self._lock:
            link = self._links.pop(key, None)
        if link is None:
            return
        if link.tunnel is not None:
            link.tunnel.stop()
        if link.serial:
            self._disconnect(link.serial)

    def _adb_connect(self, serial: str) -> bool:
        """`adb connect` exits 0 even when it fails; only its words say whether it connected."""
        try:
            output = str(self.adb.connect(serial, timeout=10)).lower()
        except ADBError:
            return False
        return ("connected to" in output or "already connected" in output) and "cannot" not in output and "failed" not in output

    def _disconnect(self, serial: str) -> None:
        try:
            self.adb.run(["disconnect", serial], timeout=10, use_serial=False)
        except Exception:
            pass

    def _adb_states(self) -> dict[str, str]:
        try:
            return {d.serial: d.state for d in self.adb.devices()}
        except Exception:
            return {}

    def _local_port(self, account: dict[str, Any], remote_id: str, key: str) -> int:
        saved = ((account.get("phones") or {}).get(remote_id) or {}).get("localPort")
        with self._lock:
            used = {int(str(l.serial).rsplit(":", 1)[1]) for k, l in self._links.items()
                    if k != key and l.serial and l.serial.startswith("127.0.0.1:")}
        if isinstance(saved, int) and saved not in used:
            return saved
        used |= {p.get("localPort") for a in self.vault.accounts().values() for r, p in (a.get("phones") or {}).items()
                 if isinstance(p.get("localPort"), int) and not (a["id"] == account["id"] and r == remote_id)}
        port = L.stable_port(phone_key(account["provider"], account["id"], remote_id), used, self.port_free)
        if port is None:
            raise ProviderError("NO_LOCAL_PORT", "This PC has no free local port for the tunnel.")
        self.vault.update(account["id"], lambda a: a["phones"].setdefault(remote_id, {"keep": True}).update({"localPort": port}))
        return port

    def _set(self, link: Link, state: str, message: str, *, wait_s: float = 0) -> None:
        link.state, link.message = state, message
        link.next_try_ms = self._now_ms() + int(wait_s * 1000)

    def _provider(self, account: dict[str, Any]) -> Any:
        return self.provider_factory(account, transport=self.transport, clock=self.clock)

    def _require(self, account_id: str) -> dict[str, Any]:
        account = self.vault.account(account_id)
        if account is None:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_NOT_FOUND, "That cloud account isn't on this PC.")
        return account


def _money(cents: int) -> str:
    return f"${cents / 100:,.2f}"


def _android_number(text: str | None) -> int:
    digits = re.findall(r"\d+", text or "")
    number = int(digits[0]) if digits else 13
    return number if number in (13, 14, 15) else 13


def _bad(message: str) -> DesktopRuntimeError:
    return DesktopRuntimeError(RuntimeErrorCode.INVALID_REQUEST, message)
