"""Where the Manager keeps things (plan 53 R1, moved unchanged from ``command/ai.py``): its settings, the write-only
OpenRouter key, spending, OpenRouter's model list, conversations, messages and proposals, all in the Command Center's
SQLite file.

The key is write-only: it is saved in the per-user grant store (DPAPI on Windows) and never returned, logged, audited
or shown to a model. Glass can save it, test it and forget it, nothing else.
"""
from __future__ import annotations

import json
import threading
from typing import TYPE_CHECKING, Any, Callable

from .. import mcp
from .. import openapi
from .. import schedule as schedules
from ..center import CommandError
from .common import (AUTONOMY, BASE, CATALOGUE_TTL_MS, CONVERSATION_ID, DEFAULTS, GRANT, KEY, MAX_CONVERSATIONS,
                     MAX_INSTRUCTIONS, MODEL_ID, PROPOSAL_ID, PROVIDER, AiError, _money, _new, _only, _price, _text_arg)
from .events import EventHub
from .registry import REGISTRY

if TYPE_CHECKING:  # pragma: no cover
    from ..center import CommandCenter

AI_SCHEMA = """
CREATE TABLE IF NOT EXISTS ai_setting (name TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS ai_conversation (
  id TEXT PRIMARY KEY, title TEXT NOT NULL, page_id TEXT, model TEXT, state TEXT NOT NULL, detail TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS ai_message (
  conversation_id TEXT NOT NULL, seq INTEGER NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, at INTEGER NOT NULL,
  PRIMARY KEY(conversation_id, seq));
CREATE TABLE IF NOT EXISTS ai_proposal (
  id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, tool TEXT NOT NULL, arguments TEXT NOT NULL, summary TEXT NOT NULL,
  preview TEXT NOT NULL, state TEXT NOT NULL, result TEXT, created_at INTEGER NOT NULL, decided_at INTEGER);
CREATE TABLE IF NOT EXISTS ai_usage (
  at INTEGER NOT NULL, day TEXT NOT NULL, month TEXT NOT NULL, conversation_id TEXT, model TEXT NOT NULL,
  prompt_tokens INTEGER NOT NULL, completion_tokens INTEGER NOT NULL, cost REAL NOT NULL);
CREATE INDEX IF NOT EXISTS ai_usage_day ON ai_usage(day);
CREATE INDEX IF NOT EXISTS ai_proposal_conversation ON ai_proposal(conversation_id);
"""


class StoreMixin:
    """Settings, key, spending, models, conversations and proposals. ``AiStore`` mixes this with the loop."""

    def __init__(self, center: "CommandCenter", *, send: Callable[..., mcp.Response] | None = None,
                 spawn: Callable[[Callable[[], None]], None] | None = None,
                 stream: Callable[..., Any] | None = None) -> None:
        self._c = center
        self._send = send or openapi.request
        # Plan 53 R2: answers stream from the provider. A caller that brings its own ``send`` (tests, scripted
        # providers) and no ``stream`` gets whole answers, published as one piece.
        self._stream = stream if stream is not None else (None if send is not None else openapi.stream)
        self._spawn = spawn or (lambda fn: threading.Thread(target=fn, name="cyclone-ai-turn", daemon=True).start())
        self._stops: set[str] = set()
        self._catalogue: tuple[int, list[dict[str, Any]]] | None = None
        self._catalogue_lock = threading.Lock()
        self._toolsets = REGISTRY.bind(center)
        self.events = EventHub(center._clock)
        with center._lock:
            center._db.executescript(AI_SCHEMA)
            center._db.execute("UPDATE ai_conversation SET state = 'failed', detail = 'Cyclone restarted while this ran.' WHERE state = 'working'")
            # A message that waited for a turn when Cyclone stopped becomes an ordinary (unanswered) owner message.
            center._db.execute("UPDATE ai_message SET role = 'user' WHERE role = 'queued'")

    @property
    def _grants(self) -> Any:
        return self._c.connections.grants

    # ------------------------------------------------------------------ settings, key and spending

    def _setting(self, name: str) -> Any:
        row = self._c._db.execute("SELECT value FROM ai_setting WHERE name = ?", (name,)).fetchone()
        return json.loads(row["value"]) if row else DEFAULTS[name]

    def settings(self) -> dict[str, Any]:
        with self._c._lock:
            return {name: self._setting(name) for name in DEFAULTS}

    def status(self) -> dict[str, Any]:
        with self._c._lock:
            grant = self._grants.get(GRANT)
            day, month = self._today()
            spent_day = self._c._db.execute("SELECT COALESCE(SUM(cost), 0) FROM ai_usage WHERE day = ?", (day,)).fetchone()[0]
            spent_month = self._c._db.execute("SELECT COALESCE(SUM(cost), 0) FROM ai_usage WHERE month = ?", (month,)).fetchone()[0]
            calls = self._c._db.execute("SELECT COUNT(*) FROM ai_usage WHERE day = ?", (day,)).fetchone()[0]
            return {"provider": PROVIDER, "keySaved": bool(grant), "keySavedAt": (grant or {}).get("savedAt"),
                    "keyKept": self._grants.persistent, **self.settings(),
                    "spentTodayUsd": round(float(spent_day), 4), "spentMonthUsd": round(float(spent_month), 4), "callsToday": calls,
                    "tools": [{"name": t.name, "kind": t.kind, "description": t.description} for t in REGISTRY.tools()]}

    def update_settings(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send the settings to change.")
        _only(body, set(DEFAULTS), "AI settings")
        values: dict[str, Any] = {}
        if "model" in body:
            values["model"] = None if body["model"] is None else self._check_model(body["model"])
        if "dailyCapUsd" in body:
            values["dailyCapUsd"] = _money(body["dailyCapUsd"], "The daily limit", 0.05, 500)
        if "monthlyCapUsd" in body:
            values["monthlyCapUsd"] = _money(body["monthlyCapUsd"], "The monthly limit", 0.5, 5000)
        if "privateOnly" in body:
            if not isinstance(body["privateOnly"], bool):
                raise CommandError("privateOnly is true or false.")
            values["privateOnly"] = body["privateOnly"]
        if "autonomy" in body:
            if body["autonomy"] not in AUTONOMY:
                raise CommandError("autonomy is propose (every edit asks) or workspace (pages and cards are edited directly).")
            values["autonomy"] = body["autonomy"]
        if "instructions" in body:
            values["instructions"] = _text_arg(body, "instructions", MAX_INSTRUCTIONS, required=False)
        with self._c._lock:
            for name, value in values.items():
                self._c._db.execute("INSERT INTO ai_setting(name, value) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET value = excluded.value",
                                    (name, json.dumps(value)))
            if values:
                self._c._audit("owner", "ai.settings", "ai", {"changed": sorted(values)})
        return self.status()

    def set_key(self, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict) or set(body) != {"key"}:
            raise CommandError("Send {key}.")
        key = body["key"].strip() if isinstance(body["key"], str) else ""
        if not KEY.match(key):
            raise CommandError("That does not look like an OpenRouter key. Copy it again from OpenRouter's Keys page.")
        check = self._check_key(key)
        self._grants.put(GRANT, {"key": key, "savedAt": self._c._clock()})
        with self._c._lock:
            self._c._audit("owner", "ai.key.save", "ai")
        return {**self.status(), "check": check}

    def forget_key(self) -> dict[str, Any]:
        self._grants.drop(GRANT)
        with self._c._lock:
            self._c._audit("owner", "ai.key.forget", "ai")
        return self.status()

    def test_key(self) -> dict[str, Any]:
        return {**self.status(), "check": self._check_key(self._key())}

    def _key(self) -> str:
        grant = self._grants.get(GRANT)
        if not grant or not grant.get("key"):
            raise AiError("Add your OpenRouter key in AI settings first.")
        return str(grant["key"])

    def _headers(self, key: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {key}", "Content-Type": "application/json", "Accept": "application/json", "X-Title": "Cyclone"}

    def _check_key(self, key: str) -> dict[str, Any]:
        """Ask OpenRouter about the key. Only its credit numbers come back; its label may echo part of the key."""
        try:
            response = self._send("GET", f"{BASE}/key", headers=self._headers(key), timeout=20.0)
        except mcp.McpError as exc:
            raise CommandError("OpenRouter could not be reached. Check this PC's internet connection and try again.") from exc
        if response.status in (401, 403):
            raise CommandError("OpenRouter refused this key. Copy it again from OpenRouter's Keys page.")
        if response.status >= 400:
            raise CommandError(f"OpenRouter answered {response.status}. Try again in a minute.")
        try:
            data = json.loads(response.body.decode("utf-8")).get("data") or {}
        except (ValueError, AttributeError):
            data = {}
        out: dict[str, Any] = {"ok": True}
        for source, target in (("usage", "usedUsd"), ("limit", "limitUsd"), ("limit_remaining", "remainingUsd")):
            if isinstance(data.get(source), (int, float)) and not isinstance(data.get(source), bool):
                out[target] = round(float(data[source]), 4)
        if isinstance(data.get("is_free_tier"), bool):
            out["freeTier"] = data["is_free_tier"]
        return out

    def _today(self) -> tuple[str, str]:
        now = self._c._local_now()
        return now.strftime("%Y-%m-%d"), now.strftime("%Y-%m")

    def _budget_left(self) -> None:
        day, month = self._today()
        with self._c._lock:
            daily, monthly = self._setting("dailyCapUsd"), self._setting("monthlyCapUsd")
            spent_day = self._c._db.execute("SELECT COALESCE(SUM(cost), 0) FROM ai_usage WHERE day = ?", (day,)).fetchone()[0]
            spent_month = self._c._db.execute("SELECT COALESCE(SUM(cost), 0) FROM ai_usage WHERE month = ?", (month,)).fetchone()[0]
        if spent_day >= daily:
            raise AiError(f"Today's AI limit (${daily:.2f}) is reached. Raise it in AI settings, or continue tomorrow.")
        if spent_month >= monthly:
            raise AiError(f"This month's AI limit (${monthly:.2f}) is reached. Raise it in AI settings.")

    # ------------------------------------------------------------------ models

    def models(self, *, everything: bool = False, refresh: bool = False) -> dict[str, Any]:
        catalogue = self._models(refresh=refresh)
        shown = catalogue if everything else [m for m in catalogue if m["tools"]]
        return {"models": shown, "total": len(catalogue), "fetchedAt": self._catalogue[0] if self._catalogue else None}

    def _models(self, *, refresh: bool = False) -> list[dict[str, Any]]:
        with self._catalogue_lock:
            now = self._c._clock()
            if self._catalogue and not refresh and now - self._catalogue[0] < CATALOGUE_TTL_MS:
                return self._catalogue[1]
            grant = self._grants.get(GRANT)
            headers = self._headers(str(grant["key"])) if grant and grant.get("key") else {"Accept": "application/json", "X-Title": "Cyclone"}
            try:
                response = self._send("GET", f"{BASE}/models", headers=headers, timeout=30.0)
            except mcp.McpError as exc:
                if self._catalogue:
                    return self._catalogue[1]
                raise CommandError("OpenRouter's model list could not be loaded. Check this PC's internet connection.") from exc
            if response.status >= 400:
                if self._catalogue:
                    return self._catalogue[1]
                raise CommandError(f"OpenRouter's model list could not be loaded ({response.status}).")
            try:
                raw = json.loads(response.body.decode("utf-8")).get("data")
            except (ValueError, AttributeError):
                raw = None
            if not isinstance(raw, list):
                raise CommandError("OpenRouter's model list was not readable.")
            models = []
            for item in raw[:5000]:
                if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not MODEL_ID.match(item["id"]):
                    continue
                pricing = item.get("pricing") if isinstance(item.get("pricing"), dict) else {}
                params = item.get("supported_parameters") if isinstance(item.get("supported_parameters"), list) else []
                prompt, completion = _price(pricing.get("prompt")), _price(pricing.get("completion"))
                context = item.get("context_length")
                models.append({
                    "id": item["id"], "name": openapi.hide_secrets(str(item.get("name") or item["id"]))[:120],
                    "contextLength": context if isinstance(context, int) and not isinstance(context, bool) else None,
                    "promptPerM": prompt, "completionPerM": completion, "tools": "tools" in params,
                    "free": prompt == 0 and completion == 0,
                })
            models.sort(key=lambda m: m["name"].lower())
            self._catalogue = (now, models)
            return models

    def _check_model(self, model: Any) -> str:
        if not isinstance(model, str) or not MODEL_ID.match(model):
            raise CommandError("Pick a model from the list.")
        known = {m["id"]: m for m in self._models()}
        if model not in known:
            raise CommandError("That model is not in OpenRouter's list any more. Pick another.")
        if not known[model]["tools"]:
            raise CommandError("That model cannot use tools, so it cannot plan or edit. Pick one marked \"uses tools\".")
        return model

    def _price_of(self, model: str) -> tuple[float | None, float | None]:
        for m in (self._catalogue[1] if self._catalogue else []):
            if m["id"] == model:
                return m["promptPerM"], m["completionPerM"]
        return None, None

    # ------------------------------------------------------------------ conversations

    def conversations(self) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM ai_conversation ORDER BY updated_at DESC LIMIT 200").fetchall()
            return [self._conversation_meta(r) for r in rows]

    def _conversation_meta(self, r: Any) -> dict[str, Any]:
        open_count = self._c._db.execute("SELECT COUNT(*) FROM ai_proposal WHERE conversation_id = ? AND state = 'open'", (r["id"],)).fetchone()[0]
        return {"id": r["id"], "title": r["title"], "pageId": r["page_id"], "model": r["model"], "state": r["state"],
                "detail": r["detail"], "createdAt": r["created_at"], "updatedAt": r["updated_at"], "openProposals": open_count}

    def _row(self, conversation_id: Any) -> Any:
        if not isinstance(conversation_id, str) or not CONVERSATION_ID.match(conversation_id):
            raise CommandError("No such conversation.")
        row = self._c._db.execute("SELECT * FROM ai_conversation WHERE id = ?", (conversation_id,)).fetchone()
        if row is None:
            raise CommandError("No such conversation.")
        return row

    def create_conversation(self, body: Any) -> dict[str, Any]:
        body = body if isinstance(body, dict) else {}
        _only(body, {"pageId", "model"}, "conversation")
        model = self._check_model(body["model"]) if body.get("model") is not None else None
        with self._c._lock:
            page_id = body.get("pageId")
            if page_id is not None:
                self._c.pages._row(page_id)
            if self._c._db.execute("SELECT COUNT(*) FROM ai_conversation").fetchone()[0] >= MAX_CONVERSATIONS:
                oldest = self._c._db.execute("SELECT id FROM ai_conversation WHERE state != 'working' ORDER BY updated_at LIMIT 1").fetchone()
                if oldest:
                    self._delete(oldest["id"])
            now = self._c._clock()
            cid = _new("ai")
            self._c._db.execute("INSERT INTO ai_conversation(id, title, page_id, model, state, detail, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                                (cid, "New conversation", page_id, model, "idle", "", now, now))
            return self.get(cid)

    def get(self, conversation_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(conversation_id)
            messages = self._c._db.execute("SELECT * FROM ai_message WHERE conversation_id = ? ORDER BY seq", (row["id"],)).fetchall()
            proposals = self._c._db.execute("SELECT * FROM ai_proposal WHERE conversation_id = ? ORDER BY created_at", (row["id"],)).fetchall()
            cost = self._c._db.execute("SELECT COALESCE(SUM(cost), 0) FROM ai_usage WHERE conversation_id = ?", (row["id"],)).fetchone()[0]
            return {**self._conversation_meta(row), "costUsd": round(float(cost), 4),
                    "messages": self._public_messages(messages), "proposals": [self._proposal_public(p) for p in proposals]}

    def update_conversation(self, conversation_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {model?, title?}.")
        _only(body, {"model", "title"}, "conversation")
        model = self._check_model(body["model"]) if body.get("model") is not None else None
        with self._c._lock:
            row = self._row(conversation_id)
            if "model" in body:
                self._c._db.execute("UPDATE ai_conversation SET model = ? WHERE id = ?", (model, row["id"]))
            if "title" in body:
                self._c._db.execute("UPDATE ai_conversation SET title = ? WHERE id = ?", (_text_arg(body, "title", 80), row["id"]))
        return self.get(conversation_id)

    def delete_conversation(self, conversation_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(conversation_id)
            if row["state"] == "working":
                raise CommandError("Stop the answer first.")
            self._delete(row["id"])
            return {"id": row["id"], "deleted": True}

    def _delete(self, cid: str) -> None:
        for table in ("ai_message", "ai_proposal"):
            self._c._db.execute(f"DELETE FROM {table} WHERE conversation_id = ?", (cid,))
        self._c._db.execute("UPDATE ai_usage SET conversation_id = NULL WHERE conversation_id = ?", (cid,))
        self._c._db.execute("DELETE FROM ai_conversation WHERE id = ?", (cid,))

    def _add(self, cid: str, role: str, body: dict[str, Any]) -> int:
        seq = (self._c._db.execute("SELECT MAX(seq) FROM ai_message WHERE conversation_id = ?", (cid,)).fetchone()[0] or 0) + 1
        now = self._c._clock()
        self._c._db.execute("INSERT INTO ai_message(conversation_id, seq, role, body, at) VALUES (?,?,?,?,?)",
                            (cid, seq, role, json.dumps(body, ensure_ascii=False), now))
        self._c._db.execute("UPDATE ai_conversation SET updated_at = ? WHERE id = ?", (now, cid))
        self.events.publish("message.added", cid, messageSeq=seq, role=role)
        return seq

    def _set_state(self, cid: str, state: str, detail: str = "") -> None:
        self._c._db.execute("UPDATE ai_conversation SET state = ?, detail = ?, updated_at = ? WHERE id = ?",
                            (state, detail[:300], self._c._clock(), cid))
        self.events.publish("state", cid, state=state, detail=detail[:300])

    # --- the queue (plan 53 R2): a message sent while Cyber answers waits for the next turn

    def _queued(self, cid: str) -> list[Any]:
        return self._c._db.execute("SELECT seq, body FROM ai_message WHERE conversation_id = ? AND role = 'queued' ORDER BY seq",
                                   (cid,)).fetchall()

    def _promote_queued(self, cid: str) -> int:
        """Waiting messages become owner messages at the end of the conversation, in the order they were sent, so the
        conversation stays a clean sequence of turns (never an owner message between a tool call and its result)."""
        rows = self._queued(cid)
        for r in rows:
            self._c._db.execute("DELETE FROM ai_message WHERE conversation_id = ? AND seq = ?", (cid, r["seq"]))
            self._add(cid, "user", json.loads(r["body"]))
        return len(rows)

    def _drop_queued(self, cid: str) -> int:
        count = self._c._db.execute("DELETE FROM ai_message WHERE conversation_id = ? AND role = 'queued'", (cid,)).rowcount
        if count:
            self.events.publish("message.added", cid, messageSeq=None, role="queued")
        return count

    # ------------------------------------------------------------------ tools, through the registry

    def _kind_of(self, name: str) -> str:
        tool = REGISTRY.get(name)
        return tool.kind if tool else "phone"

    def _label(self, name: str, args: dict[str, Any]) -> str:
        tool = REGISTRY.get(name)
        return self._toolsets[tool.toolset].label(name, args) if tool else f"Unknown tool {name[:40]}"

    def _lookup(self, kind: str, ident: str) -> str | None:
        return self._toolsets["workspace"].lookup(kind, ident)

    def _toolset_of(self, name: str) -> Any:
        tool = REGISTRY.get(name)
        if tool is None:
            raise CommandError(f"There is no tool {name}.")
        return self._toolsets[tool.toolset]

    def _read(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        return self._toolset_of(name).read(name, args)

    def _prepare(self, name: str, args: dict[str, Any]) -> tuple[str, str]:
        """Check a change and describe it (summary, a Markdown preview) without making it."""
        return self._toolset_of(name).prepare(name, args)

    def _execute(self, name: str, args: dict[str, Any], *, actor: str) -> dict[str, Any]:
        """Make one change (the caller holds the lock)."""
        return self._toolset_of(name).execute(name, args, actor=actor)

    # ------------------------------------------------------------------ proposals

    def _proposal_public(self, r: Any) -> dict[str, Any]:
        return {"id": r["id"], "conversationId": r["conversation_id"], "tool": r["tool"], "kind": self._kind_of(r["tool"]),
                "summary": r["summary"], "preview": r["preview"], "state": r["state"],
                "result": json.loads(r["result"]) if r["result"] else None, "createdAt": r["created_at"], "decidedAt": r["decided_at"]}

    def _proposal(self, proposal_id: Any) -> Any:
        if not isinstance(proposal_id, str) or not PROPOSAL_ID.match(proposal_id):
            raise CommandError("No such proposal.")
        row = self._c._db.execute("SELECT * FROM ai_proposal WHERE id = ?", (proposal_id,)).fetchone()
        if row is None:
            raise CommandError("No such proposal.")
        return row

    def apply(self, proposal_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._proposal(proposal_id)
            if row["state"] != "open":
                raise CommandError(f"This proposal was already {row['state']}.")
            try:
                result = self._execute(row["tool"], json.loads(row["arguments"]), actor="owner")
                state, note = "applied", f"The owner applied: {row['summary']}. Result: {json.dumps(result)}"
            except (CommandError, schedules.ScheduleError) as exc:
                result, state, note = {"error": str(exc)}, "failed", f"Applying “{row['summary']}” failed: {exc}"
            self._c._db.execute("UPDATE ai_proposal SET state = ?, result = ?, decided_at = ? WHERE id = ?",
                                (state, json.dumps(result), self._c._clock(), row["id"]))
            # For the model's next turn; the owner sees the outcome on the proposal's card.
            self._add(row["conversation_id"], "note", {"text": note, "quiet": True})
            self._c._audit("owner", "ai.proposal.apply", row["id"], {"tool": row["tool"], "state": state})
            public = self._proposal_public(self._proposal(row["id"]))
            self.events.publish("proposal.resolved", row["conversation_id"], proposal=public)
            return public

    def discard(self, proposal_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._proposal(proposal_id)
            if row["state"] != "open":
                raise CommandError(f"This proposal was already {row['state']}.")
            self._c._db.execute("UPDATE ai_proposal SET state = 'discarded', decided_at = ? WHERE id = ?", (self._c._clock(), row["id"]))
            self._add(row["conversation_id"], "note", {"text": f"The owner discarded: {row['summary']}.", "quiet": True})
            self._c._audit("owner", "ai.proposal.discard", row["id"], {"tool": row["tool"]})
            public = self._proposal_public(self._proposal(row["id"]))
            self.events.publish("proposal.resolved", row["conversation_id"], proposal=public)
            return public

    def open_proposals(self) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT * FROM ai_proposal WHERE state = 'open' ORDER BY created_at LIMIT 100").fetchall()
            return [self._proposal_public(r) for r in rows]

    # ------------------------------------------------------------------ what Glass shows

    def _public_messages(self, rows: list[Any]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        waiting: list[dict[str, Any]] = []
        for r in rows:
            body = json.loads(r["body"])
            if r["role"] == "queued":
                # Plan 53 R2: shown after the answer in progress, marked as waiting for the next turn.
                waiting.append({"seq": r["seq"], "role": "user", "text": body.get("text", ""), "at": r["at"], "queued": True})
                continue
            if r["role"] == "note" and body.get("quiet"):
                continue
            if r["role"] == "summary":
                # Plan 53 R2: older turns were summarized for the model; the owner still sees every message.
                continue
            if r["role"] == "tool":
                for item in reversed(out):
                    if item["role"] == "assistant":
                        item["activity"].append({"label": body.get("label", ""), "outcome": body.get("outcome", "done"),
                                                 "proposalId": body.get("proposalId"),
                                                 "error": body["result"].get("error") if isinstance(body.get("result"), dict) else None})
                        break
                continue
            item: dict[str, Any] = {"seq": r["seq"], "role": r["role"], "text": body.get("text", ""), "at": r["at"]}
            if r["role"] == "assistant":
                item.update(model=body.get("model"), costUsd=body.get("costUsd"), activity=[])
            out.append(item)
        # One owner-facing answer per turn: tool-only steps fold into the next answer.
        merged: list[dict[str, Any]] = []
        for item in out:
            prev = merged[-1] if merged else None
            if item["role"] == "assistant" and prev and prev["role"] == "assistant":
                prev["activity"].extend(item["activity"])
                prev["text"] = "\n\n".join(t for t in (prev["text"], item["text"]) if t)
                prev["costUsd"] = round((prev.get("costUsd") or 0) + (item.get("costUsd") or 0), 6)
                prev["model"] = item["model"] or prev["model"]
                continue
            merged.append(item)
        return merged + waiting
