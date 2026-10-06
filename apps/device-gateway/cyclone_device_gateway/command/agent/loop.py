"""One Manager turn (plan 53 R1, moved unchanged from ``command/ai.py``; the loop shape follows Hermes Agent, MIT).

The owner's message is stored, then a background turn asks the model, runs the tools it calls and asks again, until
the model answers without tools, the owner stops it, a budget is reached or ``MAX_STEPS`` pass. Read tools run at
once; changes become proposals unless the owner lets the AI edit the workspace. What is kept is what the model saw
and did (messages, tool calls, results, cost), never hidden reasoning. Outside content reaches the model only as tool
results, which the system prompt calls information, never instructions.
"""
from __future__ import annotations

import json
from typing import Any

from ...desktop_runtime.v5_contract import INLINE_SECRET
from .. import mcp
from .. import openapi
from .. import pagetext
from .. import schedule as schedules
from ..center import CommandError
from . import prompt
from .common import (BASE, MAX_ANSWER_TOKENS, MAX_CALLS_PER_STEP, MAX_CONTEXT_CHARS, MAX_OWNER_TEXT, MAX_STEPS,
                     MAX_TOOL_RESULT, AiError, _new, _only)
from .registry import REGISTRY


class LoopMixin:
    """send, stop and the turn itself. Needs ``StoreMixin`` for storage, settings, the key and spending."""

    def send(self, conversation_id: str, body: Any) -> dict[str, Any]:
        if not isinstance(body, dict):
            raise CommandError("Send {text}.")
        _only(body, {"text"}, "message")
        text = body.get("text")
        if not isinstance(text, str) or not text.strip():
            raise CommandError("Write a message.")
        text = text.strip()
        if len(text) > MAX_OWNER_TEXT:
            raise CommandError(f"A message is at most {MAX_OWNER_TEXT} characters.")
        if INLINE_SECRET.search(text):
            raise CommandError("Leave passwords, codes and keys out of messages; the vault keeps them.")
        self._key()  # a clear message before anything is stored
        with self._c._lock:
            row = self._row(conversation_id)
            if row["state"] == "working":
                raise CommandError("The AI is still answering. Wait, or stop it.")
            model = row["model"] or self._setting("model")
            if not model:
                raise CommandError("Pick a model in AI settings first.")
            self._add(row["id"], "user", {"text": text})
            if row["title"] == "New conversation":
                self._c._db.execute("UPDATE ai_conversation SET title = ? WHERE id = ?", (text.replace("\n", " ")[:60], row["id"]))
            self._set_state(row["id"], "working", "Thinking…")
            self._stops.discard(row["id"])
        self._spawn(lambda: self._turn(conversation_id, model))
        return self.get(conversation_id)

    def stop(self, conversation_id: str) -> dict[str, Any]:
        with self._c._lock:
            row = self._row(conversation_id)
            if row["state"] == "working":
                self._stops.add(row["id"])
        return self.get(conversation_id)

    # ------------------------------------------------------------------ one turn

    def _turn(self, cid: str, model: str) -> None:
        try:
            for _ in range(MAX_STEPS):
                if cid in self._stops:
                    raise AiError("Stopped.")
                self._budget_left()
                messages = self._context(cid)
                with self._c._lock:
                    self._set_state(cid, "working", "Thinking…")
                answer = self._complete(cid, model, messages)
                with self._c._lock:
                    text = pagetext.label_mentions(answer["text"], self._lookup)
                    self._add(cid, "assistant", {"text": text, "calls": answer["calls"], "model": model, "costUsd": answer["cost"]})
                if not answer["calls"]:
                    break
                for index, call in enumerate(answer["calls"]):
                    if cid in self._stops:
                        result = {"error": "Stopped by the owner before this ran."}
                        outcome, label, proposal = "error", call["name"], None
                    elif index >= MAX_CALLS_PER_STEP:
                        result = {"error": f"At most {MAX_CALLS_PER_STEP} tool calls at a time."}
                        outcome, label, proposal = "error", call["name"], None
                    else:
                        with self._c._lock:
                            self._set_state(cid, "working", self._label(call["name"], call["arguments"] or {}))
                        result, outcome, label, proposal = self._call_tool(cid, call)
                    with self._c._lock:
                        self._add(cid, "tool", {"callId": call["id"], "name": call["name"], "label": label, "outcome": outcome,
                                                "proposalId": proposal, "result": result})
            else:
                raise AiError(f"Stopped after {MAX_STEPS} steps. Ask again to continue.")
            with self._c._lock:
                self._set_state(cid, "idle")
        except AiError as exc:
            with self._c._lock:
                self._add(cid, "note", {"text": str(exc)})
                self._set_state(cid, "idle" if str(exc) == "Stopped." else "failed", str(exc))
        except Exception:  # noqa: BLE001 - a turn must always end in a visible state
            with self._c._lock:
                self._add(cid, "note", {"text": "Something went wrong in Cyclone while answering. Try again."})
                self._set_state(cid, "failed", "Something went wrong in Cyclone while answering.")
        finally:
            self._stops.discard(cid)

    def _system(self, cid: str) -> str:
        with self._c._lock:
            row = self._row(cid)
            autonomy, instructions = self._setting("autonomy"), self._setting("instructions")
            page = None
            if row["page_id"]:
                try:
                    page = self._c.pages.get(row["page_id"])
                except CommandError:
                    page = None
        now = self._c._local_now().strftime("%A %Y-%m-%d %H:%M")
        return prompt.build(autonomy=autonomy, now=now, instructions=instructions, page=page)

    def _context(self, cid: str) -> list[dict[str, Any]]:
        with self._c._lock:
            rows = self._c._db.execute("SELECT role, body FROM ai_message WHERE conversation_id = ? ORDER BY seq", (cid,)).fetchall()
        items = [(r["role"], json.loads(r["body"])) for r in rows]
        out: list[dict[str, Any]] = []
        notes: list[str] = []
        index = 0
        while index < len(items):
            role, body = items[index]
            index += 1
            if role == "note":
                notes.append(body["text"])
            elif role == "user":
                prefix = f"(Cyclone, since your last answer: {' '.join(notes)})\n" if notes else ""
                notes = []
                out.append({"role": "user", "content": prefix + body["text"]})
            elif role == "assistant":
                message: dict[str, Any] = {"role": "assistant", "content": body.get("text") or ""}
                calls = body.get("calls") or []
                if calls:
                    message["tool_calls"] = [{"id": c["id"], "type": "function",
                                              "function": {"name": c["name"], "arguments": json.dumps(c["arguments"] if c["arguments"] is not None else {})}}
                                             for c in calls]
                out.append(message)
                answered: dict[str, dict[str, Any]] = {}
                while index < len(items) and items[index][0] == "tool":
                    answered[items[index][1]["callId"]] = items[index][1]
                    index += 1
                for c in calls:
                    result = answered.get(c["id"], {}).get("result", {"error": "This call did not run."})
                    content = json.dumps(result, ensure_ascii=False)
                    if len(content) > MAX_TOOL_RESULT:
                        content = content[:MAX_TOOL_RESULT] + " … (cut; ask for less)"
                    out.append({"role": "tool", "tool_call_id": c["id"], "content": content})
        # Keep the newest turns that fit, starting at an owner message.
        total, start = 0, len(out)
        for i in range(len(out) - 1, -1, -1):
            total += len(str(out[i].get("content") or "")) + len(json.dumps(out[i].get("tool_calls") or []))
            if total > MAX_CONTEXT_CHARS:
                break
            if out[i]["role"] == "user":
                start = i
        kept = out[start:] if start < len(out) else out[-1:]
        return [{"role": "system", "content": self._system(cid)}, *kept]

    def _complete(self, cid: str, model: str, messages: list[dict[str, Any]]) -> dict[str, Any]:
        key = self._key()
        body: dict[str, Any] = {
            "model": model, "messages": messages, "max_tokens": MAX_ANSWER_TOKENS, "tool_choice": "auto",
            "tools": REGISTRY.specs(),
            "usage": {"include": True},
        }
        if self.settings()["privateOnly"]:
            body["provider"] = {"data_collection": "deny"}
        try:
            response = self._send("POST", f"{BASE}/chat/completions", headers=self._headers(key),
                                  body=json.dumps(body, ensure_ascii=False).encode("utf-8"), timeout=180.0)
        except mcp.McpError as exc:
            raise AiError("OpenRouter could not be reached. Check this PC's internet connection and try again.") from exc
        try:
            data = json.loads(response.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            data = {}
        if not isinstance(data, dict):
            data = {}
        error = data.get("error") if isinstance(data.get("error"), dict) else None
        if response.status >= 400 or error:
            raise AiError(self._explain(response.status, error))
        choices = data.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise AiError("OpenRouter's answer was empty. Try again, or pick another model.")
        message = choices[0].get("message") if isinstance(choices[0].get("message"), dict) else {}
        content = message.get("content")
        if isinstance(content, list):
            content = "".join(str(p.get("text", "")) for p in content if isinstance(p, dict))
        text = openapi.hide_secrets(str(content or "")).strip()[:20_000]
        calls = []
        for raw in (message.get("tool_calls") or [])[:MAX_CALLS_PER_STEP + 4]:
            function = raw.get("function") if isinstance(raw, dict) and isinstance(raw.get("function"), dict) else {}
            name = function.get("name") if isinstance(function.get("name"), str) else ""
            try:
                arguments = json.loads(function.get("arguments") or "{}")
            except (TypeError, ValueError):
                arguments = None
            call_id = raw.get("id") if isinstance(raw, dict) and isinstance(raw.get("id"), str) and 0 < len(raw["id"]) <= 120 else _new("call")
            calls.append({"id": call_id, "name": name[:64], "arguments": arguments if isinstance(arguments, dict) else None})
        usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
        prompt_tokens = usage.get("prompt_tokens") if isinstance(usage.get("prompt_tokens"), int) else 0
        completion_tokens = usage.get("completion_tokens") if isinstance(usage.get("completion_tokens"), int) else 0
        cost = usage.get("cost")
        if not isinstance(cost, (int, float)) or isinstance(cost, bool) or cost < 0:
            prompt_price, completion_price = self._price_of(model)
            cost = ((prompt_tokens * (prompt_price or 0)) + (completion_tokens * (completion_price or 0))) / 1_000_000
        day, month = self._today()
        with self._c._lock:
            self._c._db.execute("INSERT INTO ai_usage(at, day, month, conversation_id, model, prompt_tokens, completion_tokens, cost) VALUES (?,?,?,?,?,?,?,?)",
                                (self._c._clock(), day, month, cid, model, prompt_tokens, completion_tokens, float(cost)))
        if not text and not calls:
            text = "(The model gave no answer. Try again, or pick another model.)"
        return {"text": text, "calls": calls, "cost": round(float(cost), 6)}

    def _explain(self, status: int, error: dict[str, Any] | None) -> str:
        detail = openapi.hide_secrets(str((error or {}).get("message") or ""))[:200]
        if status in (401, 403):
            return "OpenRouter refused the key. Save a new key in AI settings."
        if status == 402:
            return "Your OpenRouter account is out of credits. Add credits on OpenRouter, or pick a free model."
        if status == 429:
            return "OpenRouter is busy or rate-limiting this key. Try again in a minute."
        if status == 404 and self.settings()["privateOnly"]:
            return ("No provider for this model keeps your data private. Pick another model, or turn off "
                    "\"Private providers only\" in AI settings." + (f" ({detail})" if detail else ""))
        return f"OpenRouter could not answer ({status}){': ' + detail if detail else ''}."

    def _call_tool(self, cid: str, call: dict[str, Any]) -> tuple[dict[str, Any], str, str, str | None]:
        name, args = call["name"], call["arguments"]
        tool = REGISTRY.get(name)
        if tool is None:
            return {"error": f"There is no tool {name[:40]}."}, "error", f"Unknown tool {name[:40]}", None
        if args is None:
            return {"error": "The arguments were not valid JSON."}, "error", self._label(name, {}), None
        label = self._label(name, args)
        kind, schema, toolset = tool.kind, tool.parameters, self._toolsets[tool.toolset]
        try:
            _only(args, set(schema["properties"]), name)
            missing = [r for r in schema["required"] if args.get(r) is None]
            if missing:
                raise CommandError(f"{name} needs {', '.join(missing)}.")
            with self._c._lock:
                if kind == "read":
                    return toolset.read(name, args), "done", label, None
                summary, preview = toolset.prepare(name, args)
                if kind == "workspace" and self._setting("autonomy") == "workspace":
                    result = toolset.execute(name, args, actor="ai")
                    self._c._audit("ai", f"ai.{name}", cid)
                    return {"done": True, **result}, "done", label, None
                pid = _new("prp")
                self._c._db.execute("INSERT INTO ai_proposal(id, conversation_id, tool, arguments, summary, preview, state, created_at)"
                                    " VALUES (?,?,?,?,?,?,?,?)",
                                    (pid, cid, name, json.dumps(args, ensure_ascii=False), summary, preview, "open", self._c._clock()))
                self._c._audit("ai", "ai.propose", pid, {"tool": name, "conversation": cid})
                return {"proposed": pid, "summary": summary, "note": "Waiting for the owner to apply or discard it."}, "proposed", label, pid
        except CommandError as exc:
            return {"error": str(exc)}, "error", label, None
        except schedules.ScheduleError as exc:
            return {"error": str(exc)}, "error", label, None
