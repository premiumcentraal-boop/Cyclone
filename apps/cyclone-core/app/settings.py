"""Typed, fail-fast configuration for Cyclone Core."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from urllib.parse import urlparse
import os

from pydantic import BaseModel, Field, field_validator


class Settings(BaseModel):
    app_name: str = "Cyclone Core"
    environment: str = Field(default="development")
    database_url: str = Field(default="postgresql://cyclone:cyclone@postgres:5432/cyclone")
    redis_url: str = Field(default="redis://redis:6379/0")
    hermes_base_url: str = Field(default="http://hermes:8642")
    hermes_api_key: str = Field(default="development-hermes-key")
    internal_api_key: str = Field(default="development-internal-key")
    host_bridge_token: str = Field(default="development-host-bridge-token")
    mobile_device_token: str | None = None
    vault_path: Path = Field(default=Path("/vault"))
    workspace_path: Path = Field(default=Path("/workspace"))
    agent_environments_root: Path = Field(default=Path("/agent-environments"))
    cors_origins: list[str] = Field(default_factory=list)
    telegram_bot_token: str | None = None
    telegram_allowed_users: list[int] = Field(default_factory=list)
    telegram_home_channel: str | None = None
    mobilerun_portal_url: str | None = None
    mobilerun_portal_token: str | None = None

    @field_validator("hermes_base_url")
    @classmethod
    def validate_hermes_url(cls, value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("CYCLONE_HERMES_BASE_URL must be an absolute http(s) URL")
        return value.rstrip("/")

    @field_validator("mobilerun_portal_url")
    @classmethod
    def validate_mobilerun_portal_url(cls, value: str | None) -> str | None:
        if value is None or not value.strip():
            return None
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("CYCLONE_MOBILERUN_PORTAL_URL must be an absolute http(s) URL")
        return value.rstrip("/")

    @classmethod
    def from_environment(cls) -> "Settings":
        origins = [origin.strip() for origin in os.getenv("CYCLONE_CORS_ORIGINS", "").split(",") if origin.strip()]
        return cls(
            environment=os.getenv("CYCLONE_ENVIRONMENT", "development"),
            database_url=os.getenv("CYCLONE_DATABASE_URL", "postgresql://cyclone:cyclone@postgres:5432/cyclone"),
            redis_url=os.getenv("CYCLONE_REDIS_URL", "redis://redis:6379/0"),
            hermes_base_url=os.getenv("CYCLONE_HERMES_BASE_URL", "http://hermes:8642"),
            hermes_api_key=os.getenv("CYCLONE_HERMES_API_KEY", "development-hermes-key"),
            internal_api_key=os.getenv("CYCLONE_INTERNAL_API_KEY", "development-internal-key"),
            host_bridge_token=os.getenv("CYCLONE_HOST_BRIDGE_TOKEN", "development-host-bridge-token"),
            mobile_device_token=os.getenv("CYCLONE_MOBILE_DEVICE_TOKEN") or None,
            vault_path=Path(os.getenv("CYCLONE_VAULT_PATH", "/vault")),
            workspace_path=Path(os.getenv("CYCLONE_WORKSPACE_PATH", "/workspace")),
            agent_environments_root=Path(os.getenv("CYCLONE_AGENT_ENVIRONMENTS_ROOT", "/agent-environments")),
            cors_origins=origins,
            telegram_bot_token=os.getenv("TELEGRAM_BOT_TOKEN") or None,
            telegram_allowed_users=[int(part) for part in os.getenv("TELEGRAM_ALLOWED_USERS", "").split(",") if part.strip().lstrip("-").isdigit()],
            telegram_home_channel=os.getenv("TELEGRAM_HOME_CHANNEL") or None,
            mobilerun_portal_url=os.getenv("CYCLONE_MOBILERUN_PORTAL_URL") or None,
            mobilerun_portal_token=os.getenv("CYCLONE_MOBILERUN_PORTAL_TOKEN") or None,
        )


@lru_cache
def get_settings() -> Settings:
    return Settings.from_environment()


def reset_settings_cache() -> None:
    get_settings.cache_clear()
