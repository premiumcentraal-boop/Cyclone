from __future__ import annotations

import urllib.parse
from typing import Any

from .gateway import GatewayClient as CoreGatewayClient


class GatewayClient(CoreGatewayClient):
    """Adds skill compile/run/match HTTP adapters. No local skill store."""

    def skill_save(self, payload: dict[str, Any]) -> Any:
        return self._bounded_route("POST", "/v1/skills/save", payload)

    def device_skill_save(self, device_id: str, payload: dict[str, Any]) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/skills",
            payload,
        )

    def skill_run(self, skill_id: str, *, dry_run: bool = False, params: dict[str, Any] | None = None) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/skills/{_quote(skill_id)}/runs",
            {"dryRun": bool(dry_run), "params": params or {}},
        )

    def device_skill_run(
        self,
        device_id: str,
        skill_id: str,
        *,
        dry_run: bool = False,
        params: dict[str, Any] | None = None,
    ) -> Any:
        return self._bounded_route(
            "POST",
            f"/v1/devices/{_quote(device_id)}/agent/skills/{_quote(skill_id)}/runs",
            {"dryRun": bool(dry_run), "params": params or {}},
        )

    def skill_match(self, goal: str, page_key: str = "") -> Any:
        query = urllib.parse.urlencode({"goal": goal, "pageKey": page_key})
        return self._bounded_route("GET", f"/v1/skills/match?{query}")

    def device_skill_match(self, device_id: str, goal: str, page_key: str = "") -> Any:
        query = urllib.parse.urlencode({"goal": goal, "pageKey": page_key})
        return self._bounded_route(
            "GET",
            f"/v1/devices/{_quote(device_id)}/agent/skills?{query}",
        )


def _quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")
