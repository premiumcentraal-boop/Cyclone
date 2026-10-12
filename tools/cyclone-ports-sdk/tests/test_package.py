"""cyclone.package/1 and cyclone.index/1 (plan 50): the format, the archive rules, settings, the start handshake and
the signed index."""
import hashlib
import io
import json
import shutil
import socket
import stat
import urllib.request
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from cyclone_ports import PluginServer
from cyclone_ports import index as idx
from cyclone_ports import package as pkg
from cyclone_ports.plugin_cli import main as cli

ROOT = Path(__file__).resolve().parents[1]
VECTORS = json.loads((ROOT / "schemas" / "settings-vectors.json").read_text(encoding="utf-8"))


def folder(tmp_path: Path, example: str = "logger", exe: str = "run-logger.exe") -> Path:
    src = ROOT / "examples" / example
    out = tmp_path / f"pkg-{example}"
    out.mkdir(parents=True)
    for name in ("cyclone-plugin.toml", "cyclone-plugin.json", "settings.schema.json", "README.md", "LICENSE"):
        shutil.copy(src / name, out / name)
    (out / "bin").mkdir()
    (out / "bin" / exe).write_bytes(b"MZ fake program")
    return out


def zip_of(entries: dict[str, bytes], attrs: dict[str, int] | None = None) -> zipfile.ZipFile:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (attrs or {}).get(name, (stat.S_IFREG | 0o644) << 16)
            zf.writestr(info, data)
    return zipfile.ZipFile(io.BytesIO(buffer.getvalue()))


def good_entries(tmp_path: Path) -> dict[str, bytes]:
    src = folder(tmp_path)
    return {p.relative_to(src).as_posix(): p.read_bytes() for p in src.rglob("*") if p.is_file()}


# ---- format ----------------------------------------------------------------------------------------------------------

def test_pack_is_deterministic_and_passes_every_check(tmp_path):
    src = folder(tmp_path)
    first = pkg.pack(src, tmp_path / "a")
    second = pkg.pack(src, tmp_path / "b")
    assert first.name == "run-logger-0.1.0-windows-x64.cyclone.zip"
    assert hashlib.sha256(first.read_bytes()).digest() == hashlib.sha256(second.read_bytes()).digest()
    with zipfile.ZipFile(first) as zf:
        found = pkg.read_package(zf)
    assert found["package"]["name"] == found["manifest"]["name"] == "run-logger"
    assert pkg.secret_fields(found["schema"]) == set()
    assert pkg.default_settings(found["schema"]) == {"folder": "runs"}


@pytest.mark.parametrize("change, words", [
    (lambda p: p.update(extra=1), "unknown key"),
    (lambda p: p.update(package="cyclone.package/2"), "package must be"),
    (lambda p: p.update(name="Bad Name"), "name must be"),
    (lambda p: p.update(version="1.0"), "semver"),
    (lambda p: p["local"].update(entry="run.exe"), "local.entry"),
    (lambda p: p["local"].update(entry="bin/../x.exe"), "local.entry"),
    (lambda p: p["local"].update(platform="linux-x64"), "local.platform"),
    (lambda p: p.update(remote={"endpoint": "https://x.dev"}), "has no [remote]"),
    (lambda p: p.update(kind="docker"), "kind must be"),
    (lambda p: p["permissions"].update(network=["http://x"]), "host names"),
    (lambda p: p["permissions"].update(files="everything"), "permissions.files"),
    (lambda p: p.update(settings={"schema": "other.json"}), "[settings]"),
    (lambda p: p.update(homepage="http://x.dev"), "https://"),
])
def test_the_toml_is_strict(change, words):
    package = pkg.parse_toml((ROOT / "examples" / "logger" / "cyclone-plugin.toml").read_text())
    assert pkg.validate_package(package) == []
    change(package)
    assert any(words in p for p in pkg.validate_package(package)), pkg.validate_package(package)


def test_remote_plugins_carry_no_program():
    package = {"package": pkg.PACKAGE, "name": "remote-one", "version": "1.0.0", "title": "R", "summary": "S",
               "kind": "remote", "remote": {"endpoint": "https://r.example.com"}}
    assert pkg.validate_package(package) == []
    package["remote"]["endpoint"] = "http://r.example.com"
    assert pkg.validate_package(package)


def test_the_manifest_must_agree_with_the_toml(tmp_path):
    entries = good_entries(tmp_path)
    manifest = json.loads(entries["cyclone-plugin.json"])
    manifest["version"] = "9.9.9"
    entries["cyclone-plugin.json"] = json.dumps(manifest).encode()
    with pytest.raises(pkg.PackageError) as err:
        pkg.read_package(zip_of(entries))
    assert any("version differs" in p for p in err.value.problems)
    entries = good_entries(tmp_path / "x")
    del entries["bin/run-logger.exe"]
    entries["bin/other.exe"] = b"MZ"
    with pytest.raises(pkg.PackageError, match="local.entry"):
        pkg.read_package(zip_of(entries))


# ---- the archive -----------------------------------------------------------------------------------------------------

BACKSLASH = pytest.param("bin\\evil.exe", marks=pytest.mark.skipif(
    __import__("os").sep == "\\", reason="zipfile itself turns \\ into / on Windows, so the name arrives as bin/evil.exe"))


@pytest.mark.parametrize("name", ["../evil.exe", "bin/../../evil", "/abs/evil", "C:/evil", BACKSLASH,
                                  "bin/evil.exe:stream", "bin/CON", "bin/nul.txt", "bin/trailing.", "bin/space ",
                                  "bin/a|b", "bin//x", "./bin/x"])
def test_dangerous_paths_are_refused(tmp_path, name):
    entries = good_entries(tmp_path)
    entries[name] = b"x"
    problems = pkg.check_archive(zip_of(entries))
    assert problems and any(name[:20] in p or "path" in p for p in problems), problems


def test_links_duplicates_bombs_and_strays_are_refused(tmp_path, monkeypatch):
    entries = good_entries(tmp_path)
    link = dict(entries, **{"bin/link": b"target"})
    assert any("links" in p for p in pkg.check_archive(zip_of(link, {"bin/link": (stat.S_IFLNK | 0o777) << 16})))
    dup = dict(entries, **{"BIN/Run-Logger.exe": b"x"})
    assert any("twice" in p for p in pkg.check_archive(zip_of(dup)))
    bomb = dict(entries, **{"bin/bomb.bin": b"\0" * (3 * 1024 * 1024)})
    assert any("unpacks to over" in p for p in pkg.check_archive(zip_of(bomb)))
    stray = dict(entries, **{"setup.ps1": b"x"})
    assert any("isn't allowed at the top" in p for p in pkg.check_archive(zip_of(stray)))
    missing = {k: v for k, v in entries.items() if k != "LICENSE"}
    assert any("LICENSE is missing" in p for p in pkg.check_archive(zip_of(missing)))
    monkeypatch.setitem(pkg.LIMITS, "entries", 3)
    assert any("more than 3 files" in p for p in pkg.check_archive(zip_of(entries)))


def test_extract_writes_inside_the_folder_only(tmp_path):
    zf = zip_of(good_entries(tmp_path))
    dest = tmp_path / "out"
    written = pkg.extract(zf, dest)
    assert (dest / "bin" / "run-logger.exe").read_bytes() == b"MZ fake program"
    assert written > 0
    with pytest.raises(pkg.PackageError, match="isn't empty"):
        pkg.extract(zf, dest)


def test_extract_counts_real_bytes_not_the_header(tmp_path):
    entries = good_entries(tmp_path)
    raw = io.BytesIO()
    with zipfile.ZipFile(raw, "w", zipfile.ZIP_STORED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    data = bytearray(raw.getvalue())
    # Lie in the central directory: say README.md is 1 byte. Extraction must refuse, not trust it.
    zf = zipfile.ZipFile(io.BytesIO(bytes(data)))
    info = zf.getinfo("README.md")
    info.file_size = 1
    with pytest.raises((pkg.PackageError, zipfile.BadZipFile)):
        pkg.extract(zf, tmp_path / "lie")


# ---- settings (shared vectors with the gateway and Glass) ------------------------------------------------------------

@pytest.mark.parametrize("case", VECTORS["schemas"], ids=lambda c: c["name"])
def test_schema_vectors(case):
    assert (pkg.validate_settings_schema(case["schema"]) == []) is case["ok"], pkg.validate_settings_schema(case["schema"])


@pytest.mark.parametrize("case", VECTORS["values"], ids=lambda c: c["name"])
def test_value_vectors(case):
    schema = next(s["schema"] for s in VECTORS["schemas"] if s["name"] == "good")
    assert (pkg.validate_settings(schema, case["values"]) == []) is case["ok"], pkg.validate_settings(schema, case["values"])


def test_secret_fields_are_named():
    schema = next(s["schema"] for s in VECTORS["schemas"] if s["name"] == "good")
    assert pkg.secret_fields(schema) == {"apiKey"}


# ---- the start handshake ---------------------------------------------------------------------------------------------

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def test_a_managed_plugin_listens_where_cyclone_says(tmp_path):
    port = free_port()
    line = pkg.handshake_line(port, "k1.secret-value", str(tmp_path), {"folder": "runs"})
    manifest = json.loads((ROOT / "examples" / "logger" / "cyclone-plugin.json").read_text())
    server = PluginServer.managed(manifest, io.StringIO(line)).start()
    try:
        served = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{port}/cyclone-plugin.json", timeout=5).read())
        assert served["endpoint"] == f"http://127.0.0.1:{port}"
        assert server.handshake == {"dataDir": str(tmp_path), "settings": {"folder": "runs"}}
    finally:
        server.stop()


@pytest.mark.parametrize("line", ["", "not json\n", json.dumps({"package": pkg.PACKAGE}) + "\n",
                                  pkg.handshake_line(80, "k", "d", {}).replace("127.0.0.1", "0.0.0.0")])
def test_a_bad_handshake_is_refused(line):
    with pytest.raises(ValueError):
        pkg.read_handshake(io.StringIO(line))


def test_is_managed_reads_only_its_own_flag():
    assert pkg.is_managed({"CYCLONE_PLUGIN_MANAGED": "1"})
    assert not pkg.is_managed({"CYCLONE_PLUGIN_MANAGED": "yes"})
    assert not pkg.is_managed({})


# ---- the signed index ------------------------------------------------------------------------------------------------

def index_doc(serial: int = 2, days: int = 30, **over) -> bytes:
    now = datetime.now(timezone.utc)
    doc = {"index": idx.INDEX, "serial": serial, "issuedAt": now.isoformat(),
           "expiresAt": (now + timedelta(days=days)).isoformat(),
           "plugins": [{"name": "run-logger", "repo": "acme/cyclone-run-logger", "versions": [
               {"version": "0.1.0", "tag": "v0.1.0", "asset": "run-logger-0.1.0-windows-x64.cyclone.zip", "sha256": "a" * 64},
               {"version": "0.2.0", "tag": "v0.2.0", "asset": "run-logger-0.2.0-windows-x64.cyclone.zip", "sha256": "b" * 64}]}],
           "revoked": [{"name": "run-logger", "version": "0.1.0", "sha256": "a" * 64, "reason": "Crashes on start."}]}
    doc.update(over)
    return json.dumps(doc).encode()


def test_index_sign_and_verify():
    key = idx.keygen()
    trusted = {key["keyId"]: key["public"]}
    raw = index_doc()
    sig = idx.sign(raw, key["private"])
    doc = idx.verify(raw, sig, trusted, min_serial=2)
    assert idx.newest(idx.find(doc, "run-logger"))["version"] == "0.2.0"
    assert idx.revoked(doc, "a" * 64)["reason"] == "Crashes on start."
    assert idx.revoked(doc, "b" * 64) is None


def test_index_refuses_tampering_unknown_keys_rollback_and_expiry():
    key, other = idx.keygen(), idx.keygen()
    trusted = {key["keyId"]: key["public"]}
    raw = index_doc()
    sig = idx.sign(raw, key["private"])
    with pytest.raises(idx.IndexTrustError, match="doesn't match"):
        idx.verify(raw.replace(b"Crashes", b"Crashez"), sig, trusted)
    with pytest.raises(idx.IndexTrustError, match="doesn't trust"):
        idx.verify(raw, idx.sign(raw, other["private"]), trusted)
    with pytest.raises(idx.IndexTrustError, match="older"):
        idx.verify(raw, sig, trusted, min_serial=3)
    with pytest.raises(idx.IndexTrustError, match="expired"):
        idx.verify(raw, sig, trusted, now=datetime.now(timezone.utc) + timedelta(days=31))
    with pytest.raises(idx.IndexTrustError, match="unreadable"):
        idx.verify(raw, b"{}", trusted)
    with pytest.raises(idx.IndexTrustError, match="invalid index"):
        idx.sign(index_doc(serial=0), key["private"])


def test_index_shape_rules():
    assert idx.validate_index(json.loads(index_doc())) == []
    bad = json.loads(index_doc())
    bad["plugins"][0]["versions"][0]["sha256"] = "XYZ"
    assert idx.validate_index(bad)
    bad = json.loads(index_doc())
    bad["plugins"].append(bad["plugins"][0])
    assert any("twice" in p for p in idx.validate_index(bad))


# ---- the command line ------------------------------------------------------------------------------------------------

def test_cli_pack_and_check(tmp_path, capsys):
    assert cli(["pack", str(folder(tmp_path)), "--out", str(tmp_path / "dist")]) == 0
    packed = json.loads(capsys.readouterr().out)
    assert cli(["check", packed["file"]]) == 0
    checked = json.loads(capsys.readouterr().out)
    assert checked["sha256"] == packed["sha256"] and checked["name"] == "run-logger"
    broken = tmp_path / "broken.cyclone.zip"
    broken.write_bytes(b"not a zip")
    assert cli(["check", str(broken)]) == 1


# ---- the build (as the Action runs it, with a ready program instead of PyInstaller) ----------------------------------

@pytest.mark.skipif(not hasattr(__import__("os"), "fork"), reason="the wrapper program is a POSIX script")
def test_build_starts_the_program_like_cyclone_checks_it_and_packs(tmp_path, capsys):
    import sys

    from cyclone_ports.build import main as build

    program = tmp_path / "launcher"
    program.write_text(f"#!/bin/sh\nexec {sys.executable} {ROOT / 'examples' / 'logger' / 'plugin.py'}\n")
    program.chmod(0o755)
    code = build(["--folder", str(ROOT / "examples" / "logger"), "--program", str(program), "--out", str(tmp_path / "dist"),
                  "--test-settings", '{"folder": "runs"}', "--tag", "v0.1.0"])
    out = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert code == 0 and out["ok"], out
    with zipfile.ZipFile(out["file"]) as zf:
        assert pkg.read_package(zf)["package"]["name"] == "run-logger"
    assert build(["--folder", str(ROOT / "examples" / "logger"), "--program", str(program), "--tag", "v9.9.9"]) == 1
