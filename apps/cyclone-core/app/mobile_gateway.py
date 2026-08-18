"""Cyclone Mobile Gateway backed by an external Mobilerun Portal instance.

Run as a small internal service while the compatibility backend is enabled:

    uvicorn app.mobile_gateway:app --host 0.0.0.0 --port 8790

It exposes Cyclone's stable ``phone.*`` envelope and never exposes Mobilerun's
raw API directly to Hermes or the desktop UI.  This is deliberately separate
from the main Core process during the integration phase so Portal can be
removed/replaced without coupling Android transport details to chat state.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import hmac
from typing import AsyncIterator

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status

from .mobile_portal import (
    MobilerunPortalClient,
    PhoneToolRequest,
    PhoneToolResult,
    PortalOwnershipRequest,
    PortalSettings,
    PortalStatusResponse,
)
from .settings import Settings, get_settings


class MobileGatewayRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.portal: MobilerunPortalClient | None = None
        if settings.mobilerun_portal_url and settings.mobilerun_portal_token:
            self.portal = MobilerunPortalClient(
                PortalSettings(
                    base_url=settings.mobilerun_portal_url,
                    token=settings.mobilerun_portal_token,
                )
            )

    async def close(self) -> None:
        if self.portal is not None:
            await self.portal.close()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    runtime = MobileGatewayRuntime(get_settings())
    app.state.runtime = runtime
    try:
        yield
    finally:
        await runtime.close()


app = FastAPI(
    title="Cyclone Mobile Gateway",
    version="0.1.0",
    description="Internal phone-tool gateway with Mobilerun Portal compatibility backend.",
    lifespan=lifespan,
)


def runtime(request: Request) -> MobileGatewayRuntime:
    return request.app.state.runtime


async def require_internal_key(
    x_cyclone_internal_key: str = Header(default="", alias="X-Cyclone-Internal-Key"),
    state: MobileGatewayRuntime = Depends(runtime),
) -> None:
    if not x_cyclone_internal_key or not hmac.compare_digest(
        x_cyclone_internal_key.encode("utf-8"), state.settings.internal_api_key.encode("utf-8")
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Cyclone internal credential.")


def _portal(state: MobileGatewayRuntime) -> MobilerunPortalClient:
    if state.portal is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Mobilerun Portal backend is not configured. Set CYCLONE_MOBILERUN_PORTAL_URL and CYCLONE_MOBILERUN_PORTAL_TOKEN.",
        )
    return state.portal


@app.get("/health", response_model=PortalStatusResponse, tags=["operations"])
async def health(state: MobileGatewayRuntime = Depends(runtime)) -> PortalStatusResponse:
    if state.portal is None:
        return PortalStatusResponse(
            configured=False,
            reachable=False,
            detail="Portal backend is not configured.",
        )
    return await state.portal.status()


@app.get(
    "/api/v1/mobile/status",
    response_model=PortalStatusResponse,
    tags=["mobile"],
    dependencies=[Depends(require_internal_key)],
)
async def mobile_status(state: MobileGatewayRuntime = Depends(runtime)) -> PortalStatusResponse:
    return await _portal(state).status()


@app.post(
    "/api/v1/mobile/tools/execute",
    response_model=PhoneToolResult,
    tags=["mobile"],
    dependencies=[Depends(require_internal_key)],
)
async def execute_phone_tool(
    command: PhoneToolRequest,
    state: MobileGatewayRuntime = Depends(runtime),
) -> PhoneToolResult:
    return await _portal(state).execute(command)


@app.post(
    "/api/v1/mobile/ownership",
    tags=["mobile"],
    dependencies=[Depends(require_internal_key)],
)
async def set_phone_ownership(
    ownership: PortalOwnershipRequest,
    state: MobileGatewayRuntime = Depends(runtime),
) -> dict[str, str | bool]:
    portal = _portal(state)
    portal.set_controller(ownership.owner)
    return {
        "ok": True,
        "owner": portal.controller,
        "freshObservationRequired": ownership.owner == "agent",
    }
