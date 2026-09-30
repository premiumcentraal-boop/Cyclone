"""Tables (plan 43, T1): the Command Center's Notion-like databases. The owner builds tables in Glass with typed
properties, fills rows, and looks at the same rows through several saved views (table, board). Every row is also a
page: it has its own blocks under the properties.

Rules:
- **Typed cells only.** Every cell is checked against its property's type; unknown fields are refused. Glass renders
  cells with DOM APIs, so nothing in a table is ever markup or code.
- **No secrets.** Text that looks like a password, code or key is refused, and no property may be a password: the
  vault keeps those (plan 33 §4). A property marked personal is kept out of exports and agent reads unless asked for.
- **Views are computed here.** Filters, sorts and groups run on the PC; Glass only draws what comes back.
- **Every change is kept.** A row's history records who changed which property from what to what, and undo reverts
  the last change. Deleting a row or a table moves it to the trash first.
"""
from __future__ import annotations

import json
import math
import re
import secrets
from datetime import date
from typing import TYPE_CHECKING, Any, Callable

from ..desktop_runtime.v5_contract import FORBIDDEN_SECRET_TOKENS, INLINE_SECRET

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

from .center import CommandError
from .pages import ICON, _only, plain_text, validate_blocks

TABLES_SCHEMA = """
CREATE TABLE IF NOT EXISTS cc_table (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, icon TEXT NOT NULL, description TEXT NOT NULL, version INTEGER NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER);
CREATE TABLE IF NOT EXISTS cc_property (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, config TEXT NOT NULL, position REAL NOT NULL);
CREATE TABLE IF NOT EXISTS cc_row (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, cells TEXT NOT NULL, blocks TEXT NOT NULL, plain TEXT NOT NULL, position REAL NOT NULL,
  version INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived_at INTEGER);
CREATE TABLE IF NOT EXISTS cc_view (
  id TEXT PRIMARY KEY, table_id TEXT NOT NULL, name TEXT NOT NULL, layout TEXT NOT NULL, config TEXT NOT NULL, position REAL NOT NULL);
CREATE TABLE IF NOT EXISTS cc_row_history (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, row_id TEXT NOT NULL, at INTEGER NOT NULL, actor TEXT NOT NULL, change TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS cc_property_table ON cc_property(table_id);
CREATE INDEX IF NOT EXISTS cc_row_table ON cc_row(table_id);
CREATE INDEX IF NOT EXISTS cc_view_table ON cc_view(table_id);
CREATE INDEX IF NOT EXISTS cc_row_history_row ON cc_row_history(row_id);
"""

TABLE_ID = re.compile(r"^tb_[A-Za-z0-9_-]{8,40}$")
PROP_ID = re.compile(r"^pr_[A-Za-z0-9_-]{6,40}$")
ROW_ID = re.compile(r"^rw_[A-Za-z0-9_-]{8,40}$")
VIEW_ID = re.compile(r"^vw_[A-Za-z0-9_-]{6,40}$")
OPTION_ID = re.compile(r"^op_[A-Za-z0-9_-]{4,40}$")
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$")
EMAIL = re.compile(r"^[^\s@<>]{1,64}@[^\s@<>]{1,190}\.[^\s@<>]{2,24}$")
URL = re.compile(r"^https?://[^\s<>\"]{1,1990}$")
PHONE = re.compile(r"^\+?[0-9 ()./-]{3,30}$")

#: Property types. Relations, rollups, formulas, files, buttons and phone/profile come in T2–T4.
TYPES = ("title", "text", "number", "currency", "percent", "date", "select", "multi_select", "status", "checkbox",
         "email", "url", "phone_number", "created_time", "edited_time")
COMPUTED = ("created_time", "edited_time")
COLORS = ("default", "gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
STATUS_GROUPS = ("todo", "doing", "done")
CURRENCIES = ("EUR", "USD", "GBP", "PLN", "CHF", "SEK", "NOK", "DKK")
LAYOUTS = ("table", "board")
TEXT_OPS = ("contains", "not_contains", "is", "is_not", "starts_with", "empty", "not_empty")
NUMBER_OPS = ("eq", "ne", "gt", "lt", "gte", "lte", "empty", "not_empty")
CHOICE_OPS = ("is", "is_not", "empty", "not_empty")
MULTI_OPS = ("contains", "not_contains", "empty", "not_empty")
DATE_OPS = ("is", "before", "after", "on_or_before", "on_or_after", "empty", "not_empty")
CHECK_OPS = ("is",)
OPS_FOR: dict[str, tuple[str, ...]] = {
    "title": TEXT_OPS, "text": TEXT_OPS, "email": TEXT_OPS, "url": TEXT_OPS, "phone_number": TEXT_OPS,
    "number": NUMBER_OPS, "currency": NUMBER_OPS, "percent": NUMBER_OPS,
    "select": CHOICE_OPS, "status": CHOICE_OPS, "multi_select": MULTI_OPS, "checkbox": CHECK_OPS,
    "date": DATE_OPS, "created_time": DATE_OPS, "edited_time": DATE_OPS,
}
GROUPABLE = ("select", "status", "checkbox", "multi_select")

MAX_TABLES = 200
MAX_PROPERTIES = 60
MAX_ROWS = 10_000
MAX_VIEWS = 30
MAX_OPTIONS = 100
MAX_TEXT = 2_000
MAX_TITLE = 300
MAX_FILTERS = 20
MAX_HISTORY = 200
DEFAULT_STATUS = (("Not started", "gray", "todo"), ("In progress", "blue", "doing"), ("Done", "green", "done"))


class TableConflict(CommandError):
    """A save made on an older version of a row."""


def _new(prefix: str, n: int = 10) -> str:
    return f"{prefix}{secrets.token_urlsafe(n)}"


def _text(value: Any, label: str, limit: int) -> str:
    if not isinstance(value, str):
        raise CommandError(f"{label} must be text.")
    if len(value) > limit:
        raise CommandError(f"{label} is at most {limit} characters.")
    if any(ord(c) < 32 and c not in "\n\t" for c in value):
        raise CommandError(f"{label} has control characters.")
    if INLINE_SECRET.search(value):
        raise CommandError("That looks like a password, code or key. Tables never keep secrets; the vault does.")
    return value


def _name(value: Any, label: str = "A property's name") -> str:
    name = _text(value, label, 80).strip()
    if not name:
        raise CommandError(f"{label} is required.")
    words = re.sub(r"[^a-z]", " ", name.lower()).split()
    if any(w in FORBIDDEN_SECRET_TOKENS for w in words) or "".join(words) in FORBIDDEN_SECRET_TOKENS:
        raise CommandError("Passwords, codes and keys never live in a table. Keep them in the vault; a later build links vault items to rows.")
    return name


def _icon(value: Any) -> str:
    if not isinstance(value, str) or not ICON.match(value):
        raise CommandError("The icon is one emoji.")
    return value


def _date_value(value: Any) -> dict[str, str] | None:
    if value is None:
        return None
    if isinstance(value, str):
        value = {"start": value}
    if not isinstance(value, dict):
        raise CommandError("A date is {start, end?} as YYYY-MM-DD or YYYY-MM-DDTHH:MM.")
    _only(value, {"start", "end"}, "date")
    out: dict[str, str] = {}
    for key in ("start", "end"):
        raw = value.get(key)
        if raw is None:
            continue
        if not isinstance(raw, str) or not DAY.match(raw):
            raise CommandError("A date is YYYY-MM-DD or YYYY-MM-DDTHH:MM.")
        try:
            date.fromisoformat(raw[:10])
        except ValueError as exc:
            raise CommandError("That date does not exist.") from exc
        out[key] = raw
    if "start" not in out:
        raise CommandError("A date needs a start.")
    if "end" in out and out["end"] < out["start"]:
        raise CommandError("A date range ends after it starts.")
    return out


def _options(value: Any, status: bool) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) > MAX_OPTIONS:
        raise CommandError(f"A choice property has at most {MAX_OPTIONS} options.")
    out, ids, names = [], set(), set()
    for raw in value:
        if not isinstance(raw, dict):
            raise CommandError("An option is {id?, name, color, group?}.")
        _only(raw, {"id", "name", "color", "group"}, "option")
        oid = raw.get("id") or _new("op_", 6)
        if not isinstance(oid, str) or not OPTION_ID.match(oid) or oid in ids:
            raise CommandError("Each option has its own id.")
        name = _text(raw.get("name", ""), "An option's name", 100).strip()
        if not name or name.lower() in names:
            raise CommandError("Options have different, non-empty names.")
        color = raw.get("color", "default")
        if color not in COLORS:
            raise CommandError(f"An option's colour is one of: {', '.join(COLORS)}.")
        option = {"id": oid, "name": name, "color": color}
        if status:
            group = raw.get("group", "todo")
            if group not in STATUS_GROUPS:
                raise CommandError("A status option is in the todo, doing or done group.")
            option["group"] = group
        ids.add(oid)
        names.add(name.lower())
        out.append(option)
    return out


def _config(kind: str, value: Any) -> dict[str, Any]:
    raw = value or {}
    if not isinstance(raw, dict):
        raise CommandError("A property's settings are an object.")
    _only(raw, {"options", "currency", "decimals", "personal", "time", "wrap"}, "property settings")
    out: dict[str, Any] = {}
    if kind in ("select", "multi_select", "status"):
        options = raw.get("options")
        if options is None and kind == "status":
            options = [{"name": n, "color": c, "group": g} for n, c, g in DEFAULT_STATUS]
        out["options"] = _options(options or [], kind == "status")
    if kind == "currency":
        currency = raw.get("currency", "EUR")
        if currency not in CURRENCIES:
            raise CommandError(f"The currency is one of: {', '.join(CURRENCIES)}.")
        out["currency"] = currency
    if kind in ("number", "currency", "percent"):
        decimals = raw.get("decimals", 2 if kind == "currency" else 0)
        if type(decimals) is not int or not 0 <= decimals <= 6:
            raise CommandError("Decimals are 0..6.")
        out["decimals"] = decimals
    if kind == "date":
        out["time"] = raw.get("time") is True
    for flag in ("personal", "wrap"):
        if raw.get(flag) is True:
            out[flag] = True
        elif flag in raw and raw[flag] is not False:
            raise CommandError(f"{flag} is true or false.")
    return out


def cell_value(prop: dict[str, Any], value: Any) -> Any:
    """One cell checked against its property. None clears it."""
    kind = prop["type"]
    if kind in COMPUTED:
        raise CommandError(f"{prop['name']} is filled in by Cyclone.")
    if value is None or value == "" or value == []:
        return False if kind == "checkbox" else None
    if kind in ("title", "text"):
        return _text(value, prop["name"], MAX_TITLE if kind == "title" else MAX_TEXT)
    if kind in ("number", "currency", "percent"):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or abs(value) > 1e15:
            raise CommandError(f"{prop['name']} is a number.")
        return value
    if kind == "checkbox":
        if not isinstance(value, bool):
            raise CommandError(f"{prop['name']} is checked or not.")
        return value
    if kind == "date":
        return _date_value(value)
    options = {o["id"] for o in prop["config"].get("options", [])}
    if kind in ("select", "status"):
        if value not in options:
            raise CommandError(f"Pick one of {prop['name']}'s options.")
        return value
    if kind == "multi_select":
        if not isinstance(value, list) or len(value) > MAX_OPTIONS or any(v not in options for v in value):
            raise CommandError(f"Pick {prop['name']}'s options from its list.")
        return list(dict.fromkeys(value))
    text = _text(value, prop["name"], MAX_TEXT).strip()
    pattern = {"email": EMAIL, "url": URL, "phone_number": PHONE}[kind]
    if not pattern.match(text):
        raise CommandError(f"{prop['name']} is not a valid {kind.replace('_', ' ')}.")
    return text


def display(prop: dict[str, Any], value: Any) -> str:
    """A cell as plain text (search, export, conversions)."""
    if value is None:
        return ""
    kind = prop["type"]
    if kind == "checkbox":
        return "Yes" if value else "No"
    if kind in ("select", "status"):
        return next((o["name"] for o in prop["config"].get("options", []) if o["id"] == value), "")
    if kind == "multi_select":
        names = {o["id"]: o["name"] for o in prop["config"].get("options", [])}
        return ", ".join(names[v] for v in value if v in names)
    if kind == "date":
        return value["start"] + (f" → {value['end']}" if value.get("end") else "")
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


class TableStore:
    """The owner's tables, in the Command Center's database (same lock and audit chain)."""

    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(TABLES_SCHEMA)

    # ------------------------------------------------------------------ tables

    def list(self) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM cc_table WHERE archived_at IS NULL ORDER BY updated_at DESC").fetchall()
            return [{**self._table_meta(r), "rows": self._count(r["id"])} for r in rows]

    def trash(self) -> dict[str, Any]:
        with self._c._lock:
            tables = self._c._db.execute("SELECT * FROM cc_table WHERE archived_at IS NOT NULL ORDER BY archived_at DESC LIMIT 100").fetchall()
            rows = self._c._db.execute(
                "SELECT r.*, t.title AS table_title FROM cc_row r JOIN cc_table t ON t.id = r.table_id"
                " WHERE r.archived_at IS NOT NULL AND t.archived_at IS NULL ORDER BY r.archived_at DESC LIMIT 200").fetchall()
            out_rows = []
            for r in rows:
                props = self._props(r["table_id"])
                title = next((p for p in props if p["type"] == "title"), None)
                out_rows.append({"id": r["id"], "tableId": r["table_id"], "table": r["table_title"], "archivedAt": r["archived_at"],
                                 "title": display(title, json.loads(r["cells"]).get(title["id"])) if title else ""})
            return {"tables": [{**self._table_meta(t), "archivedAt": t["archived_at"]} for t in tables], "rows": out_rows}

    def create(self, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {title, icon?, description?}.")
        _only(body, {"title", "icon", "description"}, "table")
        title = _text(body.get("title", "Untitled table"), "The title", 200).strip() or "Untitled table"
        icon = _icon(body.get("icon", "🗂️"))
        description = _text(body.get("description", ""), "The description", 1000)
        with self._c._lock:
            if self._c._db.execute("SELECT COUNT(*) FROM cc_table").fetchone()[0] >= MAX_TABLES:
                raise CommandError(f"The workspace holds at most {MAX_TABLES} tables. Empty the trash first.")
            now = self._c._clock()
            table_id = _new("tb_", 12)
            self._c._db.execute("INSERT INTO cc_table(id, title, icon, description, version, created_at, updated_at, archived_at)"
                                " VALUES (?,?,?,?,1,?,?,NULL)", (table_id, title, icon, description, now, now))
            name = self._insert_prop(table_id, "Name", "title", {})
            status = self._insert_prop(table_id, "Status", "status", _config("status", {}))
            self._insert_view(table_id, "Table", "table", {})
            self._insert_view(table_id, "Board", "board", {"groupBy": status["id"]})
            del name
            self._c._audit(actor, "table.create", table_id)
            return self._public(table_id)

    def get(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._table(table_id)
            return self._public(table_id)

    def update(self, table_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {title?, icon?, description?}.")
        _only(body, {"title", "icon", "description"}, "table")
        fields: dict[str, Any] = {}
        if "title" in body:
            fields["title"] = _text(body["title"], "The title", 200).strip() or "Untitled table"
        if "icon" in body:
            fields["icon"] = _icon(body["icon"])
        if "description" in body:
            fields["description"] = _text(body["description"], "The description", 1000)
        with self._c._lock:
            self._live_table(table_id)
            if fields:
                sets = ", ".join(f"{k} = ?" for k in fields)
                self._c._db.execute(f"UPDATE cc_table SET {sets}, version = version + 1, updated_at = ? WHERE id = ?",
                                    (*fields.values(), self._c._clock(), table_id))
                self._c._audit(actor, "table.edit", table_id)
            return self._public(table_id)

    def archive(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            self._c._db.execute("UPDATE cc_table SET archived_at = ? WHERE id = ?", (self._c._clock(), table_id))
            self._c._audit("owner", "table.archive", table_id)
            return {"id": table_id, "archived": True}

    def restore(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            if self._table(table_id)["archived_at"] is None:
                raise CommandError("That table is not in the trash.")
            self._c._db.execute("UPDATE cc_table SET archived_at = NULL, updated_at = ? WHERE id = ?", (self._c._clock(), table_id))
            self._c._audit("owner", "table.restore", table_id)
            return self._public(table_id)

    def delete(self, table_id: str) -> dict[str, Any]:
        with self._c._lock:
            if self._table(table_id)["archived_at"] is None:
                raise CommandError("Move the table to the trash first.")
            rows = [r["id"] for r in self._c._db.execute("SELECT id FROM cc_row WHERE table_id = ?", (table_id,))]
            self._c._db.executemany("DELETE FROM cc_row_history WHERE row_id = ?", [(r,) for r in rows])
            for sql in ("DELETE FROM cc_row WHERE table_id = ?", "DELETE FROM cc_property WHERE table_id = ?",
                        "DELETE FROM cc_view WHERE table_id = ?", "DELETE FROM cc_table WHERE id = ?"):
                self._c._db.execute(sql, (table_id,))
            self._c._audit("owner", "table.delete", table_id, {"rows": len(rows)})
            return {"id": table_id, "deleted": True}

    # ------------------------------------------------------------------ properties

    def add_property(self, table_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name, type, config?}.")
        _only(body, {"name", "type", "config"}, "property")
        kind = body.get("type")
        if kind not in TYPES or kind == "title":
            raise CommandError(f"A property's type is one of: {', '.join(t for t in TYPES if t != 'title')}.")
        name = _name(body.get("name"))
        config = _config(kind, body.get("config"))
        with self._c._lock:
            self._live_table(table_id)
            props = self._props(table_id)
            if len(props) >= MAX_PROPERTIES:
                raise CommandError(f"A table has at most {MAX_PROPERTIES} properties.")
            if any(p["name"].lower() == name.lower() for p in props):
                raise CommandError("Another property already has that name.")
            prop = self._insert_prop(table_id, name, kind, config)
            self._touch(table_id)
            self._c._audit("owner", "table.property.add", table_id, {"property": prop["id"], "type": kind})
            return self._public(table_id)

    def update_property(self, table_id: str, prop_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name?, type?, config?, before?}.")
        _only(body, {"name", "type", "config", "before"}, "property")
        with self._c._lock:
            self._live_table(table_id)
            props = self._props(table_id)
            prop = next((p for p in props if p["id"] == prop_id), None)
            if prop is None:
                raise CommandError("No such property.")
            name, kind = prop["name"], prop["type"]
            if "name" in body:
                name = _name(body["name"])
                if any(p["name"].lower() == name.lower() and p["id"] != prop_id for p in props):
                    raise CommandError("Another property already has that name.")
            if "type" in body and body["type"] != kind:
                if kind == "title" or body["type"] == "title" or body["type"] not in TYPES:
                    raise CommandError("The title property stays the title; other properties change to any type but title.")
                kind = body["type"]
            config = _config(kind, body["config"]) if "config" in body else (_config(kind, None) if kind != prop["type"] else prop["config"])
            if kind != prop["type"]:
                config = self._convert(table_id, prop, kind, config)
            elif "config" in body and kind in ("select", "multi_select", "status"):
                self._drop_missing_options(table_id, prop, config)
            position = prop["position"]
            if body.get("before") is not None:
                before = next((p for p in props if p["id"] == body["before"]), None)
                if before is None or before["id"] == prop_id:
                    raise CommandError("Place it before another property of this table.")
                earlier = [p["position"] for p in props if p["position"] < before["position"] and p["id"] != prop_id]
                position = (before["position"] + (max(earlier) if earlier else before["position"] - 2)) / 2
            self._c._db.execute("UPDATE cc_property SET name = ?, type = ?, config = ?, position = ? WHERE id = ?",
                                (name, kind, json.dumps(config, ensure_ascii=False), position, prop_id))
            self._touch(table_id)
            self._c._audit("owner", "table.property.edit", table_id, {"property": prop_id})
            return self._public(table_id)

    def delete_property(self, table_id: str, prop_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            prop = next((p for p in self._props(table_id) if p["id"] == prop_id), None)
            if prop is None:
                raise CommandError("No such property.")
            if prop["type"] == "title":
                raise CommandError("Every table keeps its title property.")
            self._c._db.execute("DELETE FROM cc_property WHERE id = ?", (prop_id,))
            for r in self._c._db.execute("SELECT id, cells FROM cc_row WHERE table_id = ?", (table_id,)).fetchall():
                cells = json.loads(r["cells"])
                if prop_id in cells:
                    cells.pop(prop_id)
                    self._c._db.execute("UPDATE cc_row SET cells = ? WHERE id = ?", (json.dumps(cells, ensure_ascii=False), r["id"]))
            for v in self._c._db.execute("SELECT id, config FROM cc_view WHERE table_id = ?", (table_id,)).fetchall():
                config = self._scrub_view(json.loads(v["config"]), prop_id)
                self._c._db.execute("UPDATE cc_view SET config = ? WHERE id = ?", (json.dumps(config), v["id"]))
            self._touch(table_id)
            self._c._audit("owner", "table.property.delete", table_id, {"property": prop_id})
            return self._public(table_id)

    # ------------------------------------------------------------------ views

    def add_view(self, table_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name, layout, config?}.")
        _only(body, {"name", "layout", "config"}, "view")
        with self._c._lock:
            self._live_table(table_id)
            if self._c._db.execute("SELECT COUNT(*) FROM cc_view WHERE table_id = ?", (table_id,)).fetchone()[0] >= MAX_VIEWS:
                raise CommandError(f"A table has at most {MAX_VIEWS} views.")
            layout = body.get("layout", "table")
            if layout not in LAYOUTS:
                raise CommandError(f"A view's layout is one of: {', '.join(LAYOUTS)}.")
            props = self._props(table_id)
            config = self._view_config(body.get("config"), props, layout)
            view = self._insert_view(table_id, _text(body.get("name", "View"), "A view's name", 80).strip() or "View", layout, config)
            self._touch(table_id)
            return {**self._public(table_id), "viewId": view["id"]}

    def update_view(self, table_id: str, view_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {name?, layout?, config?, before?}.")
        _only(body, {"name", "layout", "config", "before"}, "view")
        with self._c._lock:
            self._live_table(table_id)
            views = self._views(table_id)
            view = next((v for v in views if v["id"] == view_id), None)
            if view is None:
                raise CommandError("No such view.")
            layout = body.get("layout", view["layout"])
            if layout not in LAYOUTS:
                raise CommandError(f"A view's layout is one of: {', '.join(LAYOUTS)}.")
            config = self._view_config(body["config"] if "config" in body else view["config"], self._props(table_id), layout)
            name = _text(body["name"], "A view's name", 80).strip() or view["name"] if "name" in body else view["name"]
            position = view["position"]
            if body.get("before") is not None:
                before = next((v for v in views if v["id"] == body["before"]), None)
                if before is None or before["id"] == view_id:
                    raise CommandError("Place it before another view of this table.")
                earlier = [v["position"] for v in views if v["position"] < before["position"] and v["id"] != view_id]
                position = (before["position"] + (max(earlier) if earlier else before["position"] - 2)) / 2
            self._c._db.execute("UPDATE cc_view SET name = ?, layout = ?, config = ?, position = ? WHERE id = ?",
                                (name, layout, json.dumps(config), position, view_id))
            self._touch(table_id)
            return self._public(table_id)

    def delete_view(self, table_id: str, view_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            views = self._views(table_id)
            if not any(v["id"] == view_id for v in views):
                raise CommandError("No such view.")
            if len(views) == 1:
                raise CommandError("A table keeps at least one view.")
            self._c._db.execute("DELETE FROM cc_view WHERE id = ?", (view_id,))
            self._touch(table_id)
            return self._public(table_id)

    # ------------------------------------------------------------------ rows

    def rows(self, table_id: str, view_id: str | None = None, query: str | None = None, *, personal: bool = True) -> dict[str, Any]:
        """The rows as a view shows them: filtered, sorted and (for a board) grouped, computed here."""
        with self._c._lock:
            self._live_table(table_id)
            props = self._props(table_id)
            views = self._views(table_id)
            view = next((v for v in views if v["id"] == view_id), None) if view_id else views[0]
            if view is None:
                raise CommandError("No such view.")
            rows = [self._row_public(r, props) for r in self._c._db.execute(
                "SELECT * FROM cc_row WHERE table_id = ? AND archived_at IS NULL ORDER BY position, created_at", (table_id,))]
        by_id = {p["id"]: p for p in props}
        config = view["config"]
        rows = [r for r in rows if _matches(r, config.get("filters", []), config.get("match", "and"), by_id)]
        if query:
            needle = query.strip().lower()
            rows = [r for r in rows if any(needle in display(by_id[k], v).lower() for k, v in r["cells"].items() if k in by_id)]
        for sort in reversed(config.get("sorts", [])):
            prop = by_id.get(sort["property"])
            if prop is not None:
                rows.sort(key=_sort_key(prop), reverse=sort["direction"] == "desc")
        if not personal:
            hidden = {p["id"] for p in props if p["config"].get("personal")}
            rows = [{**r, "cells": {k: v for k, v in r["cells"].items() if k not in hidden}} for r in rows]
        out: dict[str, Any] = {"table": self.get(table_id), "view": view, "rows": rows, "total": len(rows)}
        group = by_id.get(config.get("groupBy") or "")
        if view["layout"] == "board" and group is not None:
            out["groups"] = _groups(group, rows)
        return out

    def get_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            props = self._props(table_id)
            history = self._c._db.execute("SELECT at, actor, change FROM cc_row_history WHERE row_id = ? ORDER BY seq DESC LIMIT 50",
                                          (row_id,)).fetchall()
            return {**self._row_public(row, props), "blocks": json.loads(row["blocks"]),
                    "history": [{"at": h["at"], "actor": h["actor"], "change": json.loads(h["change"])} for h in history]}

    def create_row(self, table_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {cells?, before?}.")
        _only(body, {"cells", "before"}, "row")
        with self._c._lock:
            self._live_table(table_id)
            if self._c._db.execute("SELECT COUNT(*) FROM cc_row WHERE table_id = ?", (table_id,)).fetchone()[0] >= MAX_ROWS:
                raise CommandError(f"A table holds at most {MAX_ROWS} rows.")
            props = self._props(table_id)
            cells = self._cells(props, body.get("cells") or {}, {})
            now = self._c._clock()
            row_id = _new("rw_", 12)
            position = self._position(table_id, body.get("before"))
            self._c._db.execute("INSERT INTO cc_row(id, table_id, cells, blocks, plain, position, version, created_at, updated_at, archived_at)"
                                " VALUES (?,?,?,?,?,?,1,?,?,NULL)",
                                (row_id, table_id, json.dumps(cells, ensure_ascii=False), "[]", "", position, now, now))
            self._history(row_id, actor, {"created": True})
            self._touch(table_id)
            return self._row_public(self._row(table_id, row_id), props)

    def update_row(self, table_id: str, row_id: str, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        """Changes some cells. A version, when sent, must be current (two tabs never overwrite each other)."""
        if not isinstance(body, dict):
            raise CommandError("Send {cells, version?}.")
        _only(body, {"cells", "version", "before"}, "row")
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            if row["archived_at"] is not None:
                raise CommandError("That row is in the trash. Restore it to edit it.")
            if "version" in body and body["version"] != row["version"]:
                raise TableConflict("This row changed elsewhere since you opened it. It has been reloaded.")
            props = self._props(table_id)
            old = json.loads(row["cells"])
            cells = self._cells(props, body.get("cells") or {}, old)
            change = {k: [old.get(k), cells.get(k)] for k in set(old) | set(cells) if old.get(k) != cells.get(k)}
            position = self._position(table_id, body["before"], exclude=row_id) if body.get("before") is not None else row["position"]
            if not change and position == row["position"]:
                return self._row_public(row, props)
            self._c._db.execute("UPDATE cc_row SET cells = ?, position = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                (json.dumps(cells, ensure_ascii=False), position, self._c._clock(), row_id))
            if change:
                self._history(row_id, actor, {"cells": change})
            self._touch(table_id)
            return self._row_public(self._row(table_id, row_id), props)

    def save_row_page(self, table_id: str, row_id: str, body: Any) -> dict[str, Any]:
        """The row's own page, under its properties: the same typed blocks as any page."""
        if not isinstance(body, dict) or "version" not in body:
            raise CommandError("Send {version, blocks}.")
        _only(body, {"version", "blocks"}, "row page")
        blocks = validate_blocks(body.get("blocks", []))
        if any(b["type"] == "table" for b in blocks):
            raise CommandError("A row's page can't hold a table.")
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            if body["version"] != row["version"]:
                raise TableConflict("This row changed elsewhere since you opened it. It has been reloaded.")
            self._c._db.execute("UPDATE cc_row SET blocks = ?, plain = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                (json.dumps(blocks, ensure_ascii=False), plain_text(blocks), self._c._clock(), row_id))
            return self.get_row(table_id, row_id)

    def undo(self, table_id: str, row_id: str, *, actor: str = "owner") -> dict[str, Any]:
        """Reverts the row's last cell change (a value that no longer fits its property is cleared)."""
        with self._c._lock:
            self._live_table(table_id)
            row = self._row(table_id, row_id)
            last = self._c._db.execute("SELECT seq, change FROM cc_row_history WHERE row_id = ? AND change LIKE '{\"cells\"%'"
                                       " ORDER BY seq DESC LIMIT 1", (row_id,)).fetchone()
            if last is None:
                raise CommandError("Nothing to undo on this row.")
            props = {p["id"]: p for p in self._props(table_id)}
            cells = json.loads(row["cells"])
            for prop_id, (before, _after) in json.loads(last["change"])["cells"].items():
                prop = props.get(prop_id)
                if prop is None or prop["type"] in COMPUTED:
                    continue
                try:
                    value = cell_value(prop, before)
                except CommandError:
                    value = None
                if value is None:
                    cells.pop(prop_id, None)
                else:
                    cells[prop_id] = value
            self._c._db.execute("DELETE FROM cc_row_history WHERE seq = ?", (last["seq"],))
            self._c._db.execute("UPDATE cc_row SET cells = ?, version = version + 1, updated_at = ? WHERE id = ?",
                                (json.dumps(cells, ensure_ascii=False), self._c._clock(), row_id))
            self._history(row_id, actor, {"undo": True})
            return self._row_public(self._row(table_id, row_id), list(props.values()))

    def archive_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            self._row(table_id, row_id)
            self._c._db.execute("UPDATE cc_row SET archived_at = ? WHERE id = ?", (self._c._clock(), row_id))
            self._history(row_id, "owner", {"archived": True})
            self._touch(table_id)
            return {"id": row_id, "archived": True}

    def restore_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            if self._row(table_id, row_id)["archived_at"] is None:
                raise CommandError("That row is not in the trash.")
            self._c._db.execute("UPDATE cc_row SET archived_at = NULL, updated_at = ? WHERE id = ?", (self._c._clock(), row_id))
            self._history(row_id, "owner", {"restored": True})
            self._touch(table_id)
            return self._row_public(self._row(table_id, row_id), self._props(table_id))

    def delete_row(self, table_id: str, row_id: str) -> dict[str, Any]:
        with self._c._lock:
            self._live_table(table_id)
            if self._row(table_id, row_id)["archived_at"] is None:
                raise CommandError("Move the row to the trash first.")
            self._c._db.execute("DELETE FROM cc_row WHERE id = ?", (row_id,))
            self._c._db.execute("DELETE FROM cc_row_history WHERE row_id = ?", (row_id,))
            self._c._audit("owner", "table.row.delete", table_id, {"row": row_id})
            return {"id": row_id, "deleted": True}

    def export_csv(self, table_id: str, view_id: str | None = None) -> str:
        """The view as CSV. Personal properties are left out."""
        import csv
        import io
        data = self.rows(table_id, view_id, personal=False)
        props = [p for p in data["table"]["properties"] if not p["config"].get("personal") and p["id"] not in data["view"]["config"].get("hidden", [])]
        out = io.StringIO()
        writer = csv.writer(out)
        writer.writerow([p["name"] for p in props])
        for row in data["rows"]:
            writer.writerow([_csv_safe(display(p, row["cells"].get(p["id"]))) for p in props])
        return out.getvalue()

    # ------------------------------------------------------------------ helpers

    def _table(self, table_id: Any) -> Any:
        if not isinstance(table_id, str) or not TABLE_ID.match(table_id):
            raise CommandError("No such table.")
        row = self._c._db.execute("SELECT * FROM cc_table WHERE id = ?", (table_id,)).fetchone()
        if row is None:
            raise CommandError("No such table.")
        return row

    def _live_table(self, table_id: Any) -> Any:
        row = self._table(table_id)
        if row["archived_at"] is not None:
            raise CommandError("That table is in the trash.")
        return row

    def _row(self, table_id: str, row_id: Any) -> Any:
        if not isinstance(row_id, str) or not ROW_ID.match(row_id):
            raise CommandError("No such row.")
        row = self._c._db.execute("SELECT * FROM cc_row WHERE id = ? AND table_id = ?", (row_id, table_id)).fetchone()
        if row is None:
            raise CommandError("No such row.")
        return row

    def _props(self, table_id: str) -> list[dict[str, Any]]:
        return [{"id": r["id"], "name": r["name"], "type": r["type"], "config": json.loads(r["config"]), "position": r["position"]}
                for r in self._c._db.execute("SELECT * FROM cc_property WHERE table_id = ? ORDER BY position", (table_id,))]

    def _views(self, table_id: str) -> list[dict[str, Any]]:
        return [{"id": r["id"], "name": r["name"], "layout": r["layout"], "config": json.loads(r["config"]), "position": r["position"]}
                for r in self._c._db.execute("SELECT * FROM cc_view WHERE table_id = ? ORDER BY position", (table_id,))]

    def _count(self, table_id: str) -> int:
        return self._c._db.execute("SELECT COUNT(*) FROM cc_row WHERE table_id = ? AND archived_at IS NULL", (table_id,)).fetchone()[0]

    @staticmethod
    def _table_meta(r: Any) -> dict[str, Any]:
        return {"id": r["id"], "title": r["title"], "icon": r["icon"], "description": r["description"], "version": r["version"],
                "createdAt": r["created_at"], "updatedAt": r["updated_at"]}

    def _public(self, table_id: str) -> dict[str, Any]:
        row = self._table(table_id)
        return {**self._table_meta(row), "archivedAt": row["archived_at"], "properties": self._props(table_id),
                "views": self._views(table_id), "rows": self._count(table_id)}

    @staticmethod
    def _row_public(r: Any, props: list[dict[str, Any]]) -> dict[str, Any]:
        cells = json.loads(r["cells"])
        for p in props:
            if p["type"] == "created_time":
                cells[p["id"]] = _day(r["created_at"])
            elif p["type"] == "edited_time":
                cells[p["id"]] = _day(r["updated_at"])
        return {"id": r["id"], "tableId": r["table_id"], "cells": cells, "version": r["version"], "position": r["position"],
                "createdAt": r["created_at"], "updatedAt": r["updated_at"], "archivedAt": r["archived_at"],
                "hasPage": r["blocks"] != "[]"}

    def _insert_prop(self, table_id: str, name: str, kind: str, config: dict[str, Any]) -> dict[str, Any]:
        top = self._c._db.execute("SELECT MAX(position) FROM cc_property WHERE table_id = ?", (table_id,)).fetchone()[0]
        prop_id = _new("pr_", 8)
        self._c._db.execute("INSERT INTO cc_property(id, table_id, name, type, config, position) VALUES (?,?,?,?,?,?)",
                            (prop_id, table_id, name, kind, json.dumps(config, ensure_ascii=False), (top or 0) + 1))
        return {"id": prop_id, "name": name, "type": kind, "config": config}

    def _insert_view(self, table_id: str, name: str, layout: str, config: dict[str, Any]) -> dict[str, Any]:
        top = self._c._db.execute("SELECT MAX(position) FROM cc_view WHERE table_id = ?", (table_id,)).fetchone()[0]
        view_id = _new("vw_", 8)
        full = {"filters": [], "match": "and", "sorts": [], "groupBy": None, "hidden": [], "widths": {}, **config}
        self._c._db.execute("INSERT INTO cc_view(id, table_id, name, layout, config, position) VALUES (?,?,?,?,?,?)",
                            (view_id, table_id, name, layout, json.dumps(full), (top or 0) + 1))
        return {"id": view_id}

    def _view_config(self, value: Any, props: list[dict[str, Any]], layout: str) -> dict[str, Any]:
        raw = value or {}
        if not isinstance(raw, dict):
            raise CommandError("A view's settings are an object.")
        _only(raw, {"filters", "match", "sorts", "groupBy", "hidden", "widths"}, "view settings")
        by_id = {p["id"]: p for p in props}
        filters = raw.get("filters", [])
        if not isinstance(filters, list) or len(filters) > MAX_FILTERS:
            raise CommandError(f"A view has at most {MAX_FILTERS} filters.")
        clean_filters = []
        for f in filters:
            if not isinstance(f, dict):
                raise CommandError("A filter is {property, op, value?}.")
            _only(f, {"property", "op", "value"}, "filter")
            prop = by_id.get(f.get("property"))
            if prop is None:
                raise CommandError("A filter names a property of this table.")
            op = f.get("op")
            if op not in OPS_FOR[prop["type"]]:
                raise CommandError(f"{prop['name']} filters with: {', '.join(OPS_FOR[prop['type']])}.")
            clean_filters.append({"property": prop["id"], "op": op, "value": _filter_value(prop, op, f.get("value"))})
        match = raw.get("match", "and")
        if match not in ("and", "or"):
            raise CommandError("Filters match all (and) or any (or).")
        sorts = raw.get("sorts", [])
        if not isinstance(sorts, list) or len(sorts) > 5:
            raise CommandError("A view sorts by at most 5 properties.")
        clean_sorts = []
        for s in sorts:
            if not isinstance(s, dict):
                raise CommandError("A sort is {property, direction}.")
            _only(s, {"property", "direction"}, "sort")
            if s.get("property") not in by_id or s.get("direction", "asc") not in ("asc", "desc"):
                raise CommandError("A sort names a property of this table, ascending or descending.")
            clean_sorts.append({"property": s["property"], "direction": s.get("direction", "asc")})
        group = raw.get("groupBy")
        if group is not None and (group not in by_id or by_id[group]["type"] not in GROUPABLE):
            raise CommandError("A board groups by a select, status, multi-select or checkbox property.")
        if layout == "board" and group is None:
            group = next((p["id"] for p in props if p["type"] in ("status", "select")), None)
        hidden = raw.get("hidden", [])
        if not isinstance(hidden, list) or any(h not in by_id or by_id[h]["type"] == "title" for h in hidden):
            raise CommandError("Hide properties of this table (the title always shows).")
        widths = raw.get("widths", {})
        if not isinstance(widths, dict) or any(k not in by_id or type(v) is not int or not 60 <= v <= 800 for k, v in widths.items()):
            raise CommandError("Column widths are 60..800 pixels per property.")
        return {"filters": clean_filters, "match": match, "sorts": clean_sorts, "groupBy": group,
                "hidden": list(dict.fromkeys(hidden)), "widths": widths}

    @staticmethod
    def _scrub_view(config: dict[str, Any], prop_id: str) -> dict[str, Any]:
        config["filters"] = [f for f in config.get("filters", []) if f["property"] != prop_id]
        config["sorts"] = [s for s in config.get("sorts", []) if s["property"] != prop_id]
        config["hidden"] = [h for h in config.get("hidden", []) if h != prop_id]
        config.get("widths", {}).pop(prop_id, None)
        if config.get("groupBy") == prop_id:
            config["groupBy"] = None
        return config

    def _cells(self, props: list[dict[str, Any]], changes: Any, old: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(changes, dict):
            raise CommandError("cells is {propertyId: value}.")
        by_id = {p["id"]: p for p in props}
        unknown = [k for k in changes if k not in by_id]
        if unknown:
            raise CommandError("A cell names a property this table doesn't have.")
        cells = {k: v for k, v in old.items() if k in by_id and by_id[k]["type"] not in COMPUTED}
        for prop_id, value in changes.items():
            clean = cell_value(by_id[prop_id], value)
            if clean is None or clean is False and by_id[prop_id]["type"] == "checkbox":
                cells.pop(prop_id, None)
            else:
                cells[prop_id] = clean
        return cells

    def _convert(self, table_id: str, prop: dict[str, Any], kind: str, config: dict[str, Any]) -> dict[str, Any]:
        """A property changed type: each cell is converted where it makes sense, otherwise cleared."""
        rows = self._c._db.execute("SELECT id, cells FROM cc_row WHERE table_id = ?", (table_id,)).fetchall()
        texts = {r["id"]: display(prop, json.loads(r["cells"]).get(prop["id"])) for r in rows}
        if kind in ("select", "multi_select", "status") and not config.get("options"):
            names: list[str] = []
            for text in texts.values():
                for piece in (text.split(",") if kind == "multi_select" else [text]):
                    piece = piece.strip()[:100]
                    if piece and piece.lower() not in {n.lower() for n in names} and len(names) < MAX_OPTIONS:
                        names.append(piece)
            config = _config(kind, {"options": [{"name": n, "color": COLORS[(i % (len(COLORS) - 1)) + 1],
                                                  **({"group": "todo"} if kind == "status" else {})} for i, n in enumerate(names)]})
        target = {"id": prop["id"], "name": prop["name"], "type": kind, "config": config}
        lookup = {o["name"].lower(): o["id"] for o in config.get("options", [])}
        for r in rows:
            cells = json.loads(r["cells"])
            text = texts[r["id"]]
            cells.pop(prop["id"], None)
            value: Any = None
            if text:
                if kind in ("text", "title"):
                    value = text[:MAX_TEXT]
                elif kind in ("number", "currency", "percent"):
                    try:
                        value = float(text.replace(",", ".").replace("€", "").replace("$", "").strip())
                        value = int(value) if value.is_integer() else value
                    except ValueError:
                        value = None
                elif kind in ("select", "status"):
                    value = lookup.get(text.strip().lower())
                elif kind == "multi_select":
                    value = [lookup[p.strip().lower()] for p in text.split(",") if p.strip().lower() in lookup] or None
                elif kind == "checkbox":
                    value = text.strip().lower() in ("yes", "true", "1", "x", "✓") or None
                else:
                    try:
                        value = cell_value(target, text)
                    except CommandError:
                        value = None
            if value is not None:
                cells[prop["id"]] = value
            self._c._db.execute("UPDATE cc_row SET cells = ? WHERE id = ?", (json.dumps(cells, ensure_ascii=False), r["id"]))
        for v in self._c._db.execute("SELECT id, config FROM cc_view WHERE table_id = ?", (table_id,)).fetchall():
            view_config = json.loads(v["config"])
            view_config["filters"] = [f for f in view_config.get("filters", []) if f["property"] != prop["id"]]
            if view_config.get("groupBy") == prop["id"] and kind not in GROUPABLE:
                view_config["groupBy"] = None
            self._c._db.execute("UPDATE cc_view SET config = ? WHERE id = ?", (json.dumps(view_config), v["id"]))
        return config

    def _drop_missing_options(self, table_id: str, prop: dict[str, Any], config: dict[str, Any]) -> None:
        keep = {o["id"] for o in config.get("options", [])}
        for r in self._c._db.execute("SELECT id, cells FROM cc_row WHERE table_id = ?", (table_id,)).fetchall():
            cells = json.loads(r["cells"])
            value = cells.get(prop["id"])
            if value is None:
                continue
            if isinstance(value, list):
                kept = [v for v in value if v in keep]
                if kept == value:
                    continue
                if kept:
                    cells[prop["id"]] = kept
                else:
                    cells.pop(prop["id"])
            elif value not in keep:
                cells.pop(prop["id"])
            else:
                continue
            self._c._db.execute("UPDATE cc_row SET cells = ? WHERE id = ?", (json.dumps(cells, ensure_ascii=False), r["id"]))

    def _position(self, table_id: str, before: Any, *, exclude: str | None = None) -> float:
        if before is None:
            top = self._c._db.execute("SELECT MAX(position) FROM cc_row WHERE table_id = ?", (table_id,)).fetchone()[0]
            return float(top) + 1.0 if top is not None else 1.0
        brow = self._row(table_id, before)
        prev = self._c._db.execute("SELECT MAX(position) FROM cc_row WHERE table_id = ? AND position < ? AND id IS NOT ?",
                                   (table_id, brow["position"], exclude)).fetchone()[0]
        return (brow["position"] + (prev if prev is not None else brow["position"] - 2)) / 2

    def _history(self, row_id: str, actor: str, change: dict[str, Any]) -> None:
        self._c._db.execute("INSERT INTO cc_row_history(row_id, at, actor, change) VALUES (?,?,?,?)",
                            (row_id, self._c._clock(), actor, json.dumps(change, ensure_ascii=False)))
        extra = self._c._db.execute("SELECT seq FROM cc_row_history WHERE row_id = ? ORDER BY seq DESC LIMIT -1 OFFSET ?",
                                    (row_id, MAX_HISTORY)).fetchall()
        self._c._db.executemany("DELETE FROM cc_row_history WHERE seq = ?", [(e["seq"],) for e in extra])

    def _touch(self, table_id: str) -> None:
        self._c._db.execute("UPDATE cc_table SET updated_at = ?, version = version + 1 WHERE id = ?", (self._c._clock(), table_id))


# ---------------------------------------------------------------------- filtering, sorting, grouping (pure)

def _day(ms: int) -> dict[str, str]:
    from datetime import datetime, timezone
    return {"start": datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M")}


def _csv_safe(text: str) -> str:
    """A cell never starts a spreadsheet formula."""
    return "'" + text if text[:1] in ("=", "+", "-", "@") and not re.match(r"^-?\d", text) else text


def _filter_value(prop: dict[str, Any], op: str, value: Any) -> Any:
    if op in ("empty", "not_empty"):
        return None
    kind = prop["type"]
    if kind in OPS_FOR and OPS_FOR[kind] is TEXT_OPS:
        return _text(value if isinstance(value, str) else "", "A filter's text", 200)
    if kind in ("number", "currency", "percent"):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise CommandError(f"{prop['name']} filters with a number.")
        return value
    if kind == "checkbox":
        if not isinstance(value, bool):
            raise CommandError(f"{prop['name']} filters with checked or not.")
        return value
    if kind in ("date", "created_time", "edited_time"):
        if not isinstance(value, str) or not DAY.match(value):
            raise CommandError(f"{prop['name']} filters with a date (YYYY-MM-DD).")
        return value[:10]
    options = {o["id"] for o in prop["config"].get("options", [])}
    if value not in options:
        raise CommandError(f"{prop['name']} filters with one of its options.")
    return value


def _test(prop: dict[str, Any], op: str, want: Any, value: Any) -> bool:
    empty = value is None or value == "" or value == [] or (prop["type"] == "checkbox" and value is False)
    if op == "empty":
        return empty
    if op == "not_empty":
        return not empty
    kind = prop["type"]
    if kind == "checkbox":
        return bool(value) == want
    if kind in ("number", "currency", "percent"):
        if empty:
            return op == "ne"
        return {"eq": value == want, "ne": value != want, "gt": value > want, "lt": value < want,
                "gte": value >= want, "lte": value <= want}[op]
    if kind in ("date", "created_time", "edited_time"):
        if empty:
            return False
        start = value["start"][:10]
        end = (value.get("end") or value["start"])[:10]
        return {"is": start <= want <= end, "before": start < want, "after": start > want,
                "on_or_before": start <= want, "on_or_after": start >= want}[op]
    if kind in ("select", "status"):
        return (value == want) if op == "is" else (value != want)
    if kind == "multi_select":
        has = want in (value or [])
        return has if op == "contains" else not has
    text = (value or "").lower()
    needle = (want or "").lower()
    return {"contains": needle in text, "not_contains": needle not in text, "is": text == needle, "is_not": text != needle,
            "starts_with": text.startswith(needle)}[op]


def _matches(row: dict[str, Any], filters: list[dict[str, Any]], match: str, props: dict[str, dict[str, Any]]) -> bool:
    if not filters:
        return True
    results = [_test(props[f["property"]], f["op"], f["value"], row["cells"].get(f["property"]))
               for f in filters if f["property"] in props]
    if not results:
        return True
    return all(results) if match == "and" else any(results)


def _sort_key(prop: dict[str, Any]) -> Callable[[dict[str, Any]], Any]:
    kind = prop["type"]
    order = {o["id"]: i for i, o in enumerate(prop["config"].get("options", []))}

    def key(row: dict[str, Any]) -> Any:
        value = row["cells"].get(prop["id"])
        if value is None or value == [] or value == "":
            return (1, 0)  # Empty last, whichever way.
        if kind in ("number", "currency", "percent"):
            return (0, value)
        if kind in ("select", "status"):
            return (0, order.get(value, len(order)))
        if kind == "multi_select":
            return (0, min(order.get(v, len(order)) for v in value))
        if kind == "checkbox":
            return (0, 0 if value else 1)
        if kind in ("date", "created_time", "edited_time"):
            return (0, value["start"])
        return (0, str(value).lower())
    return key


def _groups(prop: dict[str, Any], rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Board columns: one per option in its order (checkbox: checked / not), then "No value"."""
    if prop["type"] == "checkbox":
        buckets = [{"key": True, "name": "Checked", "color": "green"}, {"key": False, "name": "Not checked", "color": "default"}]
        for b in buckets:
            b["rowIds"] = [r["id"] for r in rows if bool(r["cells"].get(prop["id"])) == b["key"]]
        return buckets
    buckets = [{"key": o["id"], "name": o["name"], "color": o["color"], **({"group": o["group"]} if "group" in o else {}), "rowIds": []}
               for o in prop["config"].get("options", [])]
    by_key = {b["key"]: b for b in buckets}
    none: list[str] = []
    for r in rows:
        value = r["cells"].get(prop["id"])
        keys = value if isinstance(value, list) else [value]
        placed = False
        for k in keys:
            if k in by_key:
                by_key[k]["rowIds"].append(r["id"])
                placed = True
        if not placed:
            none.append(r["id"])
    return [{"key": None, "name": f"No {prop['name']}", "color": "default", "rowIds": none}] + buckets
