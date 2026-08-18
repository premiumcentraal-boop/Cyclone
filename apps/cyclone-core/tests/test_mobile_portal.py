from __future__ import annotations

import json

import httpx
import pytest

from app.mobile_portal import (
    MobilerunPortalClient,
    PhoneToolRequest,
    PortalSettings,
    find_nodes,
    normalize_portal_state,
)


def _state_payload(*, include_claim: bool = True) -> dict:
    children = [
        {
            "resourceId": "com.example:id/title",
            "className": "android.widget.TextView",
            "packageName": "com.example",
            "text": "Available shift",
            "contentDescription": "",
            "boundsInScreen": {"left": 30, "top": 100, "right": 800, "bottom": 180},
            "isClickable": False,
            "isLongClickable": False,
            "isEditable": False,
            "isScrollable": False,
            "isEnabled": True,
            "isVisibleToUser": True,
            "windowId": 3,
            "children": [],
        }
    ]
    if include_claim:
        children.append(
            {
                "resourceId": "com.example:id/claim",
                "className": "android.widget.Button",
                "packageName": "com.example",
                "text": "Claim shift",
                "contentDescription": "Claim this shift",
                "boundsInScreen": {"left": 500, "top": 1600, "right": 1000, "bottom": 1760},
                "isClickable": True,
                "isLongClickable": True,
                "isEditable": False,
                "isScrollable": False,
                "isEnabled": True,
                "isVisibleToUser": True,
                "windowId": 3,
                "children": [],
            }
        )
    return {
        "a11y_tree": {
            "resourceId": "root",
            "className": "android.widget.FrameLayout",
            "packageName": "com.example",
            "text": "",
            "contentDescription": "",
            "boundsInScreen": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
            "isClickable": False,
            "isEditable": False,
            "isScrollable": False,
            "isEnabled": True,
            "isVisibleToUser": True,
            "windowId": 3,
            "children": children,
        },
        "phone_state": {
            "currentApp": "Example",
            "packageName": "com.example",
            "activityName": "com.example.ShiftsActivity",
            "keyboardVisible": False,
            "isEditable": False,
        },
        "device_context": {"screen": {"width": 1080, "height": 2400}},
    }


def _json_response(payload: object, request: httpx.Request) -> httpx.Response:
    return httpx.Response(
        200,
        headers={"content-type": "application/json"},
        json={"status": "success", "result": json.dumps(payload) if isinstance(payload, (dict, list)) else payload},
        request=request,
    )


def test_normalize_portal_state_matches_cyclone_shape() -> None:
    snapshot = normalize_portal_state(_state_payload())
    assert snapshot["package"] == "com.example"
    assert snapshot["class"] == "com.example.ShiftsActivity"
    assert snapshot["screen"] == {"width": 1080, "height": 2400}
    assert snapshot["backend"] == "mobilerun_portal"
    assert len(snapshot["nodes"]) == 3
    claim = find_nodes(snapshot, {"resourceId": "com.example:id/claim", "clickable": True}, limit=1)[0]
    assert claim["node"]["text"] == "Claim shift"
    assert claim["node"]["role"] == "button"
    assert claim["node"]["parentId"] is not None
    assert snapshot["fingerprint"]


def test_selector_supports_text_description_ancestor_and_fuzzy() -> None:
    snapshot = normalize_portal_state(_state_payload())
    assert find_nodes(snapshot, {"textContains": "claim", "clickable": True}, limit=1)
    assert find_nodes(snapshot, {"contentDescriptionContains": "this shift"}, limit=1)
    assert find_nodes(snapshot, {"ancestorText": ""}, limit=1) == []
    fuzzy = find_nodes(snapshot, {"fuzzyText": "claim shft", "minFuzzyScore": 0.65}, limit=1)
    assert fuzzy and fuzzy[0]["node"]["resourceId"] == "com.example:id/claim"


@pytest.mark.asyncio
async def test_click_uses_portal_tree_then_semantic_center_tap() -> None:
    calls: list[tuple[str, dict[str, str]]] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/state_full":
            return _json_response(_state_payload(), request)
        if request.url.path == "/tap":
            body = (await request.aread()).decode()
            values = dict(pair.split("=", 1) for pair in body.split("&") if "=" in pair)
            calls.append((request.url.path, values))
            return _json_response("ok", request)
        raise AssertionError(f"Unexpected request {request.method} {request.url}")

    client = MobilerunPortalClient(
        PortalSettings(base_url="http://phone.test:8080", token="test-token"),
        transport=httpx.MockTransport(handler),
    )
    try:
        result = await client.execute(
            PhoneToolRequest(id="click-1", tool="phone.click", params={"selector": {"resourceId": "com.example:id/claim"}})
        )
        assert result.ok is True
        assert result.payload["performed"] is True
        assert calls and calls[0][0] == "/tap"
        # Center of [500,1600]-[1000,1760] = 750,1680.
        assert float(calls[0][1]["x"]) == 750.0
        assert float(calls[0][1]["y"]) == 1680.0
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_human_takeover_blocks_actions_and_requires_fresh_observe_on_return() -> None:
    taps = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal taps
        if request.url.path == "/state_full":
            return _json_response(_state_payload(), request)
        if request.url.path == "/tap":
            taps += 1
            return _json_response("ok", request)
        raise AssertionError(request.url.path)

    client = MobilerunPortalClient(
        PortalSettings(base_url="http://phone.test:8080", token="test-token"),
        transport=httpx.MockTransport(handler),
    )
    try:
        client.set_controller("human")
        blocked = await client.execute(PhoneToolRequest(id="tap-human", tool="phone.tap", params={"x": 10, "y": 10}))
        assert blocked.ok is False
        assert blocked.error and blocked.error.code == "HUMAN_HAS_CONTROL"
        assert taps == 0

        client.set_controller("agent")
        stale = await client.execute(PhoneToolRequest(id="tap-stale", tool="phone.tap", params={"x": 10, "y": 10}))
        assert stale.ok is False
        assert stale.error and stale.error.code == "FRESH_OBSERVATION_REQUIRED"
        assert taps == 0

        observed = await client.execute(PhoneToolRequest(id="observe-1", tool="phone.observe"))
        assert observed.ok is True
        allowed = await client.execute(PhoneToolRequest(id="tap-after", tool="phone.tap", params={"x": 10, "y": 10}))
        assert allowed.ok is True
        assert taps == 1
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_command_ids_are_idempotent() -> None:
    taps = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal taps
        if request.url.path == "/state_full":
            return _json_response(_state_payload(), request)
        if request.url.path == "/tap":
            taps += 1
            return _json_response("ok", request)
        raise AssertionError(request.url.path)

    client = MobilerunPortalClient(
        PortalSettings(base_url="http://phone.test:8080", token="test-token"),
        transport=httpx.MockTransport(handler),
    )
    try:
        request = PhoneToolRequest(id="same-command", tool="phone.tap", params={"x": 100, "y": 200})
        first = await client.execute(request)
        second = await client.execute(request)
        assert first.ok and second.ok
        assert first.model_dump() == second.model_dump()
        assert taps == 1
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_screenshot_returns_metadata_and_opt_in_base64() -> None:
    # Minimal valid-enough PNG header for width/height parsing.
    png = b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + (1080).to_bytes(4, "big") + (2400).to_bytes(4, "big") + b"rest"

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/state_full":
            return _json_response(_state_payload(), request)
        if request.url.path == "/screenshot":
            return httpx.Response(200, headers={"content-type": "image/png"}, content=png, request=request)
        raise AssertionError(request.url.path)

    client = MobilerunPortalClient(
        PortalSettings(base_url="http://phone.test:8080", token="test-token"),
        transport=httpx.MockTransport(handler),
    )
    try:
        metadata = await client.execute(PhoneToolRequest(id="shot-1", tool="phone.screenshot"))
        assert metadata.ok
        assert metadata.payload["width"] == 1080
        assert metadata.payload["height"] == 2400
        assert "pngBase64" not in metadata.payload

        with_data = await client.execute(
            PhoneToolRequest(id="shot-2", tool="phone.screenshot", params={"includeBase64": True})
        )
        assert with_data.ok
        assert with_data.payload["pngBase64"]
    finally:
        await client.close()
