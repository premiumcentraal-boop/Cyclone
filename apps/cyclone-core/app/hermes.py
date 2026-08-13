"""Authenticated private adapter for the documented Hermes Runs API."""

from __future__ import annotations

import json
from dataclasses import dataclass
from uuid import UUID

import httpx

from .recovery import HermesRunObservation


@dataclass(frozen=True)
class StartedRun:
    run_id: str
    status: str
    session_id: UUID


class HermesAdapter:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(base_url=base_url, timeout=30.0)
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key

    @property
    def headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._api_key}"}

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()
        else:
            await self._client.aclose()

    async def health(self) -> tuple[bool, str]:
        try:
            response = await self._client.get(f"{self._base_url}/health")
            if response.status_code == 200:
                return True, "Hermes health endpoint returned OK."
            return False, f"Hermes health endpoint returned HTTP {response.status_code}."
        except httpx.HTTPError:
            return False, "Hermes health endpoint is unreachable."

    async def start_run(
        self,
        *,
        conversation_id: UUID,
        input_text: str,
        system_instructions: str,
        provider: str | None = None,
        model: str | None = None,
    ) -> StartedRun:
        payload: dict[str, object] = {
            "input": input_text,
            "session_id": str(conversation_id),
            "instructions": system_instructions,
        }
        if provider:
            payload["provider"] = provider
        if model:
            payload["model"] = model
        try:
            response = await self._client.post(
                f"{self._base_url}/v1/runs",
                json=payload,
                headers=self.headers,
            )
        except httpx.HTTPError as error:
            raise RuntimeError("Hermes run endpoint is unreachable.") from error
        if response.status_code >= 400:
            raise RuntimeError(f"Hermes rejected run request with HTTP {response.status_code}.")
        data = response.json()
        run_id = data.get("run_id")
        status = data.get("status")
        if not isinstance(run_id, str) or not isinstance(status, str):
            raise RuntimeError("Hermes returned an invalid run response.")
        return StartedRun(run_id=run_id, status=status, session_id=conversation_id)

    async def get_run(self, run_id: str) -> dict[str, object]:
        try:
            response = await self._client.get(
                f"{self._base_url}/v1/runs/{run_id}", headers=self.headers
            )
        except httpx.HTTPError as error:
            raise RuntimeError("Hermes run status endpoint is unreachable.") from error
        if response.status_code >= 400:
            raise RuntimeError(f"Hermes run status returned HTTP {response.status_code}.")
        data = response.json()
        if not isinstance(data, dict):
            raise RuntimeError("Hermes returned invalid run status data.")
        return data

    async def observe_run(self, run_id: str) -> HermesRunObservation:
        """Look up a pre-restart run without conflating 404 with an outage."""
        try:
            response = await self._client.get(
                f"{self._base_url}/v1/runs/{run_id}", headers=self.headers
            )
        except httpx.HTTPError as error:
            return HermesRunObservation(found=None, detail=f"Hermes status lookup failed: {type(error).__name__}")
        if response.status_code == 404:
            return HermesRunObservation(found=False, detail="Hermes returned HTTP 404 for the stored run.")
        if response.status_code >= 400:
            return HermesRunObservation(
                found=None, detail=f"Hermes status lookup returned HTTP {response.status_code}."
            )
        try:
            data = response.json()
        except ValueError:
            return HermesRunObservation(found=None, detail="Hermes returned invalid JSON for the stored run.")
        if not isinstance(data, dict):
            return HermesRunObservation(found=None, detail="Hermes returned an invalid stored-run payload.")
        run_status = data.get("status")
        return HermesRunObservation(
            found=True,
            status=run_status if isinstance(run_status, str) else None,
            detail="Hermes returned the stored run.",
        )

    async def stop_run(self, run_id: str) -> None:
        try:
            response = await self._client.post(
                f"{self._base_url}/v1/runs/{run_id}/stop", headers=self.headers
            )
        except httpx.HTTPError as error:
            raise RuntimeError("Hermes stop endpoint is unreachable.") from error
        if response.status_code >= 400:
            raise RuntimeError(f"Hermes stop endpoint returned HTTP {response.status_code}.")

    async def resolve_run_approval(self, run_id: str, choice: str) -> dict[str, object]:
        """Resolve a pending Hermes run approval (once | session | always | deny)."""
        try:
            response = await self._client.post(
                f"{self._base_url}/v1/runs/{run_id}/approval",
                json={"choice": choice},
                headers=self.headers,
            )
        except httpx.HTTPError as error:
            raise RuntimeError("Hermes approval endpoint is unreachable.") from error
        if response.status_code >= 400:
            raise RuntimeError(f"Hermes approval endpoint returned HTTP {response.status_code}.")
        data = response.json()
        return data if isinstance(data, dict) else {"status": "resolved"}

    async def get_run_approval_request(self, run_id: str, timeout: float = 8.0) -> dict[str, object] | None:
        """Capture the pending approval.request event from the run's SSE stream.

        Returns None when no approval request is seen within *timeout* seconds.
        """
        try:
            async with self._client.stream(
                "GET", f"{self._base_url}/v1/runs/{run_id}/events", headers=self.headers, timeout=timeout
            ) as response:
                if response.status_code >= 400:
                    return None
                event: str | None = None
                data_lines: list[str] = []
                async for line in response.aiter_lines():
                    line = line.strip()
                    if not line:
                        if event == "approval.request" and data_lines:
                            payload = json.loads("\n".join(data_lines))
                            return payload if isinstance(payload, dict) else None
                        event = None
                        data_lines = []
                    elif line.startswith("event:"):
                        event = line[len("event:"):].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line[len("data:"):].strip())
        except (httpx.HTTPError, json.JSONDecodeError, TimeoutError):
            return None
        return None
