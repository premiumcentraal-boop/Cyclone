"""Contract ``cyclone.package/1`` (plan 50): a Cyclone plugin as one release file on GitHub.

A package is a zip with ``cyclone-plugin.toml`` (what to install and run), ``cyclone-plugin.json`` (the Ports manifest
the plugin serves, contract ``cyclone.ports/1``), ``README.md``, ``LICENSE``, an optional ``settings.schema.json`` and,
for a local plugin, ``bin/`` with a self-contained program. Everything here is pure and has no dependencies, so the
gateway, the build Action and plugin authors check packages with the same rules.
"""
from __future__ import annotations

import io
import json
import os
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath
from typing import IO, Any

from .sdk import NAME, SEMVER, validate_manifest

PACKAGE = "cyclone.package/1"
PLATFORM = "windows-x64"
KINDS = ("local", "remote")
TOML = "cyclone-plugin.toml"
MANIFEST = "cyclone-plugin.json"
SCHEMA = "settings.schema.json"
REQUIRED_FILES = (TOML, MANIFEST, "README.md", "LICENSE")

LIMITS = {
    "zip_bytes": 200 * 1024 * 1024,
    "unpacked_bytes": 500 * 1024 * 1024,
    "entries": 5_000,
    "path_chars": 240,
    "ratio": 100,              # per entry, for entries over ratio_floor bytes (zip bombs)
    "ratio_floor": 1024 * 1024,
    "settings_fields": 40,
    "string_chars": 4_096,
    "network_hosts": 20,
}

_TOP_KEYS = {"package", "name", "version", "title", "summary", "kind", "homepage", "license", "local", "remote",
             "permissions", "settings"}
_HOST = re.compile(r"^(\*\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
_RESERVED = re.compile(r"^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$", re.IGNORECASE)
_DRIVE = re.compile(r"^[A-Za-z]:")


class PackageError(ValueError):
    """A package that must not be installed. ``problems`` lists every reason in plain words."""

    def __init__(self, problems: list[str]):
        super().__init__("; ".join(problems[:3]) + (f" (and {len(problems) - 3} more)" if len(problems) > 3 else ""))
        self.problems = problems


def asset_name(name: str, version: str, kind: str) -> str:
    """The release file's name. Exactly one asset in a release may carry it."""
    return f"{name}-{version}-{PLATFORM if kind == 'local' else 'remote'}.cyclone.zip"


def parse_toml(text: str) -> dict[str, Any]:
    try:
        import tomllib
    except ImportError as exc:  # Python 3.10: only packing and checking need it, never a plugin at run time
        raise PackageError(["reading cyclone-plugin.toml needs Python 3.11 or newer"]) from exc
    try:
        return tomllib.loads(text)
    except tomllib.TOMLDecodeError as exc:
        raise PackageError([f"cyclone-plugin.toml isn't valid TOML: {exc}"]) from exc


# ---- cyclone-plugin.toml ---------------------------------------------------------------------------------------------

def _text(value: Any, low: int, high: int) -> bool:
    return isinstance(value, str) and low <= len(value.strip()) <= high


def validate_package(p: Any) -> list[str]:
    if not isinstance(p, dict):
        return ["cyclone-plugin.toml must be a table"]
    problems: list[str] = []
    unknown = sorted(set(p) - _TOP_KEYS)
    if unknown:
        problems.append(f"unknown key {unknown[0]!r} (cyclone.package/1 is strict, so typos never pass silently)")
    if p.get("package") != PACKAGE:
        problems.append(f"package must be {PACKAGE!r}")
    if not isinstance(p.get("name"), str) or not NAME.match(p["name"]):
        problems.append("name must be lowercase letters, digits and dashes (2-41 characters)")
    if not isinstance(p.get("version"), str) or not SEMVER.match(p["version"]):
        problems.append("version must be semver, like 1.2.0")
    if not _text(p.get("title"), 1, 60):
        problems.append("title must be 1-60 characters")
    if not _text(p.get("summary"), 1, 200):
        problems.append("summary must be 1-200 characters")
    for key in ("homepage",):
        if key in p and not (isinstance(p[key], str) and p[key].startswith("https://") and len(p[key]) <= 300):
            problems.append(f"{key} must be an https:// address")
    if "license" in p and not _text(p["license"], 1, 60):
        problems.append("license must be 1-60 characters, like MIT")
    kind = p.get("kind")
    if kind not in KINDS:
        problems.append("kind must be 'local' or 'remote'")
    other = "remote" if kind == "local" else "local"
    if kind in KINDS and other in p:
        problems.append(f"a {kind} plugin has no [{other}] table")
    if kind == "local":
        local = p.get("local")
        if not isinstance(local, dict) or set(local) - {"entry", "platform"}:
            problems.append("[local] takes entry and platform")
        else:
            entry = local.get("entry")
            if not isinstance(entry, str) or not entry.startswith("bin/") or not entry.lower().endswith(".exe") \
                    or _entry_problem(entry):
                problems.append("local.entry must be a .exe under bin/, like bin/run-logger.exe")
            if local.get("platform") != PLATFORM:
                problems.append(f"local.platform must be {PLATFORM!r}")
    if kind == "remote":
        remote = p.get("remote")
        if not isinstance(remote, dict) or set(remote) != {"endpoint"} or not isinstance(remote.get("endpoint"), str) \
                or not re.match(r"^https://[^/?#@\s]+(/[^?#\s]*)?$", remote["endpoint"]):
            problems.append("[remote] takes one endpoint, an https:// address")
    perms = p.get("permissions", {})
    if not isinstance(perms, dict) or set(perms) - {"network", "files"}:
        problems.append("[permissions] takes network and files")
    else:
        hosts = perms.get("network", [])
        if not isinstance(hosts, list) or len(hosts) > LIMITS["network_hosts"] or \
                not all(isinstance(h, str) and _HOST.match(h) for h in hosts):
            problems.append(f"permissions.network must list at most {LIMITS['network_hosts']} host names, like api.example.com")
        if perms.get("files", "own") not in ("own", "user"):
            problems.append("permissions.files must be 'own' (its data folder) or 'user' (your files)")
    settings = p.get("settings")
    if settings is not None and (not isinstance(settings, dict) or settings != {"schema": SCHEMA}):
        problems.append(f"[settings] takes schema = {SCHEMA!r}")
    return problems


def permissions(p: dict[str, Any]) -> dict[str, Any]:
    perms = p.get("permissions") or {}
    return {"network": list(perms.get("network") or []), "files": perms.get("files", "own")}


# ---- settings.schema.json (a small, safe subset of JSON Schema) ------------------------------------------------------

_FIELD_KEYS = {"type", "title", "description", "default", "enum", "minLength", "maxLength", "format", "minimum",
               "maximum", "x-cyclone-secret"}
_FIELD_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,40}$")
_FORMATS = {"uri", "email", "date"}
_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_DATE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$")
_URI = re.compile(r"^https?://[^\s]+$")


def validate_settings_schema(schema: Any) -> list[str]:
    if not isinstance(schema, dict):
        return ["the settings schema must be a JSON object"]
    problems: list[str] = []
    extra = set(schema) - {"type", "title", "description", "properties", "required", "additionalProperties"}
    if extra:
        problems.append(f"the settings schema doesn't support {sorted(extra)[0]!r}")
    if schema.get("type") != "object":
        problems.append("the settings schema's type must be 'object'")
    if schema.get("additionalProperties", False) is not False:
        problems.append("additionalProperties may only be false")
    props = schema.get("properties")
    if not isinstance(props, dict) or not 1 <= len(props) <= LIMITS["settings_fields"]:
        return problems + [f"properties must have 1-{LIMITS['settings_fields']} fields"]
    for name, field in props.items():
        where = f"settings field {name!r}"
        if not _FIELD_NAME.match(name):
            problems.append(f"{where}: names are letters, digits and _ (start with a letter)")
        if not isinstance(field, dict):
            problems.append(f"{where} must be an object")
            continue
        if set(field) - _FIELD_KEYS:
            problems.append(f"{where} doesn't support {sorted(set(field) - _FIELD_KEYS)[0]!r}")
        kind = field.get("type")
        if kind not in ("string", "integer", "number", "boolean"):
            problems.append(f"{where}: type must be string, integer, number or boolean")
            continue
        for key in ("title", "description"):
            if key in field and not _text(field[key], 1, 300 if key == "description" else 80):
                problems.append(f"{where}: {key} is too long or empty")
        secret = field.get("x-cyclone-secret", False)
        if not isinstance(secret, bool) or (secret and kind != "string"):
            problems.append(f"{where}: only a string can be a secret")
        if secret and ("default" in field or "enum" in field):
            problems.append(f"{where}: a secret has no default and no choices")
        if kind != "string" and set(field) & {"minLength", "maxLength", "format"}:
            problems.append(f"{where}: minLength, maxLength and format are for strings")
        if kind == "boolean" and "enum" in field:
            problems.append(f"{where}: a true/false field has no choices")
        if kind not in ("integer", "number") and set(field) & {"minimum", "maximum"}:
            problems.append(f"{where}: minimum and maximum are for numbers")
        if "format" in field and field["format"] not in _FORMATS:
            problems.append(f"{where}: format must be uri, email or date")
        for key in ("minLength", "maxLength"):
            if key in field and not (type(field[key]) is int and 0 <= field[key] <= LIMITS["string_chars"]):
                problems.append(f"{where}: {key} must be 0-{LIMITS['string_chars']}")
        for key in ("minimum", "maximum"):
            if key in field and (isinstance(field[key], bool) or not isinstance(field[key], (int, float))):
                problems.append(f"{where}: {key} must be a number")
        if "enum" in field:
            if not isinstance(field["enum"], list) or not 1 <= len(field["enum"]) <= 50 or \
                    any(_value_problem({k: v for k, v in field.items() if k != "enum"}, v) for v in field["enum"]):
                problems.append(f"{where}: enum must list 1-50 values of the field's type")
        if "default" in field and _value_problem(field, field["default"]):
            problems.append(f"{where}: the default doesn't fit the field")
    required = schema.get("required", [])
    if not isinstance(required, list) or not all(isinstance(r, str) and r in props for r in required):
        problems.append("required must list fields from properties")
    return problems


def secret_fields(schema: dict[str, Any] | None) -> set[str]:
    return {n for n, f in ((schema or {}).get("properties") or {}).items() if f.get("x-cyclone-secret") is True}


def _value_problem(field: dict[str, Any], value: Any) -> str | None:
    kind = field.get("type")
    if kind == "boolean":
        return None if isinstance(value, bool) else "must be true or false"
    if kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            return "must be a whole number"
    elif kind == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value or value in (float("inf"), float("-inf")):
            return "must be a number"
    if kind in ("integer", "number"):
        if "minimum" in field and value < field["minimum"]:
            return f"must be at least {field['minimum']}"
        if "maximum" in field and value > field["maximum"]:
            return f"must be at most {field['maximum']}"
    if kind == "string":
        if not isinstance(value, str):
            return "must be text"
        if len(value) > min(field.get("maxLength", LIMITS["string_chars"]), LIMITS["string_chars"]):
            return "is too long"
        if len(value) < field.get("minLength", 0):
            return "is too short"
        fmt = field.get("format")
        if value and fmt == "uri" and not _URI.match(value):
            return "must be an http(s) address"
        if value and fmt == "email" and not _EMAIL.match(value):
            return "must be an email address"
        if value and fmt == "date" and not _DATE.match(value):
            return "must be a date like 2026-10-03"
    if "enum" in field and value not in field["enum"]:
        return "must be one of the choices"
    return None


def validate_settings(schema: dict[str, Any], values: Any) -> list[str]:
    """Checks a complete set of values (defaults already applied). Messages name the field by its title."""
    if not isinstance(values, dict):
        return ["settings must be an object"]
    props = schema.get("properties") or {}
    problems = [f"{name!r} isn't a setting of this plugin" for name in values if name not in props]
    for name, field in props.items():
        label = field.get("title") or name
        if name not in values or values[name] is None or values[name] == "":
            if name in (schema.get("required") or []):
                problems.append(f"{label} is required")
            continue
        why = _value_problem(field, values[name])
        if why:
            problems.append(f"{label} {why}")
    return problems


def default_settings(schema: dict[str, Any] | None) -> dict[str, Any]:
    return {n: f["default"] for n, f in ((schema or {}).get("properties") or {}).items() if "default" in f}


# ---- the archive -----------------------------------------------------------------------------------------------------

def _entry_problem(name: str) -> str | None:
    if not name or len(name) > LIMITS["path_chars"]:
        return "an empty or too long path"
    if "\\" in name or name.startswith("/") or _DRIVE.match(name) or ":" in name or "\x00" in name:
        return "an absolute path, a drive, a backslash or a ':'"
    parts = name.rstrip("/").split("/")
    for part in parts:
        if part in ("", ".", ".."):
            return "a '.', '..' or empty path part"
        if _RESERVED.match(part) or part != part.rstrip(". "):
            return "a name Windows can't use"
        if any(ord(c) < 32 or c in '<>"|?*' for c in part):
            return "a character Windows can't use"
    return None


def check_archive(zf: zipfile.ZipFile, kind: str | None = None) -> list[str]:
    """Every entry rule (plan 50 §3). Nothing is read beyond the central directory."""
    problems: list[str] = []
    infos = zf.infolist()
    if len(infos) > LIMITS["entries"]:
        return [f"the package has more than {LIMITS['entries']} files"]
    seen: set[str] = set()
    total = 0
    for info in infos:
        name = info.filename
        why = _entry_problem(name)
        if why:
            problems.append(f"{name[:80]!r}: {why}")
            continue
        mode = (info.external_attr >> 16) & 0o170000
        if mode and mode not in (stat.S_IFREG, stat.S_IFDIR):
            problems.append(f"{name!r}: links and special files aren't allowed")
        if info.flag_bits & 0x1:
            problems.append(f"{name!r}: encrypted entries aren't allowed")
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            problems.append(f"{name!r}: only stored or deflated entries are allowed")
        key = name.rstrip("/").lower()
        if key in seen:
            problems.append(f"{name!r} appears twice (names are compared without case)")
        seen.add(key)
        total += info.file_size
        if info.file_size > LIMITS["ratio_floor"] and info.file_size > LIMITS["ratio"] * max(info.compress_size, 1):
            problems.append(f"{name!r} unpacks to over {LIMITS['ratio']}x its size")
    if total > LIMITS["unpacked_bytes"]:
        problems.append(f"the package unpacks to more than {LIMITS['unpacked_bytes'] // (1024 * 1024)} MB")
    for required in REQUIRED_FILES:
        if required.lower() not in seen:
            problems.append(f"{required} is missing")
    has_bin = any(k == "bin" or k.startswith("bin/") for k in seen)
    if kind == "remote" and has_bin:
        problems.append("a remote plugin carries no bin/ folder")
    stray = sorted(k for k in seen if "/" not in k and k not in {f.lower() for f in REQUIRED_FILES} | {SCHEMA, "bin"})
    if stray:
        problems.append(f"{stray[0]!r} isn't allowed at the top (put files in bin/)")
    return problems


def _read_json(zf: zipfile.ZipFile, name: str, limit: int = 256 * 1024) -> Any:
    info = zf.getinfo(name)
    if info.file_size > limit:
        raise PackageError([f"{name} is larger than {limit // 1024} KB"])
    try:
        return json.loads(zf.read(info).decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise PackageError([f"{name} isn't valid JSON"]) from exc


def read_package(zf: zipfile.ZipFile) -> dict[str, Any]:
    """Checks the archive and its three describing files against each other. Returns {package, manifest, schema}."""
    names = {i.filename: i for i in zf.infolist()}
    problems = check_archive(zf)
    if problems:
        raise PackageError(problems)
    info = names[TOML]
    if info.file_size > 64 * 1024:
        raise PackageError([f"{TOML} is larger than 64 KB"])
    try:
        package = parse_toml(zf.read(info).decode("utf-8"))
    except UnicodeDecodeError as exc:
        raise PackageError([f"{TOML} isn't UTF-8"]) from exc
    problems = validate_package(package)
    if problems:
        raise PackageError(problems)
    problems = check_archive(zf, package["kind"])
    manifest = _read_json(zf, MANIFEST)
    problems += [f"manifest: {p}" for p in validate_manifest(manifest)]
    if isinstance(manifest, dict):
        if manifest.get("name") != package["name"]:
            problems.append("the manifest's name differs from cyclone-plugin.toml's")
        if manifest.get("version") != package["version"]:
            problems.append("the manifest's version differs from cyclone-plugin.toml's")
        if package["kind"] == "remote" and str(manifest.get("endpoint", "")).rstrip("/") != package["remote"]["endpoint"].rstrip("/"):
            problems.append("the manifest's endpoint differs from remote.endpoint")
    schema = None
    if package.get("settings"):
        if SCHEMA not in names:
            problems.append(f"{SCHEMA} is declared but missing")
        else:
            schema = _read_json(zf, SCHEMA)
            problems += validate_settings_schema(schema)
    elif SCHEMA in names:
        problems.append(f"{SCHEMA} is in the package but not declared in [settings]")
    if package["kind"] == "local":
        entry = names.get(package["local"]["entry"])
        if entry is None or entry.is_dir():
            problems.append(f"local.entry {package['local']['entry']} is missing from the package")
    if problems:
        raise PackageError(problems)
    return {"package": package, "manifest": manifest, "schema": schema}


def extract(zf: zipfile.ZipFile, dest: Path, *, chunk: int = 1 << 20) -> int:
    """Unpacks a checked archive into an empty ``dest``. Counts real bytes (never trusts the header) and refuses to
    write outside ``dest``. Returns the bytes written."""
    problems = check_archive(zf)
    if problems:
        raise PackageError(problems)
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    if any(dest.iterdir()):
        raise PackageError(["the unpack folder isn't empty"])
    written = 0
    for info in zf.infolist():
        target = (dest / PurePosixPath(info.filename)).resolve()
        if target != dest and dest not in target.parents:
            raise PackageError([f"{info.filename!r} would land outside the plugin folder"])
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        size = 0
        with zf.open(info) as source, open(target, "xb") as sink:
            while True:
                block = source.read(chunk)
                if not block:
                    break
                size += len(block)
                written += len(block)
                if size > info.file_size or written > LIMITS["unpacked_bytes"]:
                    raise PackageError([f"{info.filename!r} unpacks to more than it says"])
                sink.write(block)
    return written


# ---- packing (plugin authors, the build Action) ----------------------------------------------------------------------

def pack(source: Path, out_dir: Path) -> Path:
    """Builds the release file from a folder holding the describing files and, for a local plugin, bin/. The zip is
    deterministic (sorted names, fixed times), so the same folder always gives the same SHA-256."""
    source = Path(source)
    package = parse_toml((source / TOML).read_text(encoding="utf-8"))
    problems = validate_package(package)
    if problems:
        raise PackageError(problems)
    files: list[Path] = [source / name for name in REQUIRED_FILES if (source / name).is_file()]
    if package.get("settings") and (source / SCHEMA).is_file():
        files.append(source / SCHEMA)
    if (source / "bin").is_dir():
        files += sorted(p for p in (source / "bin").rglob("*") if p.is_file())
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / asset_name(package["name"], package["version"], package["kind"])
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(files, key=lambda p: p.relative_to(source).as_posix()):
            info = zipfile.ZipInfo(path.relative_to(source).as_posix(), date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            zf.writestr(info, path.read_bytes())
    with zipfile.ZipFile(io.BytesIO(buffer.getvalue())) as zf:
        read_package(zf)
    target.write_bytes(buffer.getvalue())
    return target


# ---- the start handshake (a plugin Cyclone installed and runs) --------------------------------------------------------

HANDSHAKE_KEYS = {"package", "host", "port", "key", "dataDir", "settings"}
MANAGED_ENV = "CYCLONE_PLUGIN_MANAGED"


def is_managed(env: dict[str, str] | None = None) -> bool:
    return (env if env is not None else os.environ).get(MANAGED_ENV) == "1"


def read_handshake(stream: IO[str] | None = None) -> dict[str, Any]:
    """The one JSON line Cyclone writes to a plugin's stdin when it starts it: where to listen, the plugin's key, its
    data folder and its settings (secrets included). Never write it to a log or a file."""
    import sys

    line = (stream or sys.stdin).readline(256 * 1024)
    try:
        hs = json.loads(line)
    except ValueError as exc:
        raise ValueError("Cyclone's start handshake wasn't valid JSON") from exc
    if not isinstance(hs, dict) or set(hs) != HANDSHAKE_KEYS or hs.get("package") != PACKAGE:
        raise ValueError("Cyclone's start handshake has unexpected fields")
    if hs["host"] != "127.0.0.1" or type(hs["port"]) is not int or not 0 < hs["port"] < 65536:
        raise ValueError("Cyclone's start handshake has a bad address")
    if not isinstance(hs["key"], str) or not isinstance(hs["dataDir"], str) or not isinstance(hs["settings"], dict):
        raise ValueError("Cyclone's start handshake has bad values")
    return hs


def handshake_line(port: int, key: str, data_dir: str, settings: dict[str, Any]) -> str:
    return json.dumps({"package": PACKAGE, "host": "127.0.0.1", "port": port, "key": key, "dataDir": data_dir,
                       "settings": settings}) + "\n"

