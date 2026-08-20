from __future__ import annotations

import re

from ..state.store import StateStore


def _controls(semantic: dict) -> list[dict]:
    for key in ("controls", "semantic_controls", "elements", "nodes"):
        value = semantic.get(key)
        if isinstance(value, list):
            return [x for x in value if isinstance(x, dict)]
    return []


def _text(c: dict) -> str:
    return " ".join(str(c.get(k, "")) for k in ("text", "label", "content_desc", "contentDescription", "resource_id", "resourceId", "role")).lower()


def rank_control(c: dict, goal: str | None = None) -> float:
    score = 0.0
    hay = _text(c)
    if goal:
        terms = [t for t in re.findall(r"\w+", goal.lower()) if len(t) > 1]
        score += 10 * sum(t in hay for t in terms)
    if c.get("clickable") or c.get("interactive") or c.get("enabled"): score += 5
    if c.get("resource_id") or c.get("resourceId"): score += 4
    if c.get("role") or c.get("class"): score += 3
    if c.get("visible", True): score += 2
    if c.get("verified") or c.get("learned"): score += 2
    return score


class RetrievalService:
    def __init__(self, store: StateStore): self.store = store

    def search_ui(self, query: str, limit: int = 20) -> list[dict]:
        current = self.store.current_observation()
        if not current: return []
        q = query.lower().strip()
        matches = [dict(c, source=c.get("source", "CYCLONE_ACCESSIBILITY")) for c in _controls(current["semantic"]) if q in _text(c)]
        uia = current.get("uiautomator") or {}
        matches += [n for n in uia.get("nodes", []) if q in _text(n)]
        return sorted(matches, key=lambda c: rank_control(c, query), reverse=True)[:limit]

    def get_element(self, element_id: str) -> dict | None:
        current = self.store.current_observation()
        if not current: return None
        for c in _controls(current["semantic"]):
            if str(c.get("id") or c.get("element_id")) == element_id:
                return dict(c, source=c.get("source", "CYCLONE_ACCESSIBILITY"))
        for n in (current.get("uiautomator") or {}).get("nodes", []):
            if str(n.get("id")) == element_id: return n
        return None

    def get_controls(self, goal: str | None = None, limit: int = 36) -> list[dict]:
        current = self.store.current_observation()
        if not current: return []
        controls = [dict(c, source=c.get("source", "CYCLONE_ACCESSIBILITY")) for c in _controls(current["semantic"])]
        return sorted(controls, key=lambda c: rank_control(c, goal), reverse=True)[:limit]

    def get_page_context(self, mode: str = "compact", goal: str | None = None) -> dict | None:
        current = self.store.current_observation()
        if not current: return None
        if mode == "full": return current
        if mode != "compact": raise ValueError("mode must be compact or full")
        return {"id": current["id"], "page_key": current["page_key"], "package": current["package"], "activity": current["activity"],
                "screenshot": current["screenshot"], "controls": self.get_controls(goal, 36), "sources": ["CYCLONE_ACCESSIBILITY"] + (["UIAUTOMATOR"] if current.get("uiautomator") else [])}

    def compare_sources(self, query: str) -> dict:
        current = self.store.current_observation()
        if not current: return {"query": query, "sources": {}}
        q=query.lower()
        cyclone=[c for c in _controls(current["semantic"]) if q in _text(c)]
        uia=[n for n in (current.get("uiautomator") or {}).get("nodes", []) if q in _text(n)]
        return {"query": query, "sources": {"CYCLONE_ACCESSIBILITY": cyclone, "UIAUTOMATOR": uia}, "merged": False}
