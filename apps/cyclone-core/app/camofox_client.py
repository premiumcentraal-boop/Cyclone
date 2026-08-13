"""Policy-bound adapter for a private Camofox browser service.

The upstream Camofox service provides browser sessions keyed by an arbitrary
``userId``.  Cyclone intentionally never exposes that primitive to agents:
each request instead carries a Core-authorized ``BrowserAccessGrant``.  The
grant deterministically maps one agent to one private Camofox profile and
bounds every URL to explicitly granted origins.

This adapter is intentionally read-focused.  It supports opening/navigating
tabs, token-bounded accessibility snapshots, constrained extraction, and
download *metadata*.  It does not expose cookie import/export, proxies,
arbitrary page evaluation, form entry, file upload, or action loops.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlparse
from uuid import UUID, uuid4

import httpx


_ELEMENT_REF = re.compile(r"^e[1-9][0-9]*$")
_FIELD_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")


class CamofoxError(RuntimeError):
    """A safe-to-display failure from the private browser boundary."""


class CamofoxPolicyError(CamofoxError):
    """The requested browser operation is outside an explicit Core grant."""


def _normalise_base_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("CAMOFOX_BASE_URL must be an absolute http(s) URL")
    if parsed.query or parsed.fragment:
        raise ValueError("CAMOFOX_BASE_URL cannot contain query parameters or fragments")
    return value.rstrip("/")


def _normalise_origin(value: str) -> str:
    parsed = urlparse(value)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or parsed.username
        or parsed.password
        or parsed.path not in {"", "/"}
        or parsed.params
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("browser origins must be bare http(s) origins")
    return f"{parsed.scheme}://{parsed.netloc.lower()}"


def _origin_for_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise CamofoxPolicyError("Only absolute http(s) URLs may be opened in the browser.")
    if parsed.username or parsed.password:
        raise CamofoxPolicyError("URLs containing credentials are not permitted.")
    return f"{parsed.scheme}://{parsed.netloc.lower()}"


@dataclass(frozen=True)
class CamofoxClientConfig:
    """Runtime configuration kept outside of agent-visible tool arguments."""

    base_url: str
    api_key: str
    timeout_seconds: float = 20.0
    max_snapshot_characters: int = 30_000
    max_download_bytes: int = 10 * 1024 * 1024

    def __post_init__(self) -> None:
        object.__setattr__(self, "base_url", _normalise_base_url(self.base_url))
        if not self.api_key.strip():
            raise ValueError("CAMOFOX_API_KEY must not be empty")
        if not 1.0 <= self.timeout_seconds <= 120.0:
            raise ValueError("Camofox timeout_seconds must be between 1 and 120")
        if not 1_000 <= self.max_snapshot_characters <= 100_000:
            raise ValueError("max_snapshot_characters must be between 1,000 and 100,000")
        if not 1_024 <= self.max_download_bytes <= 100 * 1024 * 1024:
            raise ValueError("max_download_bytes must be between 1 KiB and 100 MiB")

    @classmethod
    def from_environment(cls) -> "CamofoxClientConfig":
        """Load deployment-only configuration without extending agent tool inputs.

        ``CAMOFOX_API_KEY`` is sent as a Bearer token.  On current upstream
        Camofox this should be configured as ``CAMOFOX_ACCESS_KEY`` on the
        service, because that key gates all normal browsing routes.
        """

        return cls(
            base_url=os.getenv("CAMOFOX_BASE_URL", "http://camofox:9377"),
            api_key=os.getenv("CAMOFOX_API_KEY", ""),
            timeout_seconds=float(os.getenv("CAMOFOX_TIMEOUT_SECONDS", "20")),
            max_snapshot_characters=int(os.getenv("CAMOFOX_MAX_SNAPSHOT_CHARACTERS", "30000")),
            max_download_bytes=int(os.getenv("CAMOFOX_MAX_DOWNLOAD_BYTES", str(10 * 1024 * 1024))),
        )


@dataclass(frozen=True)
class BrowserAccessGrant:
    """A Core-issued, resource-scoped permission for one agent browser session."""

    agent_id: UUID
    conversation_id: UUID
    resource_id: UUID
    allowed_origins: tuple[str, ...]
    expires_at: datetime | None = None

    def __post_init__(self) -> None:
        origins = tuple(sorted({_normalise_origin(origin) for origin in self.allowed_origins}))
        if not origins:
            raise ValueError("A browser access grant requires at least one allowed origin")
        object.__setattr__(self, "allowed_origins", origins)
        if self.expires_at is not None and self.expires_at.tzinfo is None:
            raise ValueError("Browser access grant expiry must be timezone-aware")

    @property
    def profile_id(self) -> str:
        """Stable private Camofox profile; never supplied by an agent."""

        return f"cyclone-agent-{self.agent_id.hex}"

    @property
    def session_key(self) -> str:
        """Conversation-bounded tab grouping inside the private profile."""

        return f"cyclone-conversation-{self.conversation_id.hex}"

    def assert_active(self) -> None:
        if self.expires_at is not None and datetime.now(UTC) >= self.expires_at:
            raise CamofoxPolicyError("This browser access grant has expired.")

    def assert_url_allowed(self, url: str) -> None:
        self.assert_active()
        if _origin_for_url(url) not in self.allowed_origins:
            raise CamofoxPolicyError("The requested site is not included in this browser access grant.")


@dataclass(frozen=True)
class BrowserAuditMetadata:
    """Non-secret operation metadata for Cyclone's durable audit trail."""

    request_id: str
    operation: str
    agent_id: UUID
    conversation_id: UUID
    resource_id: UUID
    profile_id: str
    session_key: str
    occurred_at: datetime
    timeout_seconds: float
    max_snapshot_characters: int
    max_download_bytes: int


@dataclass(frozen=True)
class CamofoxTab:
    tab_id: str
    url: str
    grant: BrowserAccessGrant = field(repr=False, compare=False)
    audit: BrowserAuditMetadata


@dataclass(frozen=True)
class CamofoxSnapshot:
    tab: CamofoxTab
    url: str
    content: str
    references_count: int
    source_truncated: bool
    client_truncated: bool
    total_characters: int | None
    audit: BrowserAuditMetadata


@dataclass(frozen=True)
class CamofoxExtractionField:
    """A single scalar value identified by a prior accessibility snapshot ref."""

    name: str
    element_ref: str
    value_type: str = "string"
    required: bool = False

    def __post_init__(self) -> None:
        if not _FIELD_NAME.fullmatch(self.name):
            raise ValueError("Extraction field names must be simple identifiers")
        if not _ELEMENT_REF.fullmatch(self.element_ref):
            raise ValueError("Extraction element_ref must be an accessibility ref such as e12")
        if self.value_type not in {"string", "number", "integer", "boolean"}:
            raise ValueError("Unsupported extraction value type")


@dataclass(frozen=True)
class CamofoxExtraction:
    tab: CamofoxTab
    values: dict[str, str | int | float | bool | None]
    audit: BrowserAuditMetadata


@dataclass(frozen=True)
class CamofoxDownload:
    """Captured-download metadata only; content stays out of agent tool responses."""

    download_id: str
    url: str
    filename: str
    mime_type: str
    size_bytes: int | None
    created_at: str | None
    failure: str | None
    exceeds_size_limit: bool


@dataclass(frozen=True)
class CamofoxDownloads:
    tab: CamofoxTab
    downloads: tuple[CamofoxDownload, ...]
    audit: BrowserAuditMetadata


class CamofoxBrowserClient:
    """Async adapter for Camofox's documented, read-oriented REST endpoints."""

    def __init__(
        self,
        *,
        config: CamofoxClientConfig,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._config = config
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(timeout=config.timeout_seconds)

    @property
    def config(self) -> CamofoxClientConfig:
        return self._config

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def open_tab(self, grant: BrowserAccessGrant, url: str) -> CamofoxTab:
        grant.assert_url_allowed(url)
        data, audit = await self._request(
            grant,
            "open_tab",
            "POST",
            "/tabs",
            json={"userId": grant.profile_id, "sessionKey": grant.session_key, "url": url},
        )
        tab_id = data.get("tabId")
        response_url = data.get("url")
        if not isinstance(tab_id, str) or not tab_id or not isinstance(response_url, str):
            raise CamofoxError("Camofox returned an invalid tab response.")
        try:
            grant.assert_url_allowed(response_url)
        except CamofoxPolicyError:
            await self._best_effort_close(tab_id, grant)
            raise
        return CamofoxTab(tab_id=tab_id, url=response_url, grant=grant, audit=audit)

    async def navigate(self, tab: CamofoxTab, url: str) -> CamofoxTab:
        tab.grant.assert_url_allowed(url)
        data, audit = await self._request(
            tab.grant,
            "navigate",
            "POST",
            f"/tabs/{tab.tab_id}/navigate",
            json={"userId": tab.grant.profile_id, "sessionKey": tab.grant.session_key, "url": url},
        )
        response_url = data.get("url")
        if not isinstance(response_url, str):
            raise CamofoxError("Camofox returned an invalid navigation response.")
        try:
            tab.grant.assert_url_allowed(response_url)
        except CamofoxPolicyError:
            await self._best_effort_close(tab.tab_id, tab.grant)
            raise
        return CamofoxTab(tab_id=tab.tab_id, url=response_url, grant=tab.grant, audit=audit)

    async def snapshot(self, tab: CamofoxTab) -> CamofoxSnapshot:
        data, audit = await self._request(
            tab.grant,
            "snapshot",
            "GET",
            f"/tabs/{tab.tab_id}/snapshot",
            params={"userId": tab.grant.profile_id, "format": "text", "offset": 0, "includeScreenshot": "false"},
        )
        response_url = data.get("url")
        snapshot = data.get("snapshot")
        if not isinstance(response_url, str) or not isinstance(snapshot, str):
            raise CamofoxError("Camofox returned an invalid accessibility snapshot.")
        try:
            tab.grant.assert_url_allowed(response_url)
        except CamofoxPolicyError:
            await self._best_effort_close(tab.tab_id, tab.grant)
            raise
        content = snapshot[: self._config.max_snapshot_characters]
        references_count = data.get("refsCount")
        total_characters = data.get("totalChars")
        return CamofoxSnapshot(
            tab=CamofoxTab(tab_id=tab.tab_id, url=response_url, grant=tab.grant, audit=audit),
            url=response_url,
            content=content,
            references_count=references_count if isinstance(references_count, int) else 0,
            source_truncated=data.get("truncated") is True or data.get("hasMore") is True,
            client_truncated=len(content) < len(snapshot),
            total_characters=total_characters if isinstance(total_characters, int) else None,
            audit=audit,
        )

    async def extract(
        self, tab: CamofoxTab, fields: tuple[CamofoxExtractionField, ...]
    ) -> CamofoxExtraction:
        if not fields or len(fields) > 20:
            raise CamofoxPolicyError("Context extraction requires between one and twenty fields.")
        schema: dict[str, Any] = {
            "type": "object",
            "properties": {
                field.name: {"type": field.value_type, "x-ref": field.element_ref} for field in fields
            },
        }
        required = [field.name for field in fields if field.required]
        if required:
            schema["required"] = required
        data, audit = await self._request(
            tab.grant,
            "extract",
            "POST",
            f"/tabs/{tab.tab_id}/extract",
            json={"userId": tab.grant.profile_id, "schema": schema},
        )
        extracted = data.get("data")
        if data.get("ok") is not True or not isinstance(extracted, dict):
            raise CamofoxError("Camofox could not extract the requested page context.")
        values: dict[str, str | int | float | bool | None] = {}
        for field in fields:
            value = extracted.get(field.name)
            if value is None or isinstance(value, (str, int, float, bool)):
                values[field.name] = value
            else:
                raise CamofoxError("Camofox returned a non-scalar extracted value.")
        return CamofoxExtraction(tab=tab, values=values, audit=audit)

    async def list_downloads(self, tab: CamofoxTab) -> CamofoxDownloads:
        """Return bounded metadata, never inline file content, for a resource importer.

        A later Core-owned resource importer may fetch an approved download.  It
        must reject every item whose recorded size exceeds this client's cap.
        """

        data, audit = await self._request(
            tab.grant,
            "list_downloads",
            "GET",
            f"/tabs/{tab.tab_id}/downloads",
            params={"userId": tab.grant.profile_id},
        )
        raw_downloads = data.get("downloads")
        if not isinstance(raw_downloads, list):
            raise CamofoxError("Camofox returned an invalid downloads response.")
        downloads: list[CamofoxDownload] = []
        for item in raw_downloads[:20]:
            if not isinstance(item, dict):
                continue
            size = item.get("bytes")
            size_bytes = size if isinstance(size, int) and size >= 0 else None
            downloads.append(
                CamofoxDownload(
                    download_id=str(item.get("id", "")),
                    url=str(item.get("url", "")),
                    filename=str(item.get("suggestedFilename", item.get("filename", "download"))),
                    mime_type=str(item.get("mimeType", "application/octet-stream")),
                    size_bytes=size_bytes,
                    created_at=item.get("createdAt") if isinstance(item.get("createdAt"), str) else None,
                    failure=item.get("failure") if isinstance(item.get("failure"), str) else None,
                    exceeds_size_limit=size_bytes is not None and size_bytes > self._config.max_download_bytes,
                )
            )
        return CamofoxDownloads(tab=tab, downloads=tuple(downloads), audit=audit)

    async def close_tab(self, tab: CamofoxTab) -> BrowserAuditMetadata:
        _, audit = await self._request(
            tab.grant,
            "close_tab",
            "DELETE",
            f"/tabs/{tab.tab_id}",
            params={"userId": tab.grant.profile_id},
        )
        return audit

    async def health(self) -> tuple[bool, str]:
        try:
            response = await self._client.get(f"{self._config.base_url}/health", timeout=self._config.timeout_seconds)
        except httpx.HTTPError:
            return False, "Camofox health endpoint is unreachable."
        if response.status_code == 200:
            return True, "Camofox health endpoint returned OK."
        return False, f"Camofox health endpoint returned HTTP {response.status_code}."

    async def _best_effort_close(self, tab_id: str, grant: BrowserAccessGrant) -> None:
        try:
            await self._request(
                grant,
                "policy_close_tab",
                "DELETE",
                f"/tabs/{tab_id}",
                params={"userId": grant.profile_id},
            )
        except CamofoxError:
            pass

    async def _request(
        self,
        grant: BrowserAccessGrant,
        operation: str,
        method: str,
        path: str,
        *,
        json: dict[str, Any] | None = None,
        params: dict[str, str | int] | None = None,
    ) -> tuple[dict[str, Any], BrowserAuditMetadata]:
        grant.assert_active()
        request_id = uuid4().hex
        audit = BrowserAuditMetadata(
            request_id=request_id,
            operation=operation,
            agent_id=grant.agent_id,
            conversation_id=grant.conversation_id,
            resource_id=grant.resource_id,
            profile_id=grant.profile_id,
            session_key=grant.session_key,
            occurred_at=datetime.now(UTC),
            timeout_seconds=self._config.timeout_seconds,
            max_snapshot_characters=self._config.max_snapshot_characters,
            max_download_bytes=self._config.max_download_bytes,
        )
        try:
            response = await self._client.request(
                method,
                f"{self._config.base_url}{path}",
                json=json,
                params=params,
                headers={"Authorization": f"Bearer {self._config.api_key}", "X-Request-ID": request_id},
                timeout=self._config.timeout_seconds,
            )
        except httpx.HTTPError as error:
            raise CamofoxError("Camofox browser service is unreachable.") from error
        if response.status_code >= 400:
            raise CamofoxError(f"Camofox browser service rejected {operation} with HTTP {response.status_code}.")
        try:
            data = response.json()
        except ValueError as error:
            raise CamofoxError("Camofox browser service returned invalid JSON.") from error
        if not isinstance(data, dict):
            raise CamofoxError("Camofox browser service returned an invalid response.")
        return data, audit
