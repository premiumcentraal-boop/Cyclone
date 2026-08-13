from datetime import UTC, datetime, timedelta
import json
from uuid import uuid4

import httpx
import pytest

from app.camofox_client import (
    BrowserAccessGrant,
    CamofoxBrowserClient,
    CamofoxClientConfig,
    CamofoxError,
    CamofoxExtractionField,
    CamofoxPolicyError,
)


def make_grant(*, expires_at: datetime | None = None) -> BrowserAccessGrant:
    return BrowserAccessGrant(
        agent_id=uuid4(),
        conversation_id=uuid4(),
        resource_id=uuid4(),
        allowed_origins=("https://example.com",),
        expires_at=expires_at,
    )


def make_client(handler, **config_overrides: object) -> CamofoxBrowserClient:
    config = CamofoxClientConfig(
        base_url="http://camofox:9377",
        api_key="private-browser-key",
        **config_overrides,
    )
    return CamofoxBrowserClient(
        config=config,
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )


@pytest.mark.asyncio
async def test_open_tab_uses_private_agent_profile_and_auditable_grant() -> None:
    captured: dict[str, object] = {}
    grant = make_grant()

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers.get("authorization")
        captured["request_id"] = request.headers.get("x-request-id")
        captured["body"] = json.loads(request.content)
        assert request.url.path == "/tabs"
        return httpx.Response(200, json={"tabId": "tab_123", "url": "https://example.com/report"})

    client = make_client(handler)
    tab = await client.open_tab(grant, "https://example.com/report")

    assert captured["authorization"] == "Bearer private-browser-key"
    assert isinstance(captured["request_id"], str)
    assert captured["body"] == {
        "userId": f"cyclone-agent-{grant.agent_id.hex}",
        "sessionKey": f"cyclone-conversation-{grant.conversation_id.hex}",
        "url": "https://example.com/report",
    }
    assert tab.tab_id == "tab_123"
    assert tab.audit.resource_id == grant.resource_id
    assert tab.audit.profile_id == f"cyclone-agent-{grant.agent_id.hex}"
    await client.close()


@pytest.mark.asyncio
async def test_snapshot_is_token_bounded_and_never_requests_a_screenshot() -> None:
    grant = make_grant()
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(200, json={"tabId": "tab_123", "url": "https://example.com/"})
        assert request.url.path == "/tabs/tab_123/snapshot"
        assert request.url.params["includeScreenshot"] == "false"
        return httpx.Response(
            200,
            json={
                "url": "https://example.com/",
                "snapshot": "a" * 1_001,
                "refsCount": 4,
                "truncated": False,
                "totalChars": 1_001,
            },
        )

    client = make_client(handler, max_snapshot_characters=1_000)
    tab = await client.open_tab(grant, "https://example.com/")
    snapshot = await client.snapshot(tab)

    assert snapshot.content == "a" * 1_000
    assert snapshot.references_count == 4
    assert snapshot.client_truncated is True
    assert snapshot.audit.max_snapshot_characters == 1_000
    await client.close()


@pytest.mark.asyncio
async def test_ungranted_sites_and_expired_grants_never_call_camofox() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={})

    client = make_client(handler)
    with pytest.raises(CamofoxPolicyError, match="not included"):
        await client.open_tab(make_grant(), "https://not-granted.example/")
    with pytest.raises(CamofoxPolicyError, match="expired"):
        await client.open_tab(
            make_grant(expires_at=datetime.now(UTC) - timedelta(seconds=1)),
            "https://example.com/",
        )
    assert calls == 0
    await client.close()


@pytest.mark.asyncio
async def test_extract_uses_bounded_scalar_schema_and_downloads_are_metadata_only() -> None:
    grant = make_grant()
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(200, json={"tabId": "tab_123", "url": "https://example.com/"})
        if calls == 2:
            assert request.url.path == "/tabs/tab_123/extract"
            body = json.loads(request.content)
            assert body["schema"] == {
                "type": "object",
                "properties": {"headline": {"type": "string", "x-ref": "e2"}},
                "required": ["headline"],
            }
            return httpx.Response(200, json={"ok": True, "data": {"headline": "Cyclone"}})
        assert request.url.path == "/tabs/tab_123/downloads"
        assert "includeData" not in request.url.params
        return httpx.Response(
            200,
            json={
                "downloads": [
                    {"id": "small", "url": "https://example.com/a.txt", "suggestedFilename": "a.txt", "bytes": 50},
                    {"id": "large", "url": "https://example.com/b.zip", "suggestedFilename": "b.zip", "bytes": 1_025},
                ]
            },
        )

    client = make_client(handler, max_download_bytes=1_024)
    tab = await client.open_tab(grant, "https://example.com/")
    extracted = await client.extract(tab, (CamofoxExtractionField("headline", "e2", required=True),))
    downloads = await client.list_downloads(tab)

    assert extracted.values == {"headline": "Cyclone"}
    assert [item.exceeds_size_limit for item in downloads.downloads] == [False, True]
    assert downloads.audit.max_download_bytes == 1_024
    await client.close()


@pytest.mark.asyncio
async def test_errors_do_not_leak_browser_access_key() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"error": "unavailable"})

    client = make_client(handler)
    with pytest.raises(CamofoxError) as error:
        await client.open_tab(make_grant(), "https://example.com/")
    assert "private-browser-key" not in str(error.value)
    await client.close()
