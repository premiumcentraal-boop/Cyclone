"""Plan 50 (alpha.103): plugins from GitHub. A fake GitHub serves release files; real plugin processes run (a POSIX
launcher stands in for the Windows program); every pipeline step is crashed on purpose and the next start must land on
the old or the new version, never a mix."""
from __future__ import annotations

import io
import json
import os
import shutil
import sys
import time
import uuid
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from cyclone_device_gateway.plugins import kindex, kpackage
from cyclone_device_gateway.plugins.github import GitHub, GitHubError, Response, parse_source
from cyclone_device_gateway.plugins.host import HostError, PluginHost, Spec, same_manifest
from cyclone_device_gateway.plugins.service import Crash, PluginsError, PluginsService
from cyclone_device_gateway.ports.hub import PortHub, PortsError

ROOT = Path(__file__).resolve().parents[3]
SDK = ROOT / "tools" / "cyclone-ports-sdk"
LOGGER = SDK / "examples" / "logger"
POSIX = os.name != "nt"
needs_posix = pytest.mark.skipif(not POSIX, reason="the stand-in plugin program is a POSIX launcher")
REPO = "acme/cyclone-run-logger"


# ---- packages --------------------------------------------------------------------------------------------------------

def make_package(tmp: Path, version: str = "0.1.0", *, kind: str = "local", script: str | None = None,
                 served_version: str | None = None, schema: dict | None = None, extra: dict[str, bytes] | None = None) -> tuple[str, bytes, str]:
    src = tmp / f"src-{uuid.uuid4().hex[:8]}"
    src.mkdir(parents=True)
    for name in ("README.md", "LICENSE"):
        shutil.copy(LOGGER / name, src / name)
    # A Windows checkout may carry CRLF line endings; the edits below match LF.
    toml = (LOGGER / "cyclone-plugin.toml").read_text().replace("\r\n", "\n").replace('version = "0.1.0"', f'version = "{version}"')
    manifest = json.loads((LOGGER / "cyclone-plugin.json").read_text())
    manifest["version"] = version
    if schema is None:
        shutil.copy(LOGGER / "settings.schema.json", src / "settings.schema.json")
    else:
        (src / "settings.schema.json").write_text(json.dumps(schema))
    if kind == "remote":
        toml = toml.replace('kind = "local"', 'kind = "remote"').replace(
            '[local]\nentry = "bin/run-logger.exe"\nplatform = "windows-x64"', '[remote]\nendpoint = "https://logger.example.com"')
        manifest["endpoint"] = "https://logger.example.com"
    (src / "cyclone-plugin.toml").write_text(toml)
    (src / "cyclone-plugin.json").write_text(json.dumps(manifest))
    if kind == "local":
        code = src / "app"
        code.mkdir()
        served = dict(manifest, version=served_version or version)
        (code / "cyclone-plugin.json").write_text(json.dumps(served))
        shutil.copy(LOGGER / "plugin.py", code / "plugin.py")
        if script is not None:
            (code / "plugin.py").write_text(script)
        (src / "bin").mkdir()
        launcher = src / "bin" / "run-logger.exe"
        launcher.write_text(f"#!/bin/sh\nPYTHONPATH={SDK} exec {sys.executable} {code / 'plugin.py'}\n")
        launcher.chmod(0o755)
    out = kpackage.pack(src, tmp / f"dist-{uuid.uuid4().hex[:8]}")
    data = out.read_bytes()
    if extra:
        buffer = io.BytesIO(data)
        with zipfile.ZipFile(buffer, "a") as zf:
            for name, value in extra.items():
                zf.writestr(name, value)
        data = buffer.getvalue()
    import hashlib
    return out.name, data, hashlib.sha256(data).hexdigest()


class FakeGitHub:
    def __init__(self) -> None:
        self.routes: dict[str, tuple[int, dict[str, str], bytes]] = {}
        self.calls: list[str] = []

    def publish(self, repo: str, tag: str, assets: list[tuple[str, bytes]], latest: bool = True, digest: bool = True) -> None:
        import hashlib
        doc = {"tag_name": tag, "draft": False, "prerelease": False, "published_at": "2026-10-03T00:00:00Z", "assets": []}
        for name, data in assets:
            page = f"https://github.com/{repo}/releases/download/{tag}/{name}"
            blob = f"https://objects.githubusercontent.com/blob/{uuid.uuid4().hex}"
            doc["assets"].append({"name": name, "size": len(data), "browser_download_url": page,
                                  **({"digest": "sha256:" + hashlib.sha256(data).hexdigest()} if digest else {})})
            self.routes[page] = (302, {"location": blob}, b"")
            self.routes[blob] = (200, {"content-length": str(len(data))}, data)
        body = json.dumps(doc).encode()
        self.routes[f"https://api.github.com/repos/{repo}/releases/tags/{tag}"] = (200, {}, body)
        if latest:
            self.routes[f"https://api.github.com/repos/{repo}/releases/latest"] = (200, {}, body)

    def __call__(self, url: str, headers: dict[str, str]) -> Response:
        self.calls.append(url)
        status, hdrs, body = self.routes.get(url, (404, {}, b"{}"))
        if callable(body):
            status, hdrs, body = body()
        return Response(status, {k.lower(): v for k, v in hdrs.items()}, io.BytesIO(body))


def ok_checker(endpoint: str, key: str) -> list:
    class C:
        name, ok, required, detail = "fake check", True, True, ""
    return [C()]


@pytest.fixture
def env(tmp_path):
    made: list[PluginsService] = []

    def build(*, trusted=None, checker=None, fault=None, root=None):
        base = root or tmp_path / "runtime"
        hub = PortHub(base / "ports")
        kwargs = {"checker": checker} if checker else {}
        svc = PluginsService(base / "plugins", hub, github=GitHub(fake, sleep=lambda s: None),
                             trusted_keys=trusted or {}, fault=fault or (lambda step: None), spawn=lambda fn: fn(),
                             **kwargs)
        made.append(svc)
        return svc

    fake = FakeGitHub()
    yield fake, build
    for svc in made:
        svc.host.close()


def resolve(svc: PluginsService, source: str) -> dict:
    job = svc.resolve(source)
    job = svc.job(job["id"])
    assert job["state"] == "done", job
    return job["result"]


def install(svc: PluginsService, card: dict, **over) -> dict:
    body = {"sha256": card["sha256"], "accept": True, "trustUnverified": True, "allowed": ["run.event", "log.line"],
            "settings": {}}
    body.update(over)
    job = svc.job(svc.install(body)["id"])
    return job


def boot(svc: PluginsService) -> None:
    svc.reconcile()
    for record in svc.store.all_installed():
        if record["enabled"] and svc._state.get(record["name"], ("",))[0] not in ("broken", "revoked"):
            svc._start_current(record["name"])


def served_version(svc: PluginsService, name: str = "run-logger") -> str | None:
    from cyclone_device_gateway.ports.hub import http_json
    record = svc.hub.store.plugin(name)
    if record is None:
        return None
    status, manifest, _ = http_json("GET", record["endpoint"] + "/cyclone-plugin.json", timeout=3)
    return manifest.get("version") if status == 200 else None


# ---- sources and the GitHub client -----------------------------------------------------------------------------------

@pytest.mark.parametrize("text, expected", [
    ("acme/repo", {"kind": "repo", "repo": "acme/repo", "tag": ""}),
    ("github.com/acme/repo", {"kind": "repo", "repo": "acme/repo", "tag": ""}),
    ("https://github.com/acme/repo/", {"kind": "repo", "repo": "acme/repo", "tag": ""}),
    ("https://github.com/acme/repo.git", {"kind": "repo", "repo": "acme/repo", "tag": ""}),
    ("https://github.com/acme/repo/releases/tag/v1.2.0", {"kind": "repo", "repo": "acme/repo", "tag": "v1.2.0"}),
    ("run-logger", {"kind": "index", "name": "run-logger"}),
])
def test_sources(text, expected):
    assert parse_source(text) == expected


@pytest.mark.parametrize("text", ["", "http://github.com/a/b", "https://gitlab.com/a/b", "https://github.com/a/b?x=1",
                                  "https://github.com:8443/a/b", "https://user@github.com/a/b", "a/b/c", "../x"])
def test_bad_sources(text):
    with pytest.raises(GitHubError):
        parse_source(text)


def test_redirects_only_to_github_hosts(tmp_path):
    fake = FakeGitHub()
    fake.routes["https://github.com/a/b/releases/download/v1/x.cyclone.zip"] = (302, {"location": "https://evil.example.com/x"}, b"")
    gh = GitHub(fake, sleep=lambda s: None)
    with pytest.raises(GitHubError, match="other than GitHub"):
        gh.download("https://github.com/a/b/releases/download/v1/x.cyclone.zip", tmp_path / "x", 1000)
    assert not (tmp_path / "x").exists()
    assert fake.calls == ["https://github.com/a/b/releases/download/v1/x.cyclone.zip"]
    with pytest.raises(GitHubError, match="other than GitHub"):
        gh.download("http://github.com/a/b", tmp_path / "y", 1000)


def test_limits_retries_and_rate_limits(tmp_path):
    fake = FakeGitHub()
    url = "https://objects.githubusercontent.com/big"
    fake.routes[url] = (200, {"content-length": "5000"}, b"x" * 5000)
    gh = GitHub(fake, sleep=lambda s: None)
    with pytest.raises(GitHubError, match="larger than"):
        gh.download(url, tmp_path / "big", 1000)
    fake.routes[url] = (200, {}, b"x" * 5000)  # no length header: the stream itself is capped
    with pytest.raises(GitHubError, match="larger than"):
        gh.download(url, tmp_path / "big", 1000)
    assert not (tmp_path / "big").exists()
    attempts = []

    def flaky():
        attempts.append(1)
        return (503, {}, b"") if len(attempts) < 3 else (200, {"content-length": "2"}, b"ok")

    fake.routes[url] = (0, {}, flaky)
    import hashlib
    assert gh.download(url, tmp_path / "ok", 1000) == hashlib.sha256(b"ok").hexdigest()
    assert len(attempts) == 3
    attempts.clear()
    fake.routes[url] = (0, {}, lambda: (attempts.append(1) or (404, {}, b"")))
    with pytest.raises(GitHubError, match="no release file"):
        gh.download(url, tmp_path / "nf", 1000)
    assert len(attempts) == 1, "a 4xx is never retried"
    fake.routes["https://api.github.com/repos/a/b/releases/latest"] = (
        403, {"x-ratelimit-remaining": "0", "x-ratelimit-reset": str(int(time.time()) + 600)}, b"{}")
    with pytest.raises(GitHubError, match="limiting requests"):
        gh.release("a/b")
    fake.routes[url] = (200, {"content-length": "10"}, b"short")
    with pytest.raises(GitHubError, match="cut off"):
        gh.download(url, tmp_path / "cut", 1000)


# ---- install, update, roll back, remove -------------------------------------------------------------------------------

@needs_posix
def test_install_from_a_link_runs_the_plugin_and_joins_the_port_hub(env, tmp_path):
    fake, build = env
    name, data, sha = make_package(tmp_path)
    fake.publish(REPO, "v0.1.0", [(name, data)])
    svc = build()
    card = resolve(svc, f"https://github.com/{REPO}")
    assert card["sha256"] == sha and card["verified"] is False and card["version"] == "0.1.0"
    assert {s["port"] for s in card["serves"]} >= {"run.event", "screen.shot"}
    assert card["settings"]["properties"]["folder"]["default"] == "runs"
    assert "Run logger" in card["readme"]
    with pytest.raises(PluginsError, match="trust its source"):
        svc.install({"sha256": sha, "accept": True, "allowed": []})
    with pytest.raises(PluginsError, match="Agree"):
        svc.install({"sha256": sha, "accept": False, "trustUnverified": True})
    with pytest.raises(PluginsError, match="ports this plugin serves"):
        svc.install({"sha256": sha, "accept": True, "trustUnverified": True, "allowed": ["code.in"]})
    job = install(svc, card)
    assert job["state"] == "done", job
    assert served_version(svc) == "0.1.0"
    record = svc.hub.store.plugin("run-logger")
    assert record["managed"] == 1 and record["consent"] == ["log.line", "run.event"]
    assert svc.hub.check("run-logger")["plugin"]["status"] == "active", "the hub signs and reaches the plugin"
    public = svc.plugin("run-logger")
    assert public["state"] == "running" and public["verified"] is False and public["source"] == "link"
    assert (svc.root / "run-logger" / "versions" / "0.1.0" / "bin" / "run-logger.exe").is_file()
    assert list(svc.staging.iterdir()) == []
    with pytest.raises(PortsError, match="Manage it under Plugins"):
        svc.hub.remove("run-logger")
    again = resolve(svc, REPO)
    assert install(svc, again)["detail"] == "This version is already installed."


@needs_posix
def test_update_keeps_the_old_one_and_rollback_returns_to_it(env, tmp_path):
    fake, build = env
    n1, d1, _ = make_package(tmp_path, "0.1.0")
    fake.publish(REPO, "v0.1.0", [(n1, d1)])
    svc = build()
    assert install(svc, resolve(svc, REPO))["state"] == "done"
    old_pid = svc.host.running("run-logger").proc.pid
    n2, d2, sha2 = make_package(tmp_path, "0.2.0")
    fake.publish(REPO, "v0.2.0", [(n2, d2)])
    card = resolve(svc, "run-logger")  # an installed name looks for its newest release
    assert card["sha256"] == sha2 and card["update"]["from"] == "0.1.0"
    job = install(svc, card, allowed=["run.event"])
    assert job["state"] == "done", job
    assert job["result"]["updatedFrom"] == "0.1.0"
    assert served_version(svc) == "0.2.0"
    record = svc.store.installed("run-logger")
    assert (record["current"], record["previous"]) == ("0.2.0", "0.1.0")
    assert svc.host.running("run-logger").proc.pid != old_pid
    assert not _alive(old_pid), "the old version's process is gone"
    job = svc.job(svc.rollback("run-logger")["id"])
    assert job["state"] == "done", job
    assert served_version(svc) == "0.1.0"
    record = svc.store.installed("run-logger")
    assert (record["current"], record["previous"]) == ("0.1.0", "0.2.0")


def _alive(pid: int) -> bool:
    try:
        os.waitpid(pid, os.WNOHANG)
    except ChildProcessError:
        pass
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


@needs_posix
@pytest.mark.parametrize("problem", ["exits", "wrong_manifest", "fails_checks"])
def test_a_bad_update_leaves_the_old_version_running(env, tmp_path, problem):
    fake, build = env
    n1, d1, _ = make_package(tmp_path, "0.1.0")
    fake.publish(REPO, "v0.1.0", [(n1, d1)])
    checks = {"fail": False}

    def checker(endpoint, key):
        from cyclone_device_gateway.ports.hub import _conformance
        if checks["fail"]:
            class C:
                name, ok, required, detail = "manifest is served", False, True, ""
            return [C()]
        return _conformance(endpoint, key)

    svc = build(checker=checker)
    assert install(svc, resolve(svc, REPO))["state"] == "done"
    kwargs = {"exits": {"script": "import sys\nsys.exit(3)\n"}, "wrong_manifest": {"served_version": "9.9.9"},
              "fails_checks": {}}[problem]
    n2, d2, _ = make_package(tmp_path, "0.2.0", **kwargs)
    fake.publish(REPO, "v0.2.0", [(n2, d2)])
    checks["fail"] = problem == "fails_checks"
    job = install(svc, resolve(svc, REPO))
    assert job["state"] == "failed", job
    assert {"exits": "stopped while starting", "wrong_manifest": "different manifest",
            "fails_checks": "failed Cyclone's checks"}[problem] in job["detail"]
    assert served_version(svc) == "0.1.0"
    assert svc.store.installed("run-logger")["current"] == "0.1.0"
    assert sorted(p.name for p in (svc.root / "run-logger" / "versions").iterdir()) == ["0.1.0"]


@needs_posix
def test_remove_keeps_data_only_when_asked(env, tmp_path):
    fake, build = env
    n1, d1, _ = make_package(tmp_path)
    fake.publish(REPO, "v0.1.0", [(n1, d1)])
    svc = build()
    assert install(svc, resolve(svc, REPO))["state"] == "done"
    endpoint = svc.host.endpoint("run-logger")
    assert svc.remove("run-logger", keep_data=True) == {"removed": "run-logger", "keptData": True}
    assert svc.hub.store.plugin("run-logger") is None and svc.host.endpoint("run-logger") is None
    assert (svc.root / "run-logger" / "data").is_dir() and not (svc.root / "run-logger" / "versions").exists()
    from cyclone_device_gateway.ports.hub import http_json
    assert http_json("GET", endpoint + "/cyclone-plugin.json", timeout=1)[0] == 0, "the process is gone"
    svc.reconcile()
    assert (svc.root / "run-logger" / "data").is_dir(), "kept data survives a restart"
    assert install(svc, resolve(svc, REPO))["state"] == "done"
    svc.remove("run-logger")
    assert not (svc.root / "run-logger").exists()


def test_a_hand_added_plugin_with_the_same_name_blocks_the_install(env, tmp_path):
    fake, build = env
    n1, d1, sha = make_package(tmp_path, kind="remote")
    fake.publish(REPO, "v0.1.0", [(n1, d1)])
    svc = build(checker=ok_checker)
    manifest = json.loads((LOGGER / "cyclone-plugin.json").read_text())
    svc.hub.store.insert({"name": "run-logger", "endpoint": "http://127.0.0.1:9", "manifest": manifest,
                          "pin_hash": "x", "consent": [], "kid": "k1"})
    card = resolve(svc, REPO)
    assert "Remove it there first" in card["conflict"]
    with pytest.raises(PluginsError, match="Remove it there first"):
        install(svc, card)


# ---- dangerous files never reach the disk outside staging ------------------------------------------------------------

def test_a_zip_slip_package_is_refused_and_nothing_is_left(env, tmp_path):
    fake, build = env
    name, data, _ = make_package(tmp_path, kind="remote", extra={"../../evil.txt": b"x"})
    fake.publish(REPO, "v0.1.0", [(name, data)])
    svc = build(checker=ok_checker)
    job = svc.job(svc.resolve(REPO)["id"])
    assert job["state"] == "failed" and "evil" in job["detail"]
    assert list(svc.staging.iterdir()) == []
    assert not (tmp_path / "evil.txt").exists()


def test_a_file_that_doesnt_match_githubs_digest_is_refused(env, tmp_path):
    fake, build = env
    name, data, _ = make_package(tmp_path, kind="remote")
    fake.publish(REPO, "v0.1.0", [(name, data)])
    blob = next(u for u in fake.routes if "objects.githubusercontent.com" in u)
    fake.routes[blob] = (200, {}, data[:-1] + bytes([data[-1] ^ 1]))
    svc = build(checker=ok_checker)
    job = svc.job(svc.resolve(REPO)["id"])
    assert job["state"] == "failed" and "doesn't match what GitHub says" in job["detail"]
    assert list(svc.staging.iterdir()) == []


def test_two_plugin_files_in_one_release_are_refused(env, tmp_path):
    fake, build = env
    name, data, _ = make_package(tmp_path, kind="remote")
    fake.publish(REPO, "v0.1.0", [(name, data), ("other-0.1.0-remote.cyclone.zip", data)])
    svc = build(checker=ok_checker)
    assert "more than one" in svc.job(svc.resolve(REPO)["id"])["detail"]


# ---- crash at every step: the next start is always old or new ---------------------------------------------------------

STEPS = ["verifying", "unpacking", "placing", "starting", "checking", "switching", "connecting", "connected"]


def _crash_at(step: str):
    def fault(current: str) -> None:
        if current == step:
            raise Crash(step)
    return fault


def _assert_consistent(svc: PluginsService, allowed_versions: set[str]) -> str:
    record = svc.store.installed("run-logger")
    assert record is not None
    assert record["current"] in allowed_versions
    folders = {p.name for p in (svc.root / "run-logger" / "versions").iterdir()} if record["kind"] == "local" else set()
    if record["kind"] == "local":
        assert record["current"] in folders
        assert folders <= {record["current"], record["previous"]}
    assert {v["version"] for v in svc.store.versions("run-logger")} <= {record["current"], record["previous"]}
    assert svc.store.version("run-logger", record["current"]) is not None
    assert list(svc.staging.iterdir()) == []
    assert svc._state.get("run-logger", ("running",))[0] == "running"
    assert svc.hub.store.plugin("run-logger")["managed"] == 1
    return record["current"]


@pytest.mark.parametrize("step", STEPS)
def test_a_crash_during_an_update_lands_on_old_or_new_50_times(env, tmp_path, step):
    """Remote plugins (no process), so each step can be crashed 50 times quickly."""
    fake, build = env
    n1, d1, _ = make_package(tmp_path, "0.1.0", kind="remote")
    n2, d2, _ = make_package(tmp_path, "0.2.0", kind="remote")
    for i in range(50):
        root = tmp_path / f"run{i}"
        fake.publish(REPO, "v0.1.0", [(n1, d1)])
        svc = build(checker=ok_checker, root=root)
        assert install(svc, resolve(svc, REPO))["state"] == "done"
        fake.publish(REPO, "v0.2.0", [(n2, d2)])
        card = resolve(svc, REPO)
        svc._fault = _crash_at(step)
        with pytest.raises(Crash):
            install(svc, card)
        svc.host.close()  # the runtime died
        after = build(checker=ok_checker, root=root)
        boot(after)
        current = _assert_consistent(after, {"0.1.0", "0.2.0"})
        expected_new = STEPS.index(step) >= STEPS.index("connecting")
        assert current == ("0.2.0" if expected_new else "0.1.0"), (step, current)
        for done in (after, svc):  # Windows keeps open files; close both runtimes' databases each round
            done.store.close()
            done.hub.store.close()


@needs_posix
@pytest.mark.parametrize("step", STEPS)
def test_a_crash_during_a_local_update_lands_on_old_or_new_and_it_runs(env, tmp_path, step):
    fake, build = env
    n1, d1, _ = make_package(tmp_path, "0.1.0")
    n2, d2, _ = make_package(tmp_path, "0.2.0")
    root = tmp_path / "runtime-local"
    fake.publish(REPO, "v0.1.0", [(n1, d1)])
    svc = build(root=root)
    assert install(svc, resolve(svc, REPO))["state"] == "done"
    fake.publish(REPO, "v0.2.0", [(n2, d2)])
    card = resolve(svc, REPO)
    svc._fault = _crash_at(step)
    with pytest.raises(Crash):
        install(svc, card)
    svc.host.close()  # the runtime died: its Job Object takes every plugin process with it
    after = build(root=root)
    boot(after)
    current = _assert_consistent(after, {"0.1.0", "0.2.0"})
    assert served_version(after) == current


# ---- settings ---------------------------------------------------------------------------------------------------------

SECRET_SCHEMA = {"type": "object", "properties": {
    "folder": {"type": "string", "title": "Folder", "default": "runs"},
    "apiKey": {"type": "string", "title": "API key", "x-cyclone-secret": True}}, "required": ["apiKey"]}

NOISY = """
import json, sys
from pathlib import Path
from cyclone_ports import PluginServer
manifest = json.loads((Path(__file__).parent / "cyclone-plugin.json").read_text())
server = PluginServer.managed(manifest)
print("my key is", server.keys, "and the api key is", server.handshake["settings"].get("apiKey"), flush=True)
server.serve_forever()
"""


@needs_posix
def test_secret_settings_are_write_only_reach_the_plugin_and_never_its_log(env, tmp_path):
    fake, build = env
    name, data, _ = make_package(tmp_path, script=NOISY, schema=SECRET_SCHEMA)
    fake.publish(REPO, "v0.1.0", [(name, data)])
    svc = build()
    card = resolve(svc, REPO)
    assert card["settings"]["properties"]["apiKey"]["x-cyclone-secret"] is True
    with pytest.raises(PluginsError, match="API key is required"):
        install(svc, card, settings={})
    job = install(svc, card, settings={"apiKey": "sk-very-secret-123"})
    assert job["state"] == "done", job
    view = svc.settings("run-logger")
    assert view["values"] == {"folder": "runs"} and view["secretsSet"] == ["apiKey"]
    assert "sk-very-secret-123" not in json.dumps(view) + json.dumps(svc.plugin("run-logger")) + json.dumps(svc.overview())
    deadline = time.time() + 5
    while time.time() < deadline and not svc.host.log_tail("run-logger"):
        time.sleep(0.1)
    log = "\n".join(svc.host.log_tail("run-logger"))
    assert "the api key is ***" in log, log
    key = svc.hub.store.key("run-logger")
    assert key.split(".", 1)[1] not in log
    assert "sk-very-secret-123" not in (svc.root / "plugins.db").read_bytes().decode("latin-1")
    svc.save_settings("run-logger", {"folder": "elsewhere"})
    assert svc.settings("run-logger")["secretsSet"] == ["apiKey"], "absent keeps the secret"
    with pytest.raises(PluginsError, match="API key is required"):
        svc.save_settings("run-logger", {"apiKey": None})
    with pytest.raises(PluginsError, match="isn't a setting"):
        svc.save_settings("run-logger", {"nope": 1})


# ---- the host ---------------------------------------------------------------------------------------------------------

def _logger_spec(tmp: Path, version: str = "0.1.0") -> Spec:
    code = tmp / f"code-{uuid.uuid4().hex[:6]}"
    code.mkdir(parents=True)
    manifest = json.loads((LOGGER / "cyclone-plugin.json").read_text())
    manifest["version"] = version
    (code / "cyclone-plugin.json").write_text(json.dumps(manifest))
    shutil.copy(LOGGER / "plugin.py", code / "plugin.py")
    entry = code / "run-logger.exe"
    entry.write_text(f"#!/bin/sh\nPYTHONPATH={SDK} exec {sys.executable} {code / 'plugin.py'}\n")
    entry.chmod(0o755)
    return Spec("run-logger", version, entry, tmp / "data", manifest, "k1.secret-key-value", {"folder": "runs"})


@needs_posix
def test_the_host_restarts_with_backoff_then_gives_up(tmp_path):
    states = []
    clock = [1000.0]
    host = PluginHost(tmp_path / "logs", on_state=lambda n, s, d: states.append(s), clock=lambda: clock[0],
                      sleep=lambda s: time.sleep(0.05))
    host._start_timeout = 1e9
    try:
        first = host.start(_logger_spec(tmp_path))
        pids = [first.proc.pid]
        for _ in range(5):
            host.running("run-logger").proc.kill()
            host.running("run-logger").proc.wait()
            host.monitor_once(health=False)
            assert states[-1] == "restarting"
            clock[0] += 20
            host.monitor_once(health=False)
            assert states[-1] == "running"
            pids.append(host.running("run-logger").proc.pid)
        assert len(set(pids)) == 6
        host.running("run-logger").proc.kill()
        host.running("run-logger").proc.wait()
        host.monitor_once(health=False)
        assert states[-1] == "crashed"
        assert host.running("run-logger") is None
        assert "restarted 5 times" in host.down_reason("run-logger")
    finally:
        host.close()


@needs_posix
def test_the_host_refuses_a_plugin_that_serves_another_manifest(tmp_path):
    host = PluginHost(tmp_path / "logs")
    spec = _logger_spec(tmp_path)
    spec.manifest = dict(spec.manifest, version="2.0.0")
    try:
        with pytest.raises(HostError, match="different manifest"):
            host.launch(spec)
    finally:
        host.close()


@needs_posix
def test_the_host_gives_the_plugin_almost_nothing_from_its_environment(tmp_path, monkeypatch):
    monkeypatch.setenv("CYCLONE_SECRET_THING", "leak")
    script = tmp_path / "env.exe"
    script.write_text("#!/bin/sh\nenv > \"$HOME/env.txt\"\nsleep 30\n")
    script.chmod(0o755)
    host = PluginHost(tmp_path / "logs", start_timeout_s=1)
    spec = Spec("envy", "1.0.0", script, tmp_path / "envdata", {}, "k1.x", {})
    with pytest.raises(HostError):
        host.launch(spec)
    text = (tmp_path / "envdata" / "env.txt").read_text()
    assert "CYCLONE_SECRET_THING" not in text and "CYCLONE_PLUGIN_MANAGED=1" in text
    assert "k1.x" not in text


def test_same_manifest_compares_what_matters():
    a = {"contract": "cyclone.ports/1", "name": "x", "version": "1.0.0", "serves": [{"port": "log.line", "way": "out"}]}
    assert same_manifest(dict(a, endpoint="http://127.0.0.1:1", title="other"), a)
    assert not same_manifest(dict(a, serves=[]), a)
    assert not same_manifest(None, a)


# ---- the signed index: verified installs and the kill switch ----------------------------------------------------------

def _index(fake: FakeGitHub, key: dict, entries: list[dict], revoked: list[dict] | None = None, serial: int = 1) -> None:
    now = datetime.now(timezone.utc)
    raw = json.dumps({"index": kindex.INDEX, "serial": serial, "issuedAt": now.isoformat(),
                      "expiresAt": (now + timedelta(days=30)).isoformat(), "plugins": entries,
                      "revoked": revoked or []}).encode()
    from cyclone_device_gateway.plugins.index import INDEX_URL
    fake.routes[INDEX_URL] = (200, {}, raw)
    fake.routes[INDEX_URL + ".sig"] = (200, {}, kindex.sign(raw, key["private"]))


def test_index_listed_plugins_install_as_verified_and_revocation_stops_them(env, tmp_path):
    fake, build = env
    key = kindex.keygen()
    name, data, sha = make_package(tmp_path, kind="remote")
    fake.publish(REPO, "v0.1.0", [(name, data)], latest=False)
    entry = {"name": "run-logger", "repo": REPO, "versions": [{"version": "0.1.0", "tag": "v0.1.0", "asset": name, "sha256": sha}]}
    _index(fake, key, [entry])
    svc = build(trusted={key["keyId"]: key["public"]}, checker=ok_checker)
    assert svc.refresh_index()["state"] == "ok"
    card = resolve(svc, "run-logger")
    assert card["verified"] is True
    job = svc.job(svc.install({"sha256": sha, "accept": True, "allowed": []})["id"])
    assert job["state"] == "done", job
    assert svc.plugin("run-logger")["verified"] is True
    _index(fake, key, [entry], revoked=[{"name": "run-logger", "version": "0.1.0", "sha256": sha, "reason": "Leaks data."}], serial=2)
    svc.refresh_index()
    public = svc.plugin("run-logger")
    assert public["state"] == "revoked" and "Leaks data." in public["detail"]
    with pytest.raises(PluginsError, match="Leaks data"):
        svc.restart("run-logger")
    _index(fake, key, [entry], serial=1)  # an older list never replaces a newer one
    assert "older" in svc.refresh_index()["error"]


def test_an_index_that_lies_about_the_hash_is_refused(env, tmp_path):
    fake, build = env
    key = kindex.keygen()
    name, data, _ = make_package(tmp_path, kind="remote")
    fake.publish(REPO, "v0.1.0", [(name, data)], latest=False, digest=False)
    _index(fake, key, [{"name": "run-logger", "repo": REPO, "versions": [
        {"version": "0.1.0", "tag": "v0.1.0", "asset": name, "sha256": "0" * 64}]}])
    svc = build(trusted={key["keyId"]: key["public"]}, checker=ok_checker)
    svc.refresh_index()
    job = svc.job(svc.resolve("run-logger")["id"])
    assert job["state"] == "failed" and "doesn't match the one Cyclone checked" in job["detail"]


def test_without_trusted_keys_the_index_is_not_set_up(env):
    fake, build = env
    svc = build()
    assert svc.refresh_index()["state"] == "not_set_up"
    assert fake.calls == [], "no network without a trusted key"
    job = svc.job(svc.resolve("run-logger")["id"])
    assert "isn't in the Cyclone list" in job["detail"]


# ---- reconcile ---------------------------------------------------------------------------------------------------------

def test_reconcile_cleans_orphans_but_keeps_data_and_fails_stale_jobs(env, tmp_path):
    fake, build = env
    svc = build(checker=ok_checker)
    (svc.root / "ghost" / "versions" / "1.0.0").mkdir(parents=True)
    (svc.root / "kept" / "data").mkdir(parents=True)
    (svc.root / "stray.txt").write_text("x")
    (svc.staging / "half.part").write_text("x")
    svc.store.new_job("job_x", "install", "ghost")
    svc.reconcile()
    assert not (svc.root / "ghost").exists()
    assert (svc.root / "kept" / "data").is_dir() and not (svc.root / "stray.txt").exists()
    assert list(svc.staging.iterdir()) == []
    assert svc.job("job_x")["state"] == "failed" and "restarted" in svc.job("job_x")["detail"]


# ---- the routes ---------------------------------------------------------------------------------------------------------

def test_every_route_needs_the_token(env):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from cyclone_device_gateway.plugins.api import create_plugins_router

    fake, build = env
    svc = build(checker=ok_checker)
    app = FastAPI()
    app.include_router(create_plugins_router(lambda: svc, "tok"))
    client = TestClient(app)
    assert client.get("/v1/plugins").status_code == 401
    assert client.post("/v1/plugins/resolve", json={"source": "a/b"}).status_code == 401
    ok = client.get("/v1/plugins", headers={"Authorization": "Bearer tok"})
    assert ok.status_code == 200 and ok.json()["index"]["state"] == "not_set_up"
    missing = client.get("/v1/plugins/nope/settings", headers={"Authorization": "Bearer tok"})
    assert missing.status_code == 404
    bad = client.post("/v1/plugins/resolve", json={"source": "https://gitlab.com/a/b"}, headers={"Authorization": "Bearer tok"})
    assert bad.status_code == 400 and "github.com" in bad.json()["detail"]["message"]


# ---- `cyclone plugin` ---------------------------------------------------------------------------------------------------

def test_cyclone_plugin_add_shows_the_card_asks_then_installs(env, tmp_path):
    from cyclone_device_gateway.terminal.app import parse as terminal_parse
    from cyclone_device_gateway.terminal.plugins import run_plugin

    assert terminal_parse(["plugin", "add", "a/b", "--yes"]).rest == ["add", "a/b", "--yes"]
    fake, build = env
    name, data, _ = make_package(tmp_path, kind="remote")
    fake.publish(REPO, "v0.1.0", [(name, data)])
    svc = build(checker=ok_checker)

    def http(method, path, body):
        try:
            if path == "/v1/plugins":
                return 200, svc.overview()
            if path == "/v1/plugins/resolve":
                return 200, svc.resolve(body["source"])
            if path.startswith("/v1/plugins/jobs/"):
                return 200, svc.job(path.rsplit("/", 1)[1])
            if path == "/v1/plugins/install":
                return 200, svc.install(body)
        except PluginsError as exc:
            return 400, {"detail": {"message": str(exc)}}
        return 404, {"detail": {"message": "no route"}}

    lines: list[str] = []
    assert run_plugin(["add", REPO], http, lines.append, ask=lambda q: "n", sleep=lambda s: None) == 1
    assert any("UNVERIFIED" in line for line in lines) and lines[-1] == "Nothing installed."
    assert svc.store.installed("run-logger") is None
    lines.clear()
    assert run_plugin(["add", REPO, "--set", "folder=out"], http, lines.append, ask=lambda q: "yes", sleep=lambda s: None) == 0
    assert lines[-1] == "Run logger 0.1.0 is installed and running."
    assert svc.store.installed("run-logger")["consent"] == ["log.line", "run.event"], "public ports by default"
    assert svc.settings("run-logger")["values"]["folder"] == "out"
    lines.clear()
    assert run_plugin(["list"], http, lines.append, ask=lambda q: "") == 0
    assert lines == ["run-logger 0.1.0 · running · unverified"]


def test_a_package_file_on_this_pc_installs_as_unverified(env, tmp_path):
    fake, build = env
    name, data, sha = make_package(tmp_path, kind="remote")
    path = tmp_path / name
    path.write_bytes(data)
    svc = build(checker=ok_checker)
    assert parse_source(str(path))["kind"] == "file"
    card = resolve(svc, str(path))
    assert card["sha256"] == sha and card["verified"] is False and card["source"] == "file"
    assert install(svc, card)["state"] == "done"
    assert svc.plugin("run-logger")["source"] == "file"
    renamed = tmp_path / "renamed.cyclone.zip"
    renamed.write_bytes(data)
    assert "doesn't match the plugin" in svc.job(svc.resolve(str(renamed))["id"])["detail"]
    with pytest.raises(GitHubError, match="no package file"):
        parse_source(str(tmp_path / "missing.cyclone.zip"))
    assert fake.calls == [], "a local file never touches the network"
