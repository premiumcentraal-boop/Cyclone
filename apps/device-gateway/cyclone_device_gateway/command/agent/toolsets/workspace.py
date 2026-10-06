"""The workspace toolset (plan 33 §7 and plan 43 T3, moved here unchanged by plan 53 R1).

Reads of pages, tasks, routines, results, phones, accounts, connections, approvals and tables; page, card and row
edits; tasks, routines and button presses as proposals. A change is checked when it is proposed (``prepare``) and
again when it is made (``execute``), because the workspace may have moved on in between.
"""
from __future__ import annotations

import json
import re
from datetime import datetime
from typing import TYPE_CHECKING, Any

from ... import openapi
from ... import pagetext
from ... import schedule as schedules
from ...center import CommandError
from ..common import _only, _schema, _text_arg
from ..registry import REGISTRY

if TYPE_CHECKING:  # pragma: no cover
    from ...center import CommandCenter

_S = {"type": "string"}
_PAGE = {"type": "string", "description": "A page id (pg_…)."}
_MD = {"type": "string", "description": "Markdown, one block per line."}
_DAY = {"type": "string", "description": "A date, YYYY-MM-DD."}
_STATUS = {"type": "string", "enum": ["todo", "doing", "done"]}
_LINKS = {"type": "array", "items": {"type": "string"}, "description": "Things to link, as kind:id (routine:rtn_…, device:…, account:acc_…, page:pg_…, task:tsk_…, connection:con_…)."}

#: name -> (kind, description, JSON schema). kind: read runs at once; workspace edits pages; phone makes phone work.
_SPECS: dict[str, tuple[str, str, dict[str, Any]]] = {
    "list_pages": ("read", "The workspace's pages (id, title, parent).", _schema({}, [])),
    "search_pages": ("read", "Find pages by words in their title or text.", _schema({"query": _S}, ["query"])),
    "read_page": ("read", "One page as Markdown, each block tagged with its id, with its plan cards.", _schema({"pageId": _PAGE}, ["pageId"])),
    "list_tasks": ("read", "Command Center tasks: what phones are doing or did.", _schema({"status": {"type": "string", "enum": ["open", "done", "all"]}}, [])),
    "list_routines": ("read", "Routines: tasks that repeat on a schedule.", _schema({}, [])),
    "list_results": ("read", "The latest finished runs and their outcome.", _schema({"limit": {"type": "integer", "minimum": 1, "maximum": 50}}, [])),
    "list_phones": ("read", "The owner's phones and whether each is ready.", _schema({}, [])),
    "list_accounts": ("read", "The accounts phones use (names and health only).", _schema({}, [])),
    "list_connections": ("read", "Connected services (MCP servers and APIs) and their allowed tools.", _schema({}, [])),
    "list_approvals": ("read", "What is waiting for the owner's OK (read only; you cannot answer them).", _schema({}, [])),
    "create_page": ("workspace", "Create a page, optionally inside another page.",
                    _schema({"title": _S, "icon": {"type": "string", "description": "One emoji."}, "parentId": _PAGE, "content": _MD}, ["title"])),
    "append_to_page": ("workspace", "Add blocks to a page, at the end or after a block.",
                       _schema({"pageId": _PAGE, "content": _MD, "afterBlockId": {"type": "string"}}, ["pageId", "content"])),
    "update_block": ("workspace", "Rewrite one text block, or tick or untick a to-do.",
                     _schema({"pageId": _PAGE, "blockId": _S, "content": {"type": "string", "description": "One line of Markdown."},
                              "checked": {"type": "boolean"}}, ["pageId", "blockId"])),
    "rename_page": ("workspace", "Change a page's title or icon.", _schema({"pageId": _PAGE, "title": _S, "icon": _S}, ["pageId"])),
    "add_card": ("workspace", "Add a card to a page's plan board (a board is added if the page has none).",
                 _schema({"pageId": _PAGE, "boardId": _S, "title": _S, "status": _STATUS, "due": _DAY, "note": _S, "links": _LINKS},
                         ["pageId", "title"])),
    "update_card": ("workspace", "Change a plan card's status, title, date, note or links.",
                    _schema({"pageId": _PAGE, "cardId": _S, "title": _S, "status": _STATUS, "due": _DAY, "note": _S, "links": _LINKS},
                            ["pageId", "cardId"])),
    "create_task": ("phone", "Propose a task: a goal in plain words that one phone carries out.",
                    _schema({"goal": _S, "title": _S, "deviceId": _S, "accountId": _S}, ["goal"])),
    "create_routine": ("phone", "Propose a routine: a task that repeats.",
                       _schema({"title": _S, "goal": _S, "deviceIds": {"type": "array", "items": _S}, "accountId": _S,
                                 "schedule": {"type": "object", "description": "{kind: daily, time: HH:MM, days: [1..7, Monday = 1]} or {kind: every, minutes: 15..10080}"}},
                                ["title", "goal", "schedule"])),
    "run_routine": ("phone", "Propose running a routine now.", _schema({"routineId": _S}, ["routineId"])),
    "pause_routine": ("phone", "Propose pausing or resuming a routine.", _schema({"routineId": _S, "paused": {"type": "boolean"}}, ["routineId", "paused"])),
    # Plan 43 (T3): tables and their buttons, for agents too. Personal columns are masked; a press is a proposal.
    "list_tables": ("read", "The owner's tables: id, title, properties (name, type, options) and buttons.", _schema({}, [])),
    "query_table": ("read", "A table's rows as a view shows them (filters and sorts computed), values as text. Personal columns are hidden.",
                    _schema({"tableId": _S, "viewId": _S, "query": {"type": "string", "description": "Words to find in the rows."},
                             "limit": {"type": "integer", "minimum": 1, "maximum": 100}}, ["tableId"])),
    "create_row": ("workspace", "Add a row. cells: {Property name: value}; choices by option name, dates YYYY-MM-DD, links by row id.",
                   _schema({"tableId": _S, "cells": {"type": "object"}}, ["tableId", "cells"])),
    "update_row": ("workspace", "Change some of a row's cells, by property name.", _schema({"tableId": _S, "rowId": _S, "cells": {"type": "object"}},
                                                                                           ["tableId", "rowId", "cells"])),
    "press_button": ("phone", "Propose pressing a row's button (it may start phone work).",
                     _schema({"tableId": _S, "rowId": _S, "button": {"type": "string", "description": "The button property's name or id."}},
                             ["tableId", "rowId", "button"])),
}


class WorkspaceTools:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center

    def label(self, name: str, args: dict[str, Any]) -> str:
        def title_of(page_id: Any) -> str:
            with self._c._lock:
                row = self._c._db.execute("SELECT title FROM page WHERE id = ?", (page_id,)).fetchone() if isinstance(page_id, str) else None
            return f"“{row['title']}”" if row else "a page"
        labels = {
            "list_pages": lambda: "Looked through your pages",
            "search_pages": lambda: f"Searched pages for “{str(args.get('query', ''))[:40]}”",
            "read_page": lambda: f"Read {title_of(args.get('pageId'))}",
            "list_tasks": lambda: "Checked the tasks",
            "list_routines": lambda: "Checked the routines",
            "list_results": lambda: "Checked recent results",
            "list_phones": lambda: "Checked the phones",
            "list_accounts": lambda: "Checked the accounts",
            "list_connections": lambda: "Checked the connections",
            "list_approvals": lambda: "Checked what is waiting for you",
            "create_page": lambda: f"New page “{str(args.get('title', ''))[:60]}”",
            "append_to_page": lambda: f"Added to {title_of(args.get('pageId'))}",
            "update_block": lambda: f"Edited a line in {title_of(args.get('pageId'))}",
            "rename_page": lambda: f"Renamed {title_of(args.get('pageId'))}",
            "add_card": lambda: f"Card “{str(args.get('title', ''))[:60]}” on {title_of(args.get('pageId'))}",
            "update_card": lambda: f"Updated a card on {title_of(args.get('pageId'))}",
            "create_task": lambda: f"Task: {str(args.get('title') or args.get('goal') or '')[:80]}",
            "create_routine": lambda: f"Routine “{str(args.get('title', ''))[:60]}”",
            "run_routine": lambda: "Run a routine now",
            "pause_routine": lambda: "Resume a routine" if args.get("paused") is False else "Pause a routine",
            "list_tables": lambda: "Read the tables",
            "query_table": lambda: "Read a table",
            "create_row": lambda: "Add a row",
            "update_row": lambda: "Change a row",
            "press_button": lambda: f"Press “{str(args.get('button', ''))[:40]}”",
        }
        return labels.get(name, lambda: f"Unknown tool {name[:40]}")()

    # --- reading

    def _iso(self, ms: Any) -> str | None:
        return datetime.fromtimestamp(ms / 1000).astimezone().strftime("%Y-%m-%d %H:%M") if isinstance(ms, int) else None

    def read(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        c = self._c
        if name == "list_pages":
            return {"pages": [{"id": p["id"], "title": p["title"], "icon": p["icon"], "parentId": p["parentId"]} for p in c.pages.tree()[:400]]}
        if name == "search_pages":
            query = _text_arg(args, "query", 100)
            return {"pages": [{"id": p["id"], "title": p["title"], "snippet": p["snippet"]} for p in c.pages.search(query)[:20]]}
        if name == "read_page":
            page = c.pages.get(args.get("pageId"))
            if page.get("archivedAt"):
                raise CommandError("That page is in the trash.")
            return {"id": page["id"], "title": page["title"], "path": [p["title"] for p in page["path"]],
                    "pagesInside": [{"id": p["id"], "title": p["title"]} for p in page["children"]],
                    "mentionedIn": [{"id": p["id"], "title": p["title"]} for p in page["backlinks"]],
                    "markdown": pagetext.page_markdown(page)}
        if name == "list_tasks":
            status = args.get("status", "open")
            if status not in ("open", "done", "all"):
                raise CommandError("status is open, done or all.")
            tasks = c.list_tasks(status=None if status == "all" else status, limit=50)
            return {"tasks": [{"id": t["id"], "title": t["title"], "goal": t["goal"][:300], "status": t["status"], "cause": t["cause"],
                               "deviceId": t["deviceId"], "accountId": t["accountId"], "routineId": t["routineId"],
                               "updated": self._iso(t["updatedAt"]),
                               "outcome": openapi.hide_secrets(t["run"]["summary"])[:400] if t.get("run") else None} for t in tasks]}
        if name == "list_routines":
            return {"routines": [{"id": r["id"], "title": r["title"], "goal": r["goal"][:300], "schedule": r["scheduleLabel"],
                                  "paused": r["paused"], "nextRun": self._iso(r["nextRunAt"]), "lastRun": self._iso(r["lastRunAt"]),
                                  "succeeded": r["succeeded"], "failed": r["failed"], "deviceIds": r["deviceIds"], "accountId": r["accountId"]}
                                 for r in c.list_routines()]}
        if name == "list_results":
            limit = args.get("limit", 20)
            if type(limit) is not int or not 1 <= limit <= 50:
                raise CommandError("limit is 1..50.")
            return {"results": [{"taskId": r["taskId"], "title": r["title"], "status": r["status"], "deviceId": r["deviceId"],
                                 "summary": openapi.hide_secrets(r["summary"] or "")[:400], "ended": self._iso(r["endedAt"])}
                                for r in c.results(limit)]}
        if name == "list_phones":
            return {"phones": [{"id": d["deviceId"], "name": d.get("name"), "state": d.get("state"), "ready": str(d.get("state") or "").upper() == "READY"}
                               for d in c._devices()]}
        if name == "list_accounts":
            return {"accounts": [{"id": a["id"], "service": a["service"], "handle": a["handle"], "status": a["status"],
                                  "lastOutcome": a["lastOutcome"], "inUse": a["locked"]} for a in c.list_accounts()]}
        if name == "list_connections":
            return {"connections": [{"id": x["id"], "name": x["name"], "kind": x["kind"], "status": x["status"], "allowedTools": x["allowed"]}
                                    for x in c.connections.list()]}
        if name == "list_tables":
            out = []
            for meta in c.tables.list():
                table = c.tables.get(meta["id"])
                out.append({"id": table["id"], "title": table["title"], "rows": meta["rows"],
                            "views": [{"id": v["id"], "name": v["name"], "layout": v["layout"]} for v in table["views"]],
                            "properties": [{"name": p["name"], "type": p["type"], **({"options": [o["name"] for o in p["config"].get("options", [])]}
                                                                                   if p["config"].get("options") else {}),
                                            **({"personal": True} if p["config"].get("personal") else {}),
                                            **({"button": p["config"]["label"]} if p["type"] == "button" else {})}
                                           for p in table["properties"]]})
            return {"tables": out}
        if name == "query_table":
            from ...tables import _shown
            data = c.tables.rows(args["tableId"], args.get("viewId"), args.get("query"), personal=False)
            props = [p for p in data["table"]["properties"] if not p["config"].get("personal")]
            limit = args.get("limit") or 30
            return {"table": data["table"]["title"], "view": data["view"]["name"], "total": data["total"],
                    "rows": [{"id": r["id"], **{p["name"]: _shown(p, r["cells"].get(p["id"]), data["links"]) for p in props}}
                             for r in data["rows"][:limit]]}
        if name == "list_approvals":
            return {"waiting": [{"title": a["title"], "kind": a["kind"], "text": openapi.hide_secrets(a["text"])[:300], "deviceId": a["deviceId"]}
                                for a in c.list_approvals()]}
        raise CommandError(f"There is no tool {name}.")

    # --- changes: checked when proposed, and again when applied

    def lookup(self, kind: str, ident: str) -> str | None:
        from ...pages import REF_IDS
        if kind not in REF_IDS or not REF_IDS[kind].match(ident):
            return None
        db = self._c._db
        if kind == "device":
            for d in self._c._devices():
                if d.get("deviceId") == ident:
                    return str(d.get("name") or ident)
            return None
        query = {"page": "SELECT title AS label FROM page WHERE id = ? AND archived_at IS NULL",
                 "routine": "SELECT title AS label FROM routine WHERE id = ?",
                 "task": "SELECT title AS label FROM task WHERE id = ?",
                 "account": "SELECT handle AS label FROM account WHERE id = ?",
                 "connection": "SELECT name AS label FROM connection WHERE id = ?"}.get(kind)
        row = db.execute(query, (ident,)).fetchone() if query else None
        return row["label"] if row else None

    def _links(self, value: Any) -> list[dict[str, Any]]:
        if value is None:
            return []
        if not isinstance(value, list) or len(value) > 10:
            raise CommandError("links is a list of at most 10 kind:id entries.")
        refs = []
        for entry in value:
            if not isinstance(entry, str) or ":" not in entry:
                raise CommandError("Each link is kind:id, like routine:rtn_abc.")
            kind, ident = entry.split(":", 1)
            if kind not in pagetext.MENTION_KINDS:
                raise CommandError(f"A link names one of: {', '.join(pagetext.MENTION_KINDS)}.")
            label = self.lookup(kind, ident)
            if label is None:
                raise CommandError(f"There is no {kind} {ident}. Look ids up with the list tools.")
            refs.append({"kind": kind, "id": ident, "label": label[:120]})
        return refs

    def _due(self, value: Any) -> int | None:
        if value is None or value == "":
            return None
        if not isinstance(value, str) or not re.match(r"^\d{4}-\d{2}-\d{2}$", value):
            raise CommandError("due is a date, YYYY-MM-DD.")
        try:
            day = datetime.strptime(value, "%Y-%m-%d").replace(hour=12)
        except ValueError as exc:
            raise CommandError("due is a real date, YYYY-MM-DD.") from exc
        return int(day.astimezone().timestamp() * 1000)

    def _page(self, args: dict[str, Any]) -> Any:
        row = self._c.pages._row(args.get("pageId"))
        if row["archived_at"] is not None:
            raise CommandError("That page is in the trash.")
        return row

    def prepare(self, name: str, args: dict[str, Any]) -> tuple[str, str]:
        """Check a change and describe it (summary, a Markdown preview) without making it."""
        from ...pages import validate_blocks
        allowed = set(_SPECS[name][2]["properties"])
        _only(args, allowed, name)
        missing = [r for r in _SPECS[name][2]["required"] if args.get(r) is None]
        if missing:
            raise CommandError(f"{name} needs {', '.join(missing)}.")
        if name == "create_page":
            title = _text_arg(args, "title", 200)
            if args.get("parentId") is not None:
                self._page({"pageId": args["parentId"]})
            content = args.get("content")
            if content:
                validate_blocks(pagetext.blocks_from_markdown(content, self.lookup))
            return f"Create the page “{title}”", pagetext.label_mentions(str(content or ""), self.lookup)[:4000]
        if name == "append_to_page":
            row = self._page(args)
            new = validate_blocks(pagetext.blocks_from_markdown(args.get("content"), self.lookup))
            after = args.get("afterBlockId")
            if after is not None and after not in {b["id"] for b in json.loads(row["blocks"])}:
                raise CommandError("afterBlockId is not a block on that page; read the page for block ids.")
            return f"Add {len(new)} block{'s' if len(new) != 1 else ''} to “{row['title']}”", pagetext.label_mentions(str(args["content"]), self.lookup)[:4000]
        if name == "update_block":
            row = self._page(args)
            block = next((b for b in json.loads(row["blocks"]) if b["id"] == args.get("blockId")), None)
            if block is None:
                raise CommandError("That block is not on the page; read the page for block ids.")
            if args.get("content") is None and args.get("checked") is None:
                raise CommandError("Send content, checked, or both.")
            kind = block["type"]
            if args.get("content") is not None:
                if "text" not in block:
                    raise CommandError("Only text blocks can be rewritten; add new blocks instead.")
                blocks = pagetext.blocks_from_markdown(args["content"], self.lookup)
                if len(blocks) != 1 or "text" not in blocks[0]:
                    raise CommandError("content is one line of text.")
                validate_blocks(blocks)
                kind = blocks[0]["type"]
            if args.get("checked") is not None and (not isinstance(args["checked"], bool) or kind != "todo"):
                raise CommandError("checked is true or false, and only for a to-do.")
            preview = args["content"] if args.get("content") is not None else ("Tick" if args.get("checked") else "Untick") + " the to-do"
            return f"Edit a line in “{row['title']}”", pagetext.label_mentions(str(preview), self.lookup)[:1000]
        if name == "rename_page":
            row = self._page(args)
            if args.get("title") is None and args.get("icon") is None:
                raise CommandError("Send title, icon, or both.")
            title = _text_arg(args, "title", 200) if args.get("title") is not None else row["title"]
            if args.get("icon") is not None:
                from ...pages import ICON
                if not isinstance(args["icon"], str) or not ICON.match(args["icon"]):
                    raise CommandError("icon is one emoji.")
            return f"Rename “{row['title']}” to “{title}”", ""
        if name in ("add_card", "update_card"):
            row = self._page(args)
            boards = [b for b in json.loads(row["blocks"]) if b["type"] == "board"]
            if name == "add_card":
                if args.get("boardId") is not None and not any(b["id"] == args["boardId"] for b in boards):
                    raise CommandError("boardId is not a plan board on that page.")
                title = _text_arg(args, "title", 300)
            else:
                if not any(i["id"] == args.get("cardId") for b in boards for i in b["items"]):
                    raise CommandError("That card is not on the page; read the page for card ids.")
                title = _text_arg(args, "title", 300, required=False)
            if args.get("status") is not None and args["status"] not in ("todo", "doing", "done"):
                raise CommandError("status is todo, doing or done.")
            _text_arg(args, "note", 2000, required=False)
            self._due(args.get("due"))
            self._links(args.get("links"))
            verb = "Add the card" if name == "add_card" else "Update the card"
            return f"{verb} “{title or 'card'}” on “{row['title']}”", ""
        if name == "create_task":
            goal = _text_arg(args, "goal", 1800)
            title = _text_arg(args, "title", 80, required=False)
            device = args.get("deviceId")
            if device is not None and self.lookup("device", device) is None:
                raise CommandError("There is no phone with that id; use list_phones.")
            if args.get("accountId") is not None and self.lookup("account", args["accountId"]) is None:
                raise CommandError("There is no account with that id; use list_accounts.")
            where = f" on {self.lookup('device', device)}" if device else " on any ready phone"
            return f"Start a task{where}: “{title or goal[:80]}”", goal
        if name == "create_routine":
            title, goal = _text_arg(args, "title", 80), _text_arg(args, "goal", 1800)
            spec = schedules.parse(args.get("schedule"))
            for device in args.get("deviceIds") or []:
                if not isinstance(device, str) or self.lookup("device", device) is None:
                    raise CommandError("deviceIds lists phones by id; use list_phones.")
            if args.get("accountId") is not None and self.lookup("account", args["accountId"]) is None:
                raise CommandError("There is no account with that id; use list_accounts.")
            return f"Create the routine “{title}” ({schedules.describe(spec)})", goal
        if name in ("create_row", "update_row"):
            table = self._c.tables.get(args["tableId"]) if isinstance(args.get("tableId"), str) else None
            if table is None:
                raise CommandError("No such table; use list_tables.")
            cells = self._agent_cells(table, args.get("cells"))
            if name == "update_row":
                self._c.tables.get_row(table["id"], args.get("rowId"))
            names = {p["id"]: p["name"] for p in table["properties"]}
            what = ", ".join(names[k] for k in cells)
            return (f"Add a row to “{table['title']}”" if name == "create_row" else f"Change {what} in “{table['title']}”"), what
        if name == "press_button":
            table = self._c.tables.get(args["tableId"]) if isinstance(args.get("tableId"), str) else None
            if table is None:
                raise CommandError("No such table; use list_tables.")
            button = self._button(table, args.get("button"))
            self._c.tables.get_row(table["id"], args.get("rowId"))
            return f"Press “{button['config']['label']}” in “{table['title']}”", ""
        if name in ("run_routine", "pause_routine"):
            routine = self._c.get_routine(args.get("routineId")) if isinstance(args.get("routineId"), str) else None
            if routine is None:
                raise CommandError("No such routine; use list_routines.")
            if name == "run_routine":
                return f"Run “{routine['title']}” now", ""
            if not isinstance(args.get("paused"), bool):
                raise CommandError("paused is true or false.")
            return f"{'Pause' if args['paused'] else 'Resume'} “{routine['title']}”", ""
        raise CommandError(f"There is no tool {name}.")

    def _save_blocks(self, row: Any, blocks: list[dict[str, Any]], actor: str) -> dict[str, Any]:
        return self._c.pages.update(row["id"], {"version": row["version"], "blocks": blocks}, actor=actor)

    def execute(self, name: str, args: dict[str, Any], *, actor: str) -> dict[str, Any]:
        """Make one change (the caller holds the lock). Returns what the model and Glass need to know."""
        self.prepare(name, args)
        pages = self._c.pages
        if name == "create_page":
            body: dict[str, Any] = {"title": _text_arg(args, "title", 200)}
            if args.get("icon") is not None:
                body["icon"] = args["icon"]
            if args.get("parentId") is not None:
                body["parentId"] = args["parentId"]
            body["blocks"] = pagetext.blocks_from_markdown(args["content"], self.lookup) if args.get("content") else []
            page = pages.create(body, actor=actor)
            return {"pageId": page["id"], "title": page["title"]}
        row = self._page(args) if "pageId" in args else None
        if name == "append_to_page":
            blocks = json.loads(row["blocks"])
            new = pagetext.blocks_from_markdown(args["content"], self.lookup)
            after = args.get("afterBlockId")
            at = next((i + 1 for i, b in enumerate(blocks) if b["id"] == after), len(blocks)) if after else len(blocks)
            page = self._save_blocks(row, blocks[:at] + new + blocks[at:], actor)
            return {"pageId": page["id"], "added": len(new)}
        if name == "update_block":
            blocks = json.loads(row["blocks"])
            for block in blocks:
                if block["id"] == args["blockId"]:
                    if args.get("content") is not None:
                        new = pagetext.blocks_from_markdown(args["content"], self.lookup)[0]
                        block.clear()
                        block.update({**new, "id": args["blockId"]})
                    if args.get("checked") is not None:
                        block["checked"] = args["checked"]
            page = self._save_blocks(row, blocks, actor)
            return {"pageId": page["id"], "blockId": args["blockId"]}
        if name == "rename_page":
            body = {"version": row["version"]}
            if args.get("title") is not None:
                body["title"] = _text_arg(args, "title", 200)
            if args.get("icon") is not None:
                body["icon"] = args["icon"]
            page = pages.update(row["id"], body, actor=actor)
            return {"pageId": page["id"], "title": page["title"]}
        if name in ("add_card", "update_card"):
            blocks = json.loads(row["blocks"])
            fields: dict[str, Any] = {}
            if args.get("title") is not None:
                fields["title"] = _text_arg(args, "title", 300)
            if args.get("status") is not None:
                fields["status"] = args["status"]
            if "due" in args:
                fields["due"] = self._due(args.get("due"))
            if args.get("note") is not None:
                fields["note"] = _text_arg(args, "note", 2000, required=False)
            if args.get("links") is not None:
                fields["refs"] = self._links(args["links"])
            if name == "add_card":
                board = next((b for b in blocks if b["type"] == "board" and (args.get("boardId") in (None, b["id"]))), None)
                if board is None:
                    board = {"type": "board", "title": "Plan", "layout": "board", "items": []}
                    blocks.append(board)
                card = {"title": fields.get("title", ""), "status": fields.get("status", "todo"), "due": fields.get("due"),
                        "note": fields.get("note", ""), "refs": fields.get("refs", [])}
                board["items"].append(card)
                page = self._save_blocks(row, blocks, actor)
                saved = next(b for b in page["blocks"] if b["type"] == "board" and (board.get("id") in (None, b["id"])))
                return {"pageId": page["id"], "boardId": saved["id"], "cardId": saved["items"][-1]["id"]}
            for block in blocks:
                for item in block.get("items") or []:
                    if item["id"] == args["cardId"]:
                        item.update(fields)
            page = self._save_blocks(row, blocks, actor)
            return {"pageId": page["id"], "cardId": args["cardId"]}
        if name == "create_task":
            body = {"goal": _text_arg(args, "goal", 1800)}
            for key in ("title", "deviceId", "accountId"):
                if args.get(key) is not None:
                    body[key] = args[key]
            task = self._c.create_task(body, actor=actor)
            return {"taskId": task["id"], "status": task["status"]}
        if name == "create_routine":
            body = {"title": args["title"], "goal": args["goal"], "schedule": args["schedule"], "deviceIds": args.get("deviceIds") or []}
            if args.get("accountId") is not None:
                body["accountId"] = args["accountId"]
            routine = self._c.create_routine(body, actor=actor)
            return {"routineId": routine["id"], "nextRun": self._iso(routine["nextRunAt"])}
        if name == "run_routine":
            created = self._c.run_routine_now(args["routineId"])
            return {"routineId": args["routineId"], "tasks": [t["id"] for t in (created.get("tasks") or [])] if isinstance(created, dict) else []}
        if name == "pause_routine":
            routine = self._c.update_routine(args["routineId"], {"paused": args["paused"]})
            return {"routineId": routine["id"], "paused": routine["paused"]}
        if name == "create_row":
            table = self._c.tables.get(args["tableId"])
            row = self._c.tables.create_row(table["id"], {"cells": self._agent_cells(table, args["cells"])}, actor=actor)
            return {"rowId": row["id"]}
        if name == "update_row":
            table = self._c.tables.get(args["tableId"])
            row = self._c.tables.update_row(table["id"], args["rowId"], {"cells": self._agent_cells(table, args["cells"])}, actor=actor)
            return {"rowId": row["id"]}
        if name == "press_button":
            table = self._c.tables.get(args["tableId"])
            button = self._button(table, args["button"])
            pressed = self._c.buttons.press(table["id"], args["rowId"], button["id"], actor=actor)
            return {"state": pressed["state"], "tasks": pressed["tasks"]}
        raise CommandError(f"There is no tool {name}.")

    def _button(self, table: dict[str, Any], name: Any) -> dict[str, Any]:
        button = next((p for p in table["properties"] if p["type"] == "button" and (p["id"] == name or str(name).lower() in
                                                                                  (p["name"].lower(), p["config"]["label"].lower()))), None)
        if button is None:
            raise CommandError("That table has no such button; use list_tables.")
        return button

    def _agent_cells(self, table: dict[str, Any], cells: Any) -> dict[str, Any]:
        """{Property name: value} from a model -> {property id: cell}. Choices by name, dates as YYYY-MM-DD. Personal
        columns are the owner's to fill."""
        from ...tables import COMPUTED
        if not isinstance(cells, dict) or not cells or len(cells) > 30:
            raise CommandError("cells is {Property name: value}, 1..30 of them.")
        by_name = {p["name"].lower(): p for p in table["properties"]}
        out: dict[str, Any] = {}
        for name, value in cells.items():
            prop = by_name.get(str(name).lower())
            if prop is None:
                raise CommandError(f"“{table['title']}” has no property “{name}”; use list_tables.")
            if prop["type"] in COMPUTED:
                raise CommandError(f"{prop['name']} is filled in by Cyclone.")
            if prop["config"].get("personal"):
                raise CommandError(f"{prop['name']} is personal; the owner fills it.")
            if isinstance(value, str):
                _text_arg({"v": value}, "v", 2000, required=False)
            if prop["type"] in ("select", "status") and isinstance(value, str):
                value = next((o["id"] for o in prop["config"].get("options", []) if o["name"].lower() == value.lower()), value)
            elif prop["type"] == "multi_select" and isinstance(value, list):
                ids = {o["name"].lower(): o["id"] for o in prop["config"].get("options", [])}
                value = [ids.get(str(v).lower(), v) for v in value]
            elif prop["type"] == "date" and isinstance(value, str):
                value = {"start": value}
            elif prop["type"] == "relation" and isinstance(value, str):
                value = [value]
            out[prop["id"]] = value
        return out


REGISTRY.toolset("workspace", WorkspaceTools)
for _name, (_kind, _description, _parameters) in _SPECS.items():
    REGISTRY.register(_name, _kind, _description, _parameters, toolset="workspace")
