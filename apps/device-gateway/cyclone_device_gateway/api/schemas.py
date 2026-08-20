from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, Field


class ObserveRequest(BaseModel):
    screenshot: bool = True
    uiautomator: bool = True


class ActionRequest(BaseModel):
    tool: Literal["phone.observe","phone.find","phone.click","phone.long_press","phone.swipe","phone.scroll","phone.type","phone.back","phone.home","phone.open_app","phone.wait_for"]
    params: dict[str, Any] = Field(default_factory=dict)
    goal: str = ""
    source: str = "PC_CODEX"
    request_id: str | None = None


class TeachStartRequest(BaseModel):
    app_package: str | None = None
    goal: str = ""
