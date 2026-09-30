"""Accounts, rebuilt (plan 43 T5 + T6): phones, their apps, the accounts in each app, and the sign-ups Cyclone mapped.

- **Maps come from the phone.** Cyclone Mobile walks an app's sign-up once (a mapping task) and keeps its Sign-up
  Map: pages, field labels and kinds, format hints, choices and the steps only a person can do. The PC keeps a copy
  so Glass can show it while the phone is away. A map is a template: it never holds a value (checked here and on the
  phone).
- **A map becomes a table.** Each field becomes a column, so the next accounts are rows the owner fills in. Passwords
  are never a column: the phone makes them in the vault.
- **Only accounts the owner owns or manages.** A mapping task says whose account it is (mine, company, client), and
  the final control that creates the account always asks the owner on the phone.
"""
from __future__ import annotations

import json
from typing import TYPE_CHECKING, Any

from ..desktop_runtime.models import DesktopRuntimeError
from ..desktop_runtime.v5_contract import SIGNUP_PACKAGE, validate_signup_map
from .center import CommandError
from .pages import _only

if TYPE_CHECKING:  # pragma: no cover
    from .center import CommandCenter

SIGNUP_SCHEMA = """
CREATE TABLE IF NOT EXISTS signup_map (
  device_id TEXT NOT NULL, package TEXT NOT NULL, map TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(device_id, package));
CREATE TABLE IF NOT EXISTS signup_table (
  device_id TEXT NOT NULL, package TEXT NOT NULL, table_id TEXT NOT NULL, mapped_at INTEGER NOT NULL, PRIMARY KEY(device_id, package));
"""

RECIPE = "signup_map:"
BASES = {"mine": "the owner's own", "company": "the owner's company's", "client": "a client's (the owner manages it)"}
#: A sign-up field kind → a table property (type, personal). Passwords and photos are never columns.
COLUMN = {
    "text": ("text", False), "first_name": ("text", True), "last_name": ("text", True), "full_name": ("text", True),
    "email": ("email", True), "phone": ("phone_number", True), "username": ("text", False), "birthday": ("date", True),
    "date": ("date", False), "gender": ("select", True), "choice": ("select", False), "checkbox": ("checkbox", False),
    "number": ("number", False),
}
STATUS = (("Draft", "gray", "todo"), ("Ready", "blue", "todo"), ("Queued", "purple", "doing"), ("Creating", "yellow", "doing"),
          ("Needs verification", "orange", "doing"), ("Created", "green", "done"), ("Failed", "red", "done"))
CHECK_LABEL = {"email_code": "email code", "sms_code": "SMS code", "captcha": "CAPTCHA", "selfie": "selfie", "id_document": "ID document",
               "phone_call": "phone call", "other": "a person's step"}


def recipe_package(recipe: Any) -> str | None:
    """The app a task maps, when it is a sign-up mapping task."""
    if isinstance(recipe, str) and recipe.startswith(RECIPE) and SIGNUP_PACKAGE.match(recipe[len(RECIPE):]):
        return recipe[len(RECIPE):]
    return None


class SignupStore:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center
        with center._lock:
            center._db.executescript(SIGNUP_SCHEMA)

    # ------------------------------------------------------------------ maps

    def maps(self, device_id: Any) -> dict[str, Any]:
        """The phone's maps, fetched now; the last copy when the phone can't be reached."""
        device = self._device(device_id)
        fresh = True
        try:
            result = self._c._contract.signup_maps(device)
        except (DesktopRuntimeError, AttributeError):
            fresh, result = False, None
        if result is not None:
            with self._c._lock:
                now = self._c._clock()
                self._c._db.execute("DELETE FROM signup_map WHERE device_id = ?", (device,))
                self._c._db.executemany("INSERT INTO signup_map(device_id, package, map, fetched_at) VALUES (?,?,?,?)",
                                        [(device, m["package"], json.dumps(m, ensure_ascii=False), now) for m in result["maps"]])
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM signup_map WHERE device_id = ? ORDER BY package", (device,)).fetchall()
            tables = {r["package"]: r["table_id"] for r in self._c._db.execute("SELECT package, table_id FROM signup_table WHERE device_id = ?", (device,))}
            live_tables = {t["id"] for t in self._c.tables.list()}
        maps = []
        for r in rows:
            item = json.loads(r["map"])
            table = tables.get(r["package"])
            maps.append({**item, "fetchedAt": r["fetched_at"], "tableId": table if table in live_tables else None})
        out: dict[str, Any] = {"deviceId": device, "maps": maps, "fresh": fresh}
        if not fresh:
            out["note"] = "The phone couldn't be reached; these are the last maps it sent."
        return out

    def map_signup(self, body: Any, *, actor: str = "owner") -> dict[str, Any]:
        """Starts a mapping task: the phone walks the app's sign-up once, for the owner's first account there."""
        if not isinstance(body, dict):
            raise CommandError("Send {deviceId, package, app, ownerBasis}.")
        _only(body, {"deviceId", "package", "app", "ownerBasis"}, "sign-up mapping")
        device = self._device(body.get("deviceId"))
        package = body.get("package")
        if not isinstance(package, str) or not SIGNUP_PACKAGE.match(package):
            raise CommandError("package is the app's Android package name.")
        app = body.get("app") if isinstance(body.get("app"), str) and body.get("app", "").strip() else package
        app = app.strip()[:80]
        basis = body.get("ownerBasis")
        if basis not in BASES:
            raise CommandError("Say whose account it is: mine, company or client. Cyclone only makes accounts you own or manage.")
        goal = (f"Map the sign-up of {app} ({package}) for Cyclone's Account Setup. The first account is {BASES[basis]}; "
                "ask the owner for its details on the phone. Record every sign-up page, hand any verification to the owner, "
                "and create the account only after the owner approves the final step.")
        return self._c.create_task({"title": f"Map the sign-up of {app}", "goal": goal, "deviceId": device, "recipe": RECIPE + package},
                                   actor=actor)

    def forget(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {deviceId, package}.")
        _only(body, {"deviceId", "package"}, "forget")
        device = self._device(body.get("deviceId"))
        package = body.get("package")
        if not isinstance(package, str) or not SIGNUP_PACKAGE.match(package):
            raise CommandError("package is the app's Android package name.")
        try:
            self._c._contract.signup_forget(device, package)
        except DesktopRuntimeError as exc:
            raise CommandError(f"The phone couldn't forget it now: {exc}") from exc
        with self._c._lock:
            self._c._db.execute("DELETE FROM signup_map WHERE device_id = ? AND package = ?", (device, package))
            self._c._audit("owner", "signup.forget", package, {"device": device})
        return {"forgotten": True}

    # ------------------------------------------------------------------ the sign-up table

    def make_table(self, body: Any) -> dict[str, Any]:
        """Turns a map into a table: one column per field, plus the phone, whose account it is, and the status."""
        if not isinstance(body, dict):
            raise CommandError("Send {deviceId, package}.")
        _only(body, {"deviceId", "package"}, "sign-up table")
        device = self._device(body.get("deviceId"))
        package = body.get("package")
        with self._c._lock:
            row = self._c._db.execute("SELECT map FROM signup_map WHERE device_id = ? AND package = ?", (device, package)).fetchone()
            existing = self._c._db.execute("SELECT table_id FROM signup_table WHERE device_id = ? AND package = ?", (device, package)).fetchone()
        if row is None:
            raise CommandError("This phone has no sign-up map for that app yet. Map the sign-up first.")
        signup = json.loads(row["map"])
        validate_signup_map(signup)
        if existing is not None and any(t["id"] == existing["table_id"] for t in self._c.tables.list()):
            return self._c.tables.get(existing["table_id"])
        t = self._c.tables
        checks = sorted({CHECK_LABEL[p["check"]] for p in signup["pages"] if p["check"]})
        description = (f"Accounts for Cyclone to create with the {signup['app']} sign-up it mapped ({len(signup['pages'])} pages). "
                       "Fill a row, set it to Ready, and Cyclone makes the account on the phone. Passwords are made in the vault on the "
                       "phone, never kept here." + (f" Needs a person for: {', '.join(checks)}." if checks else ""))
        table = t.create({"title": f"{signup['app']} sign-ups", "icon": "🪪", "description": description[:1000]})
        tid = table["id"]
        title = next(p for p in table["properties"] if p["type"] == "title")
        status = next(p for p in table["properties"] if p["type"] == "status")
        t.update_property(tid, title["id"], {"name": "Account"})
        t.update_property(tid, status["id"], {"config": {"options": [{"name": n, "color": c, "group": g} for n, c, g in STATUS]}})
        taken = {"account", "status"}
        for page in signup["pages"]:
            if page["check"]:
                continue  # A person's step (a code, a CAPTCHA) is never a column.
            for field in page["fields"]:
                column = COLUMN.get(field["kind"])
                if column is None:
                    continue
                kind, personal = column
                name = field["label"][:70]
                while name.lower() in taken:
                    name = f"{name} ({field['key']})"[:80]
                taken.add(name.lower())
                config: dict[str, Any] = {"personal": True} if personal else {}
                if kind == "select":
                    config["options"] = [{"name": c[:100]} for c in dict.fromkeys(field["choices"])][:100]
                try:
                    t.add_property(tid, {"name": name, "type": kind, "config": config})
                except CommandError:
                    continue  # A label the table refuses (it reads like a secret) stays on the phone's map only.
        t.add_property(tid, {"name": "Whose account", "type": "select", "config": {"options": [
            {"name": "Mine", "color": "blue"}, {"name": "Company", "color": "purple"}, {"name": "Client", "color": "orange"}]}})
        t.add_property(tid, {"name": "Phone", "type": "relation", "config": {"target": "sys:phones"}})
        t.add_property(tid, {"name": "Cyclone account", "type": "relation", "config": {"target": "sys:accounts"}})
        t.add_property(tid, {"name": "Notes", "type": "text"})
        with self._c._lock:
            self._c._db.execute("INSERT OR REPLACE INTO signup_table(device_id, package, table_id, mapped_at) VALUES (?,?,?,?)",
                                (device, package, tid, signup["mappedAt"]))
            self._c._audit("owner", "signup.table", package, {"device": device, "table": tid})
        return t.get(tid)

    # ------------------------------------------------------------------ helpers

    def _device(self, device_id: Any) -> str:
        if not isinstance(device_id, str) or not device_id or len(device_id) > 120:
            raise CommandError("Name the phone by its deviceId.")
        return device_id
