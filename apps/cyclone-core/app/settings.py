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
    vault_path: Path = Field(default=Path("/vault"))
    workspace_path: Path = Field(default=Path("/workspace"))
    cors_origins: list[str] = Field(default_factory=list)

    @field_validator("hermes_base_url")
    @classmethod
    def validate_hermes_url(cls, value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("CYCLONE_HERMES_BASE_URL must be an absolute http(s) URL")
        return value.rstrip("/")

    @classmethod
    def from_environment(cls) -> "Settings":
        origins = [
            origin.strip()
            for origin in os.getenv("CYCLONE_CORS_ORIGINS", "").split(",")
            if origin.strip()
        ]
        return cls(
            environment=os.getenv("CYCLONE_ENVIRONMENT", "development"),
            database_url=os.getenv("CYCLONE_DATABASE_URL", "postgresql://cyclone:cyclone@postgres:5432/cyclone"),
            redis_url=os.getenv("CYCLONE_REDIS_URL", "redis://redis:6379/0"),
            hermes_base_url=os.getenv("CYCLONE_HERMES_BASE_URL", "http://hermes:8642"),
            hermes_api_key=os.getenv("CYCLONE_HERMES_API_KEY", "development-hermes-key"),
            internal_api_key=os.getenv("CYCLONE_INTERNAL_API_KEY", "development-internal-key"),
            host_bridge_token=os.getenv("CYCLONE_HOST_BRIDGE_TOKEN", "development-host-bridge-token"),
            vault_path=Path(os.getenv("CYCLONE_VAULT_PATH", "/vault")),
            workspace_path=Path(os.getenv("CYCLONE_WORKSPACE_PATH", "/workspace")),
            cors_origins=origins,
        )


@lru_cache
def get_settings() -> Settings:
    return Settings.from_environment()


def reset_settings_cache() -> None:
    """Test helper for tests that manipulate environment variables."""
    get_settings.cache_clear()
