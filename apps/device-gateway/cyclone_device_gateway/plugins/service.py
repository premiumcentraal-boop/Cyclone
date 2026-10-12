"""Plugins from GitHub (plan 50 §5): look up, install, update, roll back and remove plugins, atomically.

Rules the code keeps:
1. Nothing outside ``staging/`` changes until the bytes are verified and unpacked.
2. A version folder is placed with one same-volume rename and never changes after that.
3. Which version is current is one row in ``plugins.db``, switched in one transaction (the "flip").
4. On every start, :meth:`reconcile` makes disk follow the database and empties ``staging/``.
5. A failure before the flip leaves the old version running untouched; the Port Hub record is rewritten from the
   database on every start, so it can never disagree for long.
"""
from __future__ import annotations

import hashlib
import os
import secrets
import shutil
import stat
import sys
import threading
import time
import uuid
import zipfile
from pathlib import Path
from typing import Any, Callable

from ..ports import kit
from ..ports.hub import PortHub, PortsError, _conformance
from . import kindex, kpackage
from .github import GitHub, GitHubError, parse_source
from .host import HostError, PluginHost, Running, Spec
from .index import IndexClient
from .store import PluginsStore, now_ms

KEEP_FILES = {"plugins.db", "plugins.db-wal", "plugins.db-shm", "settings.dpapi", "settings.tmp"}
KEEP_DIRS = {"staging", "logs"}
STEPS = ("resolving", "downloading", "verifying", "unpacking", "placing", "starting", "checking", "switching",
         "connecting", "done")


class PluginsError(Exception):
    """A plain reason shown to the owner."""


class Crash(BaseException):
    """Test-only: a simulated power loss. Never caught by the pipeline, so no cleanup runs."""


def _rmtree(path: Path) -> None:
    """Removes a folder, read-only files included (placed versions are sealed read-only)."""
    def unlock(fn: Callable, target: str, _exc: Any) -> None:
        os.chmod(target, stat.S_IWRITE | stat.S_IREAD | stat.S_IEXEC)
        fn(target)

    if path.exists():
        if sys.version_info >= (3, 12):
            shutil.rmtree(path, onexc=unlock)
        else:
            shutil.rmtree(path, onerror=unlock)


def _seal(folder: Path) -> None:
    """Marks a placed version read-only, so nothing (the plugin included) changes it by accident."""
    for p in folder.rglob("*"):
        if p.is_file():
            mode = p.stat().st_mode
            p.chmod((mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH)) | stat.S_IREAD)


def _sha_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _runtime_version() -> str:
    from ..terminal.release import installed_version

    return installed_version()


def _older(runtime: str, needed: str) -> bool:
    from ..terminal.release import version_key

    have, want = version_key(runtime), version_key(needed)
    return have is not None and want is not None and have < want


class PluginsService:
    def __init__(self, root: Path, hub: PortHub, *, github: GitHub | None = None, store: PluginsStore | None = None,
                 host: PluginHost | None = None, trusted_keys: dict[str, str] | None = None,
                 checker: Callable[[str, str], list[Any]] = _conformance,
                 fault: Callable[[str], None] = lambda step: None,
                 spawn: Callable[[Callable[[], None]], None] | None = None,
                 runtime_version: Callable[[], str] = _runtime_version) -> None:
        if kpackage is None:
            raise RuntimeError("The Cyclone Ports kit (tools/cyclone-ports-sdk) is not installed.")
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self.staging = root / "staging"
        self.staging.mkdir(exist_ok=True)
        self.store = store or PluginsStore(root)
        self.github = github or GitHub()
        self.index = IndexClient(self.store, self.github, trusted=trusted_keys)
        self.host = host or PluginHost(root / "logs", on_state=self._host_state)
        self.hub = hub
        self._check = checker
        self._fault = fault
        self._spawn = spawn or (lambda fn: threading.Thread(target=fn, name="cyclone-plugins-job", daemon=True).start())
        self._runtime_version = runtime_version
        self._job_lock = threading.Lock()
        self._cards: dict[str, dict[str, Any]] = {}
        self._state: dict[str, tuple[str, str]] = {}
        self._stop = threading.Event()
        self._ticker: threading.Thread | None = None

    # ---- lifecycle ---------------------------------------------------------------------------------------------------

    def start(self) -> None:
        """Reconciles, then starts the installed plugins in the background so Glass and the phone link never wait."""
        self.reconcile()

        def boot() -> None:
            if self.index.due():
                self.index.refresh()
            self.enforce_revocations()
            for record in self.store.all_installed():
                if record["enabled"] and self._state.get(record["name"], ("", ""))[0] not in ("broken", "revoked"):
                    self._start_current(record["name"])
            self.host.start_monitor()

        self._spawn(boot)
        self._ticker = threading.Thread(target=self._tick, name="cyclone-plugins-index", daemon=True)
        self._ticker.start()

    def _tick(self) -> None:
        while not self._stop.wait(60):
            try:
                if self.index.due():
                    self.index.refresh()
                    self.enforce_revocations()
            except Exception:  # noqa: BLE001 - the index never takes the runtime down
                pass

    def stop(self) -> None:
        self._stop.set()
        self.host.close()
        self.store.close()

    # ---- reconcile: disk follows the database ------------------------------------------------------------------------

    def reconcile(self) -> None:
        self.store.fail_running_jobs("Cyclone restarted while this ran.")
        _rmtree(self.staging)
        self.staging.mkdir(parents=True, exist_ok=True)
        installed = {r["name"]: r for r in self.store.all_installed()}
        for child in self.root.iterdir():
            if child.name in KEEP_DIRS or (child.is_file() and child.name in KEEP_FILES):
                continue
            if child.is_file():
                child.unlink(missing_ok=True)
                continue
            record = installed.get(child.name)
            keep = {record["current"], record["previous"]} - {None} if record else set()
            versions = child / "versions"
            if versions.is_dir():
                for folder in versions.iterdir():
                    if folder.name not in keep:
                        _rmtree(folder)
            if record is None and not (child / "data").exists():
                _rmtree(child)
        with self.store.transaction() as db:
            for name, record in installed.items():
                keep = [v for v in (record["current"], record["previous"]) if v]
                db.execute(f"DELETE FROM version WHERE name = ? AND version NOT IN ({','.join('?' * len(keep))})",
                           (name, *keep))
                if record["previous"] and not (self.root / name / "versions" / record["previous"]).is_dir():
                    db.execute("UPDATE installed SET previous = NULL WHERE name = ?", (name,))
            db.execute("DELETE FROM version WHERE name NOT IN (SELECT name FROM installed)")
        for name in self.store.secrets.names():
            if name not in installed:
                self.store.put_secret_settings(name, {})
        for name, record in installed.items():
            version = self.store.version(name, record["current"])
            if version is None or (version["package"]["kind"] == "local"
                                   and not (self.root / name / "versions" / record["current"]).is_dir()):
                self._state[name] = ("broken", "Its files are missing. Install it again.")

    def enforce_revocations(self) -> None:
        for record in self.store.all_installed():
            version = self.store.version(record["name"], record["current"])
            hit = self.index.revoked(version["sha256"]) if version else None
            if hit:
                self.host.stop(record["name"])
                self.hub.managed_state(record["name"], "revoked", hit["reason"])
                self._state[record["name"]] = ("revoked", f"Cyclone stopped this version: {hit['reason']}")

    # ---- jobs --------------------------------------------------------------------------------------------------------

    def _job(self, action: str, name: str | None, work: Callable[[str], dict[str, Any]]) -> dict[str, Any]:
        job_id = "job_" + uuid.uuid4().hex[:16]
        self.store.new_job(job_id, action, name)

        def run() -> None:
            with self._job_lock:  # one install, update or rollback at a time
                try:
                    result = work(job_id)
                    self.store.update_job(job_id, state="done", step="done", detail="", result=result)
                except (PluginsError, GitHubError, HostError, PortsError, kpackage.PackageError, OSError,
                        zipfile.BadZipFile) as exc:
                    self.store.update_job(job_id, state="failed", detail=str(exc)[:500])
                except Exception as exc:  # noqa: BLE001 - every failure ends the job with a reason
                    self.store.update_job(job_id, state="failed", detail=f"Something went wrong ({type(exc).__name__}).")

        self._spawn(run)
        return self.job(job_id)

    def job(self, job_id: str) -> dict[str, Any]:
        job = self.store.job(job_id) if isinstance(job_id, str) else None
        if job is None:
            raise PluginsError("No such job.")
        return {"id": job["id"], "action": job["action"], "name": job["name"], "state": job["state"],
                "step": job["step"], "detail": job["detail"], "done": job["done"], "total": job["total"],
                "result": job["result"]}

    def _step(self, job_id: str, step: str, **extra: Any) -> None:
        self.store.update_job(job_id, step=step, **extra)
        self._fault(step)

    # ---- look up -----------------------------------------------------------------------------------------------------

    def resolve(self, source: Any) -> dict[str, Any]:
        """Starts a job that downloads the release file into staging (nothing is unpacked or run) and returns the card
        Glass shows before anything is installed."""
        parsed = parse_source(source)
        return self._job("resolve", parsed.get("name"), lambda job_id: self._resolve(job_id, parsed))

    def _resolve(self, job_id: str, parsed: dict[str, str]) -> dict[str, Any]:
        self._step(job_id, "resolving")
        if parsed["kind"] == "file":
            return self._resolve_file(job_id, Path(parsed["path"]))
        entry = expected = None
        repo, tag = parsed.get("repo", ""), parsed.get("tag", "")
        if parsed["kind"] == "index":
            name = parsed["name"]
            entry = self.index.usable_entry(name)
            current = self.store.installed(name)
            if entry is None and current is None:
                raise PluginsError(f"{name} isn't in the Cyclone list. Paste its GitHub link instead.")
            if entry is None:
                repo, tag = current["repo"] or "", ""
                if not repo:
                    raise PluginsError("Cyclone doesn't know where this plugin came from. Install it again by link.")
            else:
                repo = entry["repo"]
                expected = kindex.newest(entry)
                tag = expected["tag"]
        release = self.github.release(repo, tag)
        if expected is not None:
            asset = next((a for a in release["assets"] if a["name"] == expected["asset"]), None)
        else:
            matches = [a for a in release["assets"] if a["name"].endswith(".cyclone.zip")]
            if len(matches) != 1:
                raise PluginsError("That release has no Cyclone plugin file." if not matches
                                   else "That release has more than one Cyclone plugin file.")
            asset = matches[0]
        if asset is None or not asset["url"]:
            raise PluginsError("The release is missing its Cyclone plugin file.")
        limit = kpackage.LIMITS["zip_bytes"]
        if asset["size"] and asset["size"] > limit:
            raise PluginsError("The plugin file is larger than 200 MB.")
        part = self.staging / f"{job_id}.part"
        self._step(job_id, "downloading", done=0, total=asset["size"])
        last = [0.0]

        def progress(n: int) -> None:
            if time.monotonic() - last[0] > 0.5:
                last[0] = time.monotonic()
                self.store.update_job(job_id, done=n)

        sha = self.github.download(asset["url"], part, limit, progress)
        self._step(job_id, "verifying")
        try:
            if expected is not None and sha != expected["sha256"]:
                raise PluginsError("The file doesn't match the one Cyclone checked. Nothing was installed.")
            if asset["sha256"] and sha != asset["sha256"]:
                raise PluginsError("The file doesn't match what GitHub says it should be. Nothing was installed.")
            hit = self.index.revoked(sha)
            if hit:
                raise PluginsError(f"Cyclone blocked this file: {hit['reason']}")
            with zipfile.ZipFile(part) as zf:
                found = kpackage.read_package(zf)
                readme = zf.read("README.md")[:4000].decode("utf-8", errors="replace")
        except BaseException:
            part.unlink(missing_ok=True)
            raise
        package, manifest, schema = found["package"], found["manifest"], found["schema"]
        problems = []
        if asset["name"] != kpackage.asset_name(package["name"], package["version"], package["kind"]):
            problems.append("The file's name doesn't match the plugin inside it.")
        if expected is not None and (package["name"] != parsed.get("name") or package["version"] != expected["version"]):
            problems.append("The file isn't the plugin and version the Cyclone list names.")
        if problems:
            part.unlink(missing_ok=True)
            raise PluginsError(" ".join(problems))
        staged = self.staging / f"{sha}.zip"
        os.replace(part, staged)
        verified = self.index.verified_version(package["name"], sha) is not None
        card = self._card(package, manifest, schema, sha, staged.stat().st_size, repo, release["tag"], asset["name"],
                          verified, (expected or {}).get("minRuntime"), readme)
        self._cards[sha] = card
        return card

    def _resolve_file(self, job_id: str, source: Path) -> dict[str, Any]:
        limit = kpackage.LIMITS["zip_bytes"]
        if source.stat().st_size > limit:
            raise PluginsError("The plugin file is larger than 200 MB.")
        part = self.staging / f"{job_id}.part"
        self._step(job_id, "downloading", done=0, total=source.stat().st_size)
        shutil.copyfile(source, part)
        sha = _sha_file(part)
        self._step(job_id, "verifying")
        try:
            hit = self.index.revoked(sha)
            if hit:
                raise PluginsError(f"Cyclone blocked this file: {hit['reason']}")
            with zipfile.ZipFile(part) as zf:
                found = kpackage.read_package(zf)
                readme = zf.read("README.md")[:4000].decode("utf-8", errors="replace")
            package = found["package"]
            if source.name != kpackage.asset_name(package["name"], package["version"], package["kind"]):
                raise PluginsError("The file's name doesn't match the plugin inside it.")
        except BaseException:
            part.unlink(missing_ok=True)
            raise
        staged = self.staging / f"{sha}.zip"
        os.replace(part, staged)
        card = self._card(package, found["manifest"], found["schema"], sha, staged.stat().st_size, "", "", source.name,
                          False, None, readme)
        card["source"] = "file"
        self._cards[sha] = card
        return card

    def _card(self, package: dict[str, Any], manifest: dict[str, Any], schema: dict[str, Any] | None, sha: str,
              size: int, repo: str, tag: str, asset: str, verified: bool, min_runtime: str | None,
              readme: str = "") -> dict[str, Any]:
        remote = package["kind"] == "remote"
        serves = []
        for s in manifest.get("serves") or []:
            spec = kit.port_spec(s.get("port"), s.get("way"))
            if spec is not None:
                serves.append({"port": spec.name, "way": spec.way, "sensitivity": spec.sensitivity,
                               "summary": spec.summary})
        current = self.store.installed(package["name"])
        update = None
        if current is not None:
            old = self.store.version(package["name"], current["current"])
            old_ports = {s.get("port") for s in (old or {}).get("manifest", {}).get("serves") or []}
            update = {"from": current["current"],
                      "newPorts": sorted({s["port"] for s in serves} - old_ports),
                      "permissionsChanged": kpackage.permissions((old or {}).get("package") or {}) != kpackage.permissions(package),
                      "consent": current["consent"]}
        try:
            hub_record = self.hub.store.plugin(package["name"])
        except Exception:  # noqa: BLE001
            hub_record = None
        conflict = bool(hub_record) and not hub_record.get("managed")
        return {
            "sha256": sha, "name": package["name"], "version": package["version"], "title": package["title"],
            "summary": package["summary"], "kind": package["kind"], "repo": repo, "tag": tag, "asset": asset,
            "bytes": size, "verified": verified, "homepage": package.get("homepage", ""),
            "license": package.get("license", ""), "permissions": kpackage.permissions(package),
            "serves": serves, "needsPersonal": bool((manifest.get("needs") or {}).get("personal")),
            "remote": remote, "endpoint": package["remote"]["endpoint"] if remote else None,
            "settings": self._public_schema(schema), "update": update, "readme": readme,
            "minRuntime": min_runtime, "conflict": "A plugin with this name was added by its address under Ports. "
                                                   "Remove it there first." if conflict else "",
        }

    @staticmethod
    def _public_schema(schema: dict[str, Any] | None) -> dict[str, Any] | None:
        if not schema:
            return None
        return {"properties": schema.get("properties") or {}, "required": schema.get("required") or [],
                "title": schema.get("title", ""), "description": schema.get("description", "")}

    # ---- install and update ------------------------------------------------------------------------------------------

    def install(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise PluginsError("Send the plugin you looked up and your answers.")
        sha = body.get("sha256")
        card = self._cards.get(sha) if isinstance(sha, str) else None
        if card is None or not (self.staging / f"{sha}.zip").is_file():
            raise PluginsError("Look the plugin up again first.")
        if body.get("accept") is not True:
            raise PluginsError("Agree to what the plugin may do first.")
        if not card["verified"] and body.get("trustUnverified") is not True:
            raise PluginsError("This plugin isn't checked by Cyclone. Tick that you trust its source first.")
        if card["conflict"]:
            raise PluginsError(card["conflict"])
        allowed = body.get("allowed", [])
        served = {s["port"] for s in card["serves"]}
        if not isinstance(allowed, list) or not all(isinstance(p, str) and p in served for p in allowed):
            raise PluginsError("Pick ports this plugin serves.")
        if card["minRuntime"] and _older(self._runtime_version(), card["minRuntime"]):
            raise PluginsError(f"This plugin needs Cyclone {card['minRuntime']} or newer. Update Cyclone first.")
        settings = body.get("settings", {})
        values, hidden = self._merge_settings(card["name"], card["settings"], settings)
        return self._job("install", card["name"],
                         lambda job_id: self._install(job_id, card, sorted(set(allowed)), values, hidden))

    def _merge_settings(self, name: str, schema: dict[str, Any] | None, changes: Any) -> tuple[dict[str, Any], dict[str, str]]:
        """Applies ``changes`` (a secret field: text sets it, null clears it, absent keeps it) over the saved values
        and the defaults; returns (plain values, secret values), checked against the schema."""
        if not isinstance(changes, dict):
            raise PluginsError("Settings must be an object.")
        props = (schema or {}).get("properties") or {}
        secret_names = {n for n, f in props.items() if f.get("x-cyclone-secret") is True}
        record = self.store.installed(name)
        plain = dict(kpackage.default_settings({"properties": props}))
        plain.update({k: v for k, v in ((record or {}).get("settings") or {}).items() if k in props and k not in secret_names})
        hidden = {k: v for k, v in self.store.secret_settings(name).items() if k in secret_names}
        for key, value in changes.items():
            if key not in props:
                raise PluginsError(f"{key} isn't a setting of this plugin.")
            target = hidden if key in secret_names else plain
            if value is None or value == "":
                target.pop(key, None)
            else:
                target[key] = value
        if schema:
            problems = kpackage.validate_settings({"properties": props, "required": schema.get("required") or []},
                                                  {**plain, **hidden})
            if problems:
                raise PluginsError("Settings: " + "; ".join(problems[:3]))
        return plain, hidden

    def _key_for(self, name: str) -> str:
        existing = self.hub.store.key(name)
        return existing or f"k1.{secrets.token_urlsafe(32)}"

    def _spec(self, name: str, version: dict[str, Any], key: str, plain: dict[str, Any], hidden: dict[str, str]) -> Spec:
        folder = self.root / name / "versions" / version["version"]
        return Spec(name=name, version=version["version"], entry=folder / version["package"]["local"]["entry"],
                    data_dir=self.root / name / "data", manifest=version["manifest"], key=key,
                    settings={**plain, **hidden}, hide=[v for v in hidden.values() if isinstance(v, str)])

    def _launch(self, name: str, version: dict[str, Any], key: str, plain: dict[str, Any],
                hidden: dict[str, str]) -> tuple[str, Running | None]:
        """Starts a candidate (local) or names the remote endpoint; returns (endpoint, running or None)."""
        if version["package"]["kind"] == "remote":
            return version["package"]["remote"]["endpoint"].rstrip("/"), None
        running = self.host.launch(self._spec(name, version, key, plain, hidden))
        return running.endpoint, running

    def _conform(self, endpoint: str, key: str) -> None:
        try:
            results = self._check(endpoint, key)
        except Exception as exc:  # noqa: BLE001 - a broken plugin must not break the installer
            raise PluginsError(f"The plugin's checks couldn't run ({type(exc).__name__}).") from exc
        failed = [c for c in results if getattr(c, "required", True) and not getattr(c, "ok", False)]
        if not results or failed:
            names = ", ".join(getattr(c, "name", "?") for c in failed[:3]) or "no checks ran"
            raise PluginsError(f"The plugin failed Cyclone's checks: {names}.")

    def _install(self, job_id: str, card: dict[str, Any], allowed: list[str], plain: dict[str, Any],
                 hidden: dict[str, str]) -> dict[str, Any]:
        name, version_name, sha = card["name"], card["version"], card["sha256"]
        staged = self.staging / f"{sha}.zip"
        self._step(job_id, "verifying")
        if _sha_file(staged) != sha:
            raise PluginsError("The downloaded file changed on disk. Look the plugin up again.")
        hit = self.index.revoked(sha)
        if hit:
            raise PluginsError(f"Cyclone blocked this file: {hit['reason']}")
        record = self.store.installed(name)
        if record is not None and record["current"] == version_name and self._state.get(name, ("",))[0] != "broken":
            current_version = self.store.version(name, version_name)
            if current_version and current_version["sha256"] == sha:
                raise PluginsError("This version is already installed.")
            raise PluginsError("A different file claims the version that's running. Ask its author to publish a new "
                               "version; Cyclone won't replace a running version in place.")
        with zipfile.ZipFile(staged) as zf:
            found = kpackage.read_package(zf)
            self._step(job_id, "unpacking")
            unpacked = self.staging / f"unpack-{job_id}"
            kpackage.extract(zf, unpacked)
        self._step(job_id, "placing")
        target = self.root / name / "versions" / version_name
        if target.exists():
            # Reinstalling a version that isn't running (a broken current, or the kept previous): replace it.
            if record is not None and record["previous"] == version_name:
                with self.store.transaction() as db:
                    db.execute("UPDATE installed SET previous = NULL WHERE name = ?", (name,))
            if record is not None and record["current"] == version_name:
                self.host.stop(name)
            _rmtree(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        os.replace(unpacked, target)
        if found["package"]["kind"] == "local" and os.name != "nt":  # zips carry no execute bit; Windows needs none
            entry = target / found["package"]["local"]["entry"]
            entry.chmod(entry.stat().st_mode | 0o111)
        _seal(target)
        version = {"version": version_name, "sha256": sha, "package": found["package"], "manifest": found["manifest"],
                   "schema": found["schema"]}
        placed_new = True
        candidate: Running | None = None
        key = self._key_for(name)
        try:
            self._step(job_id, "starting")
            endpoint, candidate = self._launch(name, version, key, plain, hidden)
            self._step(job_id, "checking")
            self._conform(endpoint, key)
            self._step(job_id, "switching")
            self.store.put_secret_settings(name, hidden)
            stamp = now_ms()
            with self.store.transaction() as db:
                db.execute("INSERT OR REPLACE INTO version (name, version, sha256, tag, asset, verified, package, "
                           "manifest, schema, installed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                           (name, version_name, sha, card["tag"], card["asset"], 1 if card["verified"] else 0,
                            _json(found["package"]), _json(found["manifest"]),
                            _json(found["schema"]) if found["schema"] else None, stamp))
                if record is None:
                    db.execute("INSERT INTO installed (name, kind, source, repo, current, previous, enabled, settings, "
                               "consent, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, 1, ?, ?, ?, ?)",
                               (name, found["package"]["kind"], self._source_of(card), card["repo"],
                                version_name, _json(plain), _json(allowed), stamp, stamp))
                else:
                    previous = record["current"] if record["current"] != version_name else record["previous"]
                    db.execute("UPDATE installed SET kind = ?, source = ?, repo = ?, current = ?, previous = ?, "
                               "enabled = 1, settings = ?, consent = ?, updated_at = ? WHERE name = ?",
                               (found["package"]["kind"], self._source_of(card), card["repo"],
                                version_name, previous, _json(plain), _json(allowed), stamp, name))
            placed_new = False
        except BaseException as exc:
            if isinstance(exc, Exception):  # a simulated crash leaves everything for reconcile, like a power loss
                if candidate is not None:
                    self.host.discard(candidate)
                if placed_new:
                    _rmtree(target)
            raise
        self._step(job_id, "connecting")
        self._state[name] = ("running", "")
        self.hub.register_managed(name, endpoint, found["manifest"], key, allowed)
        if candidate is not None:
            self.host.adopt(candidate)
        else:
            self.host.stop(name)
        self._fault("connected")
        self._tidy(name, sha)
        return {"name": name, "version": version_name, "updatedFrom": (record or {}).get("current")}

    @staticmethod
    def _source_of(card: dict[str, Any]) -> str:
        return "index" if card["verified"] else card.get("source", "link")

    def _tidy(self, name: str, sha: str | None = None) -> None:
        record = self.store.installed(name)
        keep = {record["current"], record["previous"]} - {None} if record else set()
        versions = self.root / name / "versions"
        if versions.is_dir():
            for folder in versions.iterdir():
                if folder.name not in keep:
                    _rmtree(folder)
        with self.store.transaction() as db:
            db.execute(f"DELETE FROM version WHERE name = ? AND version NOT IN ({','.join('?' * len(keep)) or 'NULL'})",
                       (name, *keep))
        if sha:
            (self.staging / f"{sha}.zip").unlink(missing_ok=True)
            self._cards.pop(sha, None)

    # ---- roll back, remove -------------------------------------------------------------------------------------------

    def rollback(self, name: str) -> dict[str, Any]:
        record = self._installed(name)
        if not record["previous"]:
            raise PluginsError("There's no earlier version to go back to.")
        return self._job("rollback", name, lambda job_id: self._rollback(job_id, name))

    def _rollback(self, job_id: str, name: str) -> dict[str, Any]:
        record = self._installed(name)
        target = self.store.version(name, record["previous"])
        if target is None:
            raise PluginsError("The earlier version's files are gone.")
        hit = self.index.revoked(target["sha256"])
        if hit:
            raise PluginsError(f"Cyclone blocked the earlier version: {hit['reason']}")
        key = self._key_for(name)
        plain, hidden = self._merge_settings(name, target["schema"], {})
        self._step(job_id, "starting")
        endpoint, candidate = self._launch(name, target, key, plain, hidden)
        try:
            self._step(job_id, "checking")
            self._conform(endpoint, key)
            self._step(job_id, "switching")
            with self.store.transaction() as db:
                db.execute("UPDATE installed SET current = ?, previous = ?, enabled = 1, updated_at = ? WHERE name = ?",
                           (record["previous"], record["current"], now_ms(), name))
        except Exception:
            if candidate is not None:
                self.host.discard(candidate)
            raise
        self._step(job_id, "connecting")
        self._state[name] = ("running", "")
        self.hub.register_managed(name, endpoint, target["manifest"], key, None)
        if candidate is not None:
            self.host.adopt(candidate)
        return {"name": name, "version": record["previous"], "updatedFrom": record["current"]}

    def remove(self, name: str, keep_data: Any = False) -> dict[str, Any]:
        self._installed(name)
        if not isinstance(keep_data, bool):
            raise PluginsError("Send keepData: true or false.")
        with self._job_lock:
            self.host.stop(name)
            with self.store.transaction() as db:
                db.execute("DELETE FROM installed WHERE name = ?", (name,))
                db.execute("DELETE FROM version WHERE name = ?", (name,))
            self.hub.unregister_managed(name)
            self.store.put_secret_settings(name, {})
            self._state.pop(name, None)
            _rmtree(self.root / name / "versions")
            if not keep_data:
                _rmtree(self.root / name)
        return {"removed": name, "keptData": keep_data}

    # ---- running -----------------------------------------------------------------------------------------------------

    def _installed(self, name: Any) -> dict[str, Any]:
        record = self.store.installed(name) if isinstance(name, str) else None
        if record is None:
            raise PluginsError("That plugin isn't installed.")
        return record

    def _start_current(self, name: str) -> None:
        record = self._installed(name)
        version = self.store.version(name, record["current"])
        if version is None:
            self._state[name] = ("broken", "Its files are missing. Install it again.")
            return
        key = self._key_for(name)
        try:
            plain, hidden = self._merge_settings(name, version["schema"], {})
        except PluginsError as exc:
            self._state[name] = ("needs_settings", str(exc))
            self.hub.managed_state(name, "needs_settings", str(exc))
            return
        try:
            if version["package"]["kind"] == "remote":
                self.hub.register_managed(name, version["package"]["remote"]["endpoint"].rstrip("/"),
                                          version["manifest"], key, None if self.hub.store.plugin(name) else record["consent"])
                self._state[name] = ("running", "")
                return
            running = self.host.launch(self._spec(name, version, key, plain, hidden))
            self.hub.register_managed(name, running.endpoint, version["manifest"], key,
                                      None if self.hub.store.plugin(name) else record["consent"])
            self.host.adopt(running)
            self._state[name] = ("running", "")
        except (HostError, PortsError, OSError) as exc:
            self._state[name] = ("failed", str(exc))
            self.hub.managed_state(name, "failed", str(exc))

    def _host_state(self, name: str, state: str, detail: str) -> None:
        if state == "running":
            running = self.host.running(name)
            record = self.store.installed(name)
            version = self.store.version(name, record["current"]) if record else None
            if running is not None and version is not None:
                try:
                    self.hub.register_managed(name, running.endpoint, version["manifest"], running.spec.key, None)
                except PortsError:
                    pass
        else:
            self.hub.managed_state(name, state, detail)
        if state != "stopped" or self._state.get(name, ("",))[0] == "running":
            self._state[name] = (state, detail)

    def set_enabled(self, name: str, enabled: Any) -> dict[str, Any]:
        self._installed(name)
        if not isinstance(enabled, bool):
            raise PluginsError("Send enabled: true or false.")
        with self._job_lock:
            self.store.set_fields(name, enabled=enabled)
            if enabled:
                self._start_current(name)
            else:
                self.host.stop(name)
                self.hub.managed_state(name, "stopped", "Turned off.")
                self._state[name] = ("stopped", "Turned off.")
        return self.plugin(name)

    def restart(self, name: str) -> dict[str, Any]:
        record = self._installed(name)
        if self._state.get(name, ("",))[0] in ("revoked", "broken"):
            raise PluginsError(self._state[name][1])
        with self._job_lock:
            self.host.stop(name)
            if not record["enabled"]:
                self.store.set_fields(name, enabled=True)
            self._start_current(name)
        return self.plugin(name)

    # ---- settings ----------------------------------------------------------------------------------------------------

    def settings(self, name: str) -> dict[str, Any]:
        record = self._installed(name)
        version = self.store.version(name, record["current"])
        schema = self._public_schema((version or {}).get("schema"))
        props = (schema or {}).get("properties") or {}
        secret_names = {n for n, f in props.items() if f.get("x-cyclone-secret") is True}
        values = {**kpackage.default_settings({"properties": props}),
                  **{k: v for k, v in record["settings"].items() if k in props and k not in secret_names}}
        saved = self.store.secret_settings(name)
        return {"schema": schema, "values": values, "secretsSet": sorted(k for k in secret_names if saved.get(k))}

    def save_settings(self, name: str, changes: Any) -> dict[str, Any]:
        record = self._installed(name)
        version = self.store.version(name, record["current"])
        plain, hidden = self._merge_settings(name, (version or {}).get("schema"), changes)
        with self._job_lock:
            self.store.put_secret_settings(name, hidden)
            self.store.set_fields(name, settings=plain)
            if record["enabled"] and self._state.get(name, ("",))[0] not in ("revoked", "broken"):
                self.host.stop(name)
                self._start_current(name)
        return self.settings(name)

    def log(self, name: str) -> dict[str, Any]:
        self._installed(name)
        return {"lines": self.host.log_tail(name)}

    # ---- reading -----------------------------------------------------------------------------------------------------

    def plugin(self, name: str) -> dict[str, Any]:
        record = self._installed(name)
        return self._public(record)

    def _public(self, record: dict[str, Any]) -> dict[str, Any]:
        name = record["name"]
        version = self.store.version(name, record["current"]) or {}
        package = version.get("package") or {}
        state, detail = self._state.get(name, ("stopped" if not record["enabled"] else "starting", ""))
        entry = self.index.usable_entry(name)
        latest = kindex.newest(entry)["version"] if entry else None
        try:
            ports = self.hub._public(self.hub.store.plugin(name)) if self.hub.store.plugin(name) else None
        except Exception:  # noqa: BLE001
            ports = None
        return {
            "name": name, "title": package.get("title", name), "summary": package.get("summary", ""),
            "kind": record["kind"], "version": record["current"], "previous": record["previous"],
            "verified": bool(version.get("verified")), "source": record["source"], "repo": record["repo"],
            "tag": version.get("tag"), "sha256": version.get("sha256"), "enabled": record["enabled"],
            "state": state, "detail": detail, "permissions": kpackage.permissions(package) if package else None,
            "latest": latest, "updateAvailable": bool(latest and latest != record["current"]
                                                       and kindex._semver_key(latest) > kindex._semver_key(record["current"])),
            "hasSettings": bool(version.get("schema")), "endpoint": self.host.endpoint(name),
            "ports": None if ports is None else {"status": ports["status"], "serves": ports["serves"]},
            "installedAt": version.get("installed_at"), "updatedAt": record["updated_at"],
        }

    def overview(self) -> dict[str, Any]:
        installed = [self._public(r) for r in self.store.all_installed()]
        names = {p["name"] for p in installed}
        listing = self.index.status()
        for item in listing["plugins"]:
            item["installed"] = item["name"] in names
        return {"plugins": installed, "index": listing}

    def refresh_index(self) -> dict[str, Any]:
        status = self.index.refresh()
        self.enforce_revocations()
        return status


def _json(value: Any) -> str:
    import json

    return json.dumps(value)
