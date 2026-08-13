from uuid import uuid4
import json

import httpx
import pytest

from app.hermes import HermesAdapter


@pytest.mark.asyncio
async def test_adapter_submits_private_authenticated_run_to_hermes() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers.get("authorization")
        captured["path"] = request.url.path
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"run_id": "run_123", "status": "started"})

    adapter = HermesAdapter(
        base_url="http://hermes:8642",
        api_key="test-private-key",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    result = await adapter.start_run(
        conversation_id=uuid4(),
        input_text="Inspect the project and report the current state.",
        system_instructions="You are Chief.",
        provider="deepseek",
        model="deepseek-chat",
    )

    assert result.run_id == "run_123"
    assert captured["authorization"] == "Bearer test-private-key"
    assert captured["path"] == "/v1/runs"
    assert captured["body"] == {
        "input": "Inspect the project and report the current state.",
        "session_id": str(result.session_id),
        "instructions": "You are Chief.",
        "provider": "deepseek",
        "model": "deepseek-chat",
    }

    await adapter.close()


@pytest.mark.asyncio
async def test_adapter_reports_unavailable_without_leaking_secret() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="Hermes unavailable")

    adapter = HermesAdapter(
        base_url="http://hermes:8642",
        api_key="sensitive-token",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(RuntimeError) as error:
        await adapter.start_run(
            conversation_id=uuid4(),
            input_text="hello",
            system_instructions="You are Chief.",
        )

    assert "sensitive-token" not in str(error.value)
    await adapter.close()


@pytest.mark.asyncio
async def test_observe_run_keeps_a_missing_hermes_run_distinct_from_an_outage() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"detail": "run not found"})

    adapter = HermesAdapter(
        base_url="http://hermes:8642",
        api_key="test-private-key",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    observation = await adapter.observe_run("run_lost_after_restart")

    assert observation.found is False
    assert observation.state.value == "missing"
    await adapter.close()
