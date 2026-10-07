"""Cyber moves Glass (plan 53 R5): open a page, set a page's filter or search, highlight what it talks about.

These tools change nothing in Cyclone: they only move the owner's view, so they run at once without a proposal (kind
``ui``). Each call is published as a ``ui.action`` event; Glass carries it out if it is open and reports back only when
it could not (``POST /v1/cc/ai/ui-result``), which reaches the model as a quiet note on its next turn. Arguments are
checked against the pages and targets Glass publishes, so the model can never send Glass anywhere else.
"""
from __future__ import annotations

import re
from typing import TYPE_CHECKING, Any

from ...center import CommandError
from ..common import _schema
from ..registry import REGISTRY

if TYPE_CHECKING:  # pragma: no cover
    from ...center import CommandCenter

#: Pages Glass can open. Those marked True need an id (a run, an experiment, an app, a workspace page).
PAGES: dict[str, bool] = {
    "home": False, "devices": False, "apps": False, "runs": False, "lab": False, "market": False, "knowledge": False,
    "phone": False, "settings": False, "remote": False, "attach": False,
    "workspace": False, "approvals": False, "tasks": False, "routines": False, "results": False, "accounts": False,
    "numbers": False, "vault": False, "connections": False, "fleet": False, "cyber_settings": False,
    "run": True, "experiment": True, "app": True, "workspace_page": True,
}
#: Things Glass marks on its pages (data-mgr-target="kind:id").
TARGET_KINDS = ("run", "app", "phone", "experiment", "page", "task", "routine", "account")
TARGET = re.compile(r"^(%s):(?!.*\.\.)[A-Za-z0-9._:@/-]{1,160}$" % "|".join(TARGET_KINDS))
ID = re.compile(r"^(?!.*\.\.)[A-Za-z0-9._:@/-]{1,160}$")  # no ".." anywhere
FILTER = re.compile(r"^[a-z][a-z_-]{0,39}$")
MAX_SEARCH = 80

_SPECS: dict[str, tuple[str, str, dict[str, Any]]] = {
    "open_page": ("ui", "Open a page in the owner's Glass (it only changes what they see). Pages: " + ", ".join(PAGES)
                  + ". run, experiment, app and workspace_page need id.",
                  _schema({"page": {"type": "string", "enum": list(PAGES)}, "id": {"type": "string"}}, ["page"])),
    "set_filter": ("ui", "On the page the owner has open, press a filter tab (runs: all, failed, completed, stopped; apps: all, "
                         "ready, attention, partial, unmapped, web) and/or type into its search box.",
                   _schema({"filter": {"type": "string"}, "search": {"type": "string"}}, [])),
    "highlight": ("ui", "Scroll to and briefly ring one thing on the owner's Glass page, as kind:id "
                        f"({', '.join(TARGET_KINDS)}), e.g. run:9f2c or phone:dev_a. Open its page first if needed.",
                  _schema({"target": {"type": "string"}}, ["target"])),
    "open_run": ("ui", "Open one run's inspector in the owner's Glass.", _schema({"runId": {"type": "string"}}, ["runId"])),
}


class GlassUiTools:
    def __init__(self, center: "CommandCenter") -> None:
        self._c = center

    def label(self, name: str, args: dict[str, Any]) -> str:
        if name == "open_page":
            page = str(args.get("page") or "a page").replace("_", " ")
            return f"Opened {page} in Glass"
        if name == "set_filter":
            parts = [f"“{args['filter']}”" if args.get("filter") else "", f"search “{str(args.get('search'))[:30]}”" if args.get("search") else ""]
            return "Filtered the page: " + " and ".join(p for p in parts if p) if any(parts) else "Filtered the page"
        if name == "highlight":
            return f"Pointed at {str(args.get('target', ''))[:60]}"
        if name == "open_run":
            return f"Opened run {str(args.get('runId', ''))[:40]}"
        return f"Unknown tool {name[:40]}"

    def check(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        """The action Glass gets, after checking every argument against what Glass publishes."""
        if name == "open_page":
            page = args.get("page")
            if page not in PAGES:
                raise CommandError(f"page is one of: {', '.join(PAGES)}.")
            ident = args.get("id")
            if PAGES[page]:
                if not isinstance(ident, str) or not ID.match(ident):
                    raise CommandError(f"Opening {page} needs its id.")
                return {"action": "open_page", "page": page, "id": ident}
            if ident is not None:
                raise CommandError(f"{page} takes no id.")
            return {"action": "open_page", "page": page}
        if name == "set_filter":
            out: dict[str, Any] = {"action": "set_filter"}
            if args.get("filter") is not None:
                if not isinstance(args["filter"], str) or not FILTER.match(args["filter"]):
                    raise CommandError("filter is a tab name, like failed.")
                out["filter"] = args["filter"]
            if args.get("search") is not None:
                if not isinstance(args["search"], str) or len(args["search"]) > MAX_SEARCH:
                    raise CommandError(f"search is at most {MAX_SEARCH} characters.")
                out["search"] = args["search"].strip()
            if len(out) == 1:
                raise CommandError("Send filter, search, or both.")
            return out
        if name == "highlight":
            target = args.get("target")
            if not isinstance(target, str) or not TARGET.match(target):
                raise CommandError(f"target is kind:id, kind one of {', '.join(TARGET_KINDS)}.")
            return {"action": "highlight", "target": target}
        if name == "open_run":
            run = args.get("runId")
            if not isinstance(run, str) or not ID.match(run):
                raise CommandError("runId is a run id.")
            return {"action": "open_page", "page": "run", "id": run}
        raise CommandError(f"There is no tool {name}.")

    def show(self, cid: str, call_id: str, name: str, args: dict[str, Any]) -> dict[str, Any]:
        action = self.check(name, args)
        self._c.ai.events.publish("ui.action", cid, callId=call_id, **action)
        return {"shown": True, "note": "Glass shows it if the owner has Glass open, and says so if it could not."}

    # The registry's toolset shape; ui tools never read, prepare or execute workspace changes.
    def read(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        raise CommandError(f"{name} only moves Glass.")

    def prepare(self, name: str, args: dict[str, Any]) -> tuple[str, str]:
        raise CommandError(f"{name} only moves Glass.")

    def execute(self, name: str, args: dict[str, Any], *, actor: str) -> dict[str, Any]:
        raise CommandError(f"{name} only moves Glass.")


REGISTRY.toolset("glass_ui", GlassUiTools)
for _name, (_kind, _description, _parameters) in _SPECS.items():
    REGISTRY.register(_name, _kind, _description, _parameters, toolset="glass_ui")
