"""The Cyclone index on this PC (plan 50 §4): fetched from GitHub, trusted only when signed by a key compiled in here,
never older than the last one accepted, and used as the kill switch for revoked plugin files.

``TRUSTED_KEYS`` is empty until the owner creates the index repo and its signing key (see the release notes): until
then the index reads "not set up", every plugin is Unverified, and nothing else changes.
"""
from __future__ import annotations

import threading
from datetime import datetime, timezone
from typing import Any, Callable

from . import kindex
from .github import GitHub, GitHubError
from .store import PluginsStore, now_ms

# {key id: base64 Ed25519 public key}. Two slots so a key can rotate without a window where nothing verifies.
TRUSTED_KEYS: dict[str, str] = {}
INDEX_URL = "https://raw.githubusercontent.com/premiumcentraal-boop/cyclone-plugins/main/index.json"
REFRESH_EVERY_MS = 6 * 60 * 60 * 1000
MAX_INDEX_BYTES = 1024 * 1024


class IndexClient:
    def __init__(self, store: PluginsStore, github: GitHub, *, trusted: dict[str, str] | None = None,
                 url: str = INDEX_URL, now: Callable[[], datetime] = lambda: datetime.now(timezone.utc)) -> None:
        self._store = store
        self._github = github
        self._trusted = dict(TRUSTED_KEYS if trusted is None else trusted)
        self._url = url
        self._now = now
        self._lock = threading.Lock()
        self._doc: dict[str, Any] | None = None
        self._error = ""
        self._checked_at: int | None = None
        self._load_saved()

    @property
    def configured(self) -> bool:
        return bool(self._trusted)

    def _load_saved(self) -> None:
        saved = self._store.index_state()
        if not saved or not self._trusted:
            return
        try:
            # Saved copies are re-verified (a file edited on disk is refused); expiry is checked separately.
            self._doc = kindex.verify(bytes(saved["raw"]), bytes(saved["sig"]), self._trusted,
                                         now=datetime.fromisoformat("2000-01-01T00:00:00+00:00"))
        except kindex.IndexTrustError as exc:
            self._error = f"The saved list couldn't be trusted ({exc}). It will be fetched again."

    def refresh(self) -> dict[str, Any]:
        """Fetches and verifies the index. A failure keeps the last good copy and says why."""
        if not self._trusted:
            return self.status()
        with self._lock:
            saved = self._store.index_state()
            try:
                raw = self._github.get_bytes(self._url, "plugin list", MAX_INDEX_BYTES)
                sig = self._github.get_bytes(self._url + ".sig", "plugin list signature", 4096)
                doc = kindex.verify(raw, sig, self._trusted, now=self._now(),
                                       min_serial=saved["serial"] if saved else 0)
                self._store.save_index(doc["serial"], raw, sig)
                self._doc, self._error = doc, ""
            except (GitHubError, kindex.IndexTrustError) as exc:
                self._error = str(exc)
            self._checked_at = now_ms()
        return self.status()

    def due(self) -> bool:
        return self.configured and (self._checked_at is None or now_ms() - self._checked_at > REFRESH_EVERY_MS)

    def expired(self) -> bool:
        if self._doc is None:
            return True
        return datetime.fromisoformat(self._doc["expiresAt"].replace("Z", "+00:00")) <= self._now()

    def doc(self) -> dict[str, Any] | None:
        return self._doc

    def entry(self, name: str) -> dict[str, Any] | None:
        return kindex.find(self._doc, name)

    def usable_entry(self, name: str) -> dict[str, Any] | None:
        """An index entry Cyclone may install as Verified: the index is current and lists it."""
        return None if self.expired() else self.entry(name)

    def verified_version(self, name: str, sha256: str) -> dict[str, Any] | None:
        entry = self.usable_entry(name)
        for v in (entry or {}).get("versions") or []:
            if v.get("sha256") == sha256:
                return v
        return None

    def revoked(self, sha256: str) -> dict[str, Any] | None:
        # The kill switch works from the last good copy even when it has expired.
        return kindex.revoked(self._doc, sha256)

    def status(self) -> dict[str, Any]:
        doc = self._doc
        state = "not_set_up" if not self._trusted else ("missing" if doc is None else ("expired" if self.expired() else "ok"))
        return {
            "state": state,
            "serial": doc["serial"] if doc else None,
            "expiresAt": doc["expiresAt"] if doc else None,
            "checkedAt": self._checked_at,
            "error": self._error,
            "plugins": [{"name": p["name"], "repo": p["repo"], "latest": kindex.newest(p)["version"]}
                        for p in (doc or {}).get("plugins") or []],
        }
