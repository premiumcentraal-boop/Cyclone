"""`/v1/cloud/*`: the owner's cloud accounts and which phones Cyclone keeps connected. Same loopback bearer as every
gateway route. No route takes a command, an adb argument or a URL to call; keys go in and never come back out.

Alpha.117 adds the owner's VMOS buttons: rent phones (a period, or pay-for-time), renew, auto-renew, power a
pay-for-time phone on or off, back a phone up and restore its own backup. Renting and renewing carry the exact total
the owner confirmed (`expectedPriceCents`); the gateway refuses if VMOS now asks anything else. No MCP or model tool
reaches these routes (CI guard)."""
from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field

from ..auth import verify_bearer
from ..desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from .models import ProviderError
from .service import CloudFleetService


class AccountBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    provider: str = Field(min_length=1, max_length=16)
    label: str | None = Field(default=None, max_length=40)
    secrets: dict[str, str] = Field(default_factory=dict, max_length=4)
    installCyclone: bool = True
    baseUrl: str | None = Field(default=None, max_length=200)
    endpoints: dict[str, str] = Field(default_factory=dict, max_length=4)


class PhoneBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    keep: bool | None = None
    address: str | None = Field(default=None, max_length=300)
    billing: Literal["rental", "timing"] | None = None


class RentBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["rental", "timing"]
    skuId: int = Field(ge=1)
    android: int = Field(ge=13, le=15)
    count: int = Field(default=1, ge=1, le=5)
    autoRenew: bool = False
    expectedPriceCents: int = Field(ge=0)


class RenewBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    skuId: int = Field(ge=1)
    expectedPriceCents: int = Field(ge=0)


class SwitchBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    on: bool


class BackupBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str | None = Field(default=None, max_length=60)


class RestoreBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    backupId: str = Field(min_length=1, max_length=120, pattern=r"^[A-Za-z0-9_.\-]+$")


class AddressBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    address: str = Field(min_length=3, max_length=300)
    name: str | None = Field(default=None, max_length=80)


def create_cloud_fleet_router(service: CloudFleetService, token: str) -> APIRouter:
    router = APIRouter()

    def auth(authorization: str | None = Header(default=None)) -> None:
        verify_bearer(authorization, token)

    def call(fn) -> Any:
        try:
            return fn()
        except ProviderError as exc:
            raise HTTPException(status_code=502, detail=exc.to_dict()) from exc
        except DesktopRuntimeError as exc:
            status = {RuntimeErrorCode.DEVICE_NOT_FOUND.value: 404, RuntimeErrorCode.INVALID_REQUEST.value: 400}.get(exc.code, 503)
            raise HTTPException(status_code=status, detail=exc.to_dict()) from exc

    @router.get("/v1/cloud", dependencies=[Depends(auth)])
    def cloud_status():
        return call(service.status)

    @router.post("/v1/cloud/accounts", dependencies=[Depends(auth)])
    def add_account(body: AccountBody):
        return call(lambda: service.add_account(body.model_dump()))

    @router.post("/v1/cloud/accounts/{account_id}/remove", dependencies=[Depends(auth)])
    def remove_account(account_id: str):
        return call(lambda: service.remove_account(account_id))

    @router.post("/v1/cloud/accounts/{account_id}/refresh", dependencies=[Depends(auth)])
    def refresh(account_id: str):
        return call(lambda: (service.refresh_phones(account_id), service.account_public(account_id))[1])

    @router.post("/v1/cloud/accounts/{account_id}/addresses", dependencies=[Depends(auth)])
    def add_address(account_id: str, body: AddressBody):
        return call(lambda: service.add_address(account_id, body.address, body.name))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}", dependencies=[Depends(auth)])
    def set_phone(account_id: str, remote_id: str, body: PhoneBody):
        return call(lambda: service.set_phone(account_id, remote_id, keep=body.keep, address=body.address, billing=body.billing))

    # The owner's VMOS buttons (alpha.117) ----------------------------------------------------------------------

    @router.get("/v1/cloud/accounts/{account_id}/offers", dependencies=[Depends(auth)])
    def offers(account_id: str, android: int = Query(default=13, ge=13, le=15)):
        return call(lambda: {"android": android, "offers": service.offers(account_id, android)})

    @router.post("/v1/cloud/accounts/{account_id}/rent", dependencies=[Depends(auth)])
    def rent(account_id: str, body: RentBody):
        return call(lambda: service.rent(account_id, body.model_dump()))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}/renew", dependencies=[Depends(auth)])
    def renew(account_id: str, remote_id: str, body: RenewBody):
        return call(lambda: service.renew(account_id, remote_id, body.skuId, body.expectedPriceCents))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}/auto-renew", dependencies=[Depends(auth)])
    def auto_renew(account_id: str, remote_id: str, body: SwitchBody):
        return call(lambda: service.set_auto_renew(account_id, remote_id, body.on))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}/power", dependencies=[Depends(auth)])
    def power(account_id: str, remote_id: str, body: SwitchBody):
        return call(lambda: service.power(account_id, remote_id, body.on))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}/backup", dependencies=[Depends(auth)])
    def backup(account_id: str, remote_id: str, body: BackupBody):
        return call(lambda: service.start_backup(account_id, remote_id, body.name))

    @router.post("/v1/cloud/accounts/{account_id}/phones/{remote_id}/restore", dependencies=[Depends(auth)])
    def restore(account_id: str, remote_id: str, body: RestoreBody):
        return call(lambda: service.restore(account_id, remote_id, body.backupId))

    return router
