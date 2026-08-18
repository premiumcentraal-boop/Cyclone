"""Hermes-facing MCP tools backed by the typed Cyclone Mobile registry."""

from __future__ import annotations

import json
from typing import Any, Callable

from .mobile_registry import MobileDeviceRegistry


ServicesGetter = Callable[[], Any]


def _result_json(result: Any) -> str:
    return json.dumps(
        {
            "commandId": result.command_id,
            "tool": result.tool,
            "ok": result.ok,
            "payload": result.payload,
            "error": result.error,
            "beforeFingerprint": result.before_fingerprint,
            "afterFingerprint": result.after_fingerprint,
            "attempts": result.attempts,
        },
        ensure_ascii=False,
    )


def register_mobile_mcp_tools(
    mcp: Any,
    get_services: ServicesGetter,
    devices: MobileDeviceRegistry,
) -> None:
    """Install Agent-3 phone tools into the existing Cyclone MCP server."""

    async def require_agent(agent_slug: str) -> Any:
        runtime = get_services()
        try:
            return await runtime.repository.get_agent_by_slug(agent_slug)
        except Exception as error:
            raise ValueError(f"Unknown Cyclone agent slug: {agent_slug}") from error

    @mcp.tool()
    async def list_phone_devices(agent_slug: str) -> str:
        """List live Cyclone Mobile devices and advertised capabilities."""
        await require_agent(agent_slug)
        snapshots = []
        for snapshot in devices.list():
            snapshots.append(
                {
                    "deviceId": snapshot.device_id,
                    "name": snapshot.name,
                    "platform": snapshot.platform,
                    "controller": snapshot.controller.value,
                    "capabilities": dict(snapshot.capabilities),
                    "freshObservationRequired": snapshot.fresh_observation_required,
                }
            )
        return json.dumps({"devices": snapshots}, ensure_ascii=False)

    @mcp.tool()
    async def phone_observe(agent_slug: str, device_id: str) -> str:
        """Observe fresh structured UI state on a specific connected phone."""
        agent = await require_agent(agent_slug)
        result = await devices.execute(device_id, "phone.observe", {}, timeout=20.0)
        runtime = get_services()
        try:
            await runtime.repository.add_audit_event(
                actor_type="agent",
                actor_id=str(agent.id),
                action="PHONE_ACTION",
                target=device_id,
                outcome="success" if result.ok else "failed",
                metadata={"tool": "phone.observe", "command_id": result.command_id},
            )
        except Exception:
            pass
        return _result_json(result)

    @mcp.tool()
    async def phone_execute(
        agent_slug: str,
        device_id: str,
        tool: str,
        arguments: dict[str, Any] | None = None,
        timeout_seconds: float = 30.0,
    ) -> str:
        """Execute one typed phone.* tool on a specific connected device.

        Use deterministic selectors whenever possible. Observe first on an
        unfamiliar screen. Request screenshots only when the UI tree is not
        sufficient. Device ownership and the mandatory fresh-observe rule are
        enforced by Core before any input command is sent.
        """
        agent = await require_agent(agent_slug)
        timeout_seconds = max(0.1, min(float(timeout_seconds), 120.0))
        result = await devices.execute(
            device_id,
            tool,
            dict(arguments or {}),
            timeout=timeout_seconds,
        )
        runtime = get_services()
        try:
            await runtime.repository.add_audit_event(
                actor_type="agent",
                actor_id=str(agent.id),
                action="PHONE_ACTION",
                target=device_id,
                outcome="success" if result.ok else "failed",
                metadata={
                    "tool": tool,
                    "command_id": result.command_id,
                    "before_fingerprint": result.before_fingerprint,
                    "after_fingerprint": result.after_fingerprint,
                },
            )
        except Exception:
            pass
        return _result_json(result)
