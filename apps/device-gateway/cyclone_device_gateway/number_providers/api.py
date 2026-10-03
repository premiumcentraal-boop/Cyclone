"""Glass configuration/quotes and SDK-signed native plugin endpoints. No purchase/approval-answer tool."""
from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Body, Depends, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from cyclone_ports.sdk import ReplayCache, parse_signature

from ..auth import verify_bearer
from ..ports import kit
from .providers import ProviderError
from .service import PLUGIN


def create_number_providers_router(service, token: str) -> APIRouter:
    router, replays = APIRouter(), ReplayCache()

    def auth(authorization: str | None = Header(default=None)):
        verify_bearer(authorization, token)

    def call(fn):
        try:
            return fn()
        except ProviderError as error:
            raise HTTPException(status_code=400, detail={"code": error.code, "message": str(error), "retryable": False}) from None
        except (KeyError, TypeError, ValueError):
            raise HTTPException(status_code=400, detail={"code": "INVALID_PROVIDER_RESPONSE", "message": "The provider response or settings were invalid."}) from None

    @router.get("/v1/number-providers", dependencies=[Depends(auth)])
    def overview():
        return call(service.overview)

    @router.post("/v1/number-providers/{kind}/config", dependencies=[Depends(auth)])
    def configure(kind: str, body: Any = Body(...)):
        return call(lambda: service.configure(kind, body))

    @router.get("/v1/number-providers/{kind}/catalogue", dependencies=[Depends(auth)])
    def catalogue(kind: str, country: str | None = None, areaCode: str = "213"):
        return call(lambda: service.catalogue(kind, country, areaCode))

    @router.post("/v1/number-providers/{kind}/quotes", dependencies=[Depends(auth)])
    def quote(kind: str, body: Any = Body(...)):
        return call(lambda: service.quote(kind, body))

    @router.get("/v1/number-orders/{quote_id}", dependencies=[Depends(auth)])
    def order(quote_id: str):
        return call(lambda: service.order(quote_id))

    @router.post("/v1/number-orders/{quote_id}/request-approval", dependencies=[Depends(auth)])
    def request_approval(quote_id: str):
        return call(lambda: service.request_approval(quote_id))

    @router.post("/v1/number-orders/{quote_id}/reconcile", dependencies=[Depends(auth)])
    def reconcile(quote_id: str, body: Any = Body(...)):
        if not isinstance(body, dict) or set(body) != {"rentalId"}:
            raise HTTPException(status_code=422, detail={"code": "INVALID_REQUEST", "message": "Identify the actual rental ID from your provider dashboard."})
        return call(lambda: service.reconcile(quote_id, body["rentalId"]))

    # These expose only a public manifest/health; requests use the Ports SDK signature, not the Glass bearer.
    @router.get("/v1/number-plugins/{kind}/cyclone-plugin.json")
    def manifest(kind: str):
        return call(lambda: service.manifest(kind))

    @router.get("/v1/number-plugins/{kind}/health")
    def health(kind: str):
        service._kind(kind)
        return {"ok": True, "name": PLUGIN[kind], "contract": kit.CONTRACT}

    @router.post("/v1/number-plugins/{kind}/ports/{port}/{action}")
    async def plugin_request(kind: str, port: str, action: str, request: Request):
        if kind not in PLUGIN or port not in ("value.in", "code.in") or action not in ("await", "cancel"):
            return JSONResponse(kit.error_body("port_not_served"), status_code=404)
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > kit.LIMITS["envelope_bytes"]:
                return JSONResponse(kit.error_body("too_large"), status_code=413)
        key = service.hub.store.key(PLUGIN[kind])
        signature = request.headers.get(kit.SIGNATURE_HEADER)
        path = request.url.path + ("?" + request.url.query if request.url.query else "")
        if not key or not kit.verify(key, signature, "POST", path, bytes(raw)):
            return JSONResponse(kit.error_body("bad_signature"), status_code=401)
        if not replays.first_time(parse_signature(signature)["id"]):
            return JSONResponse(kit.error_body("replayed"), status_code=409)
        try:
            body = json.loads(raw)
        except (ValueError, UnicodeError):
            return JSONResponse(kit.error_body("bad_json"), status_code=400)
        if not isinstance(body, dict) or (action == "await" and any(not isinstance(body.get(k), str) for k in ("awaitId", "deliverUrl", "token"))):
            return JSONResponse(kit.error_body("bad_await"), status_code=422)
        # The worker consumes only real waits already saved by Traffic. A signed sample cannot buy/read an inbox.
        return JSONResponse({"accepted": True} if action == "await" else {"cancelled": True}, status_code=202 if action == "await" else 200)

    return router
