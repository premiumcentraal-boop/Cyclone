"""The only way plugin bytes reach this PC (plan 50 §5): GitHub releases over https, redirects followed only to GitHub's
own hosts, every byte hashed while it streams, hard size caps, bounded retries. Nothing else in ``plugins`` opens a
network connection to the internet.
"""
from __future__ import annotations

import hashlib
import json
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, BinaryIO, Callable, Iterator

ALLOWED_HOSTS = frozenset({
    "api.github.com", "github.com", "raw.githubusercontent.com",
    "objects.githubusercontent.com", "release-assets.githubusercontent.com",
})
API = "https://api.github.com"
USER_AGENT = "Cyclone-Plugins/1"
MAX_REDIRECTS = 5
MAX_JSON_BYTES = 2 * 1024 * 1024
TIMEOUT_S = 30
TOTAL_S = 600
RETRIES = 3

_REPO = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")
_TAG = re.compile(r"^[A-Za-z0-9._/-]{1,100}$")


class GitHubError(Exception):
    """A plain reason the owner can act on. ``retry`` says whether trying again later may help."""

    def __init__(self, message: str, retry: bool = False):
        super().__init__(message)
        self.retry = retry


@dataclass
class Response:
    status: int
    headers: dict[str, str]
    body: BinaryIO | None = None
    close: Callable[[], None] = field(default=lambda: None)


Transport = Callable[[str, dict[str, str]], Response]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # every hop is checked against ALLOWED_HOSTS by the client itself
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def urllib_transport(url: str, headers: dict[str, str]) -> Response:
    request = urllib.request.Request(url, headers=headers)
    try:
        resp = _OPENER.open(request, timeout=TIMEOUT_S)
    except urllib.error.HTTPError as exc:
        return Response(exc.code, {k.lower(): v for k, v in (exc.headers or {}).items()}, exc, exc.close)
    return Response(resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp, resp.close)


def parse_source(text: Any) -> dict[str, str]:
    """``owner/repo``, ``github.com/owner/repo``, ``https://github.com/owner/repo[/releases/tag/<tag>]`` → a repo
    source; a bare plugin name → an index source."""
    if not isinstance(text, str) or not text.strip() or len(text) > 300:
        raise GitHubError("Paste a GitHub link like github.com/owner/repo, or a plugin name from the list.")
    raw = text.strip()
    if raw.lower().endswith(".cyclone.zip") and "://" not in raw:
        # A package file on this PC (plugin authors, the release smoke). Always Unverified; only the owner's token reaches it.
        path = Path(raw).expanduser()
        if not path.is_file():
            raise GitHubError("There's no package file at that path.")
        return {"kind": "file", "path": str(path.resolve())}
    value = raw.removesuffix("/").removesuffix(".git")
    if re.match(r"^[a-z][a-z0-9-]{1,40}$", value):
        return {"kind": "index", "name": value}
    if "://" not in value and value.startswith("github.com/"):
        value = "https://" + value
    tag = ""
    if value.startswith("https://") or value.startswith("http://"):
        parts = urllib.parse.urlsplit(value)
        if parts.scheme != "https" or parts.hostname not in ("github.com", "www.github.com") or parts.port \
                or parts.username or parts.query or parts.fragment:
            raise GitHubError("Only https://github.com links can be added.")
        segments = [s for s in parts.path.split("/") if s]
        if len(segments) == 2:
            value = "/".join(segments)
        elif len(segments) >= 5 and segments[2:4] == ["releases", "tag"]:
            value, tag = "/".join(segments[:2]), "/".join(segments[4:])
        else:
            raise GitHubError("Use the repository's link, like https://github.com/owner/repo.")
    if not _REPO.match(value) or (tag and not _TAG.match(tag)):
        raise GitHubError("That doesn't look like a GitHub repository (owner/repo).")
    return {"kind": "repo", "repo": value, "tag": tag}


class GitHub:
    def __init__(self, transport: Transport = urllib_transport, *, sleep: Callable[[float], None] = time.sleep,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._transport = transport
        self._sleep = sleep
        self._clock = clock

    # ---- one request, redirects checked hop by hop ------------------------------------------------------------------

    def _open(self, url: str, accept: str) -> Response:
        headers = {"User-Agent": USER_AGENT, "Accept": accept}
        if urllib.parse.urlsplit(url).hostname == "api.github.com":
            headers["X-GitHub-Api-Version"] = "2022-11-28"
        for _ in range(MAX_REDIRECTS + 1):
            parts = urllib.parse.urlsplit(url)
            if parts.scheme != "https" or parts.hostname not in ALLOWED_HOSTS or parts.username or parts.port not in (None, 443):
                raise GitHubError("GitHub sent Cyclone somewhere other than GitHub. Nothing was downloaded.")
            resp = self._transport(url, headers)
            if resp.status in (301, 302, 303, 307, 308):
                location = resp.headers.get("location", "")
                resp.close()
                if not location:
                    raise GitHubError("GitHub answered with a redirect but no address.", retry=True)
                url = urllib.parse.urljoin(url, location)
                headers.pop("X-GitHub-Api-Version", None)
                continue
            return resp
        raise GitHubError("Too many redirects from GitHub.", retry=True)

    def _with_retries(self, fn: Callable[[], Any]) -> Any:
        delay = 1.0
        for attempt in range(RETRIES + 1):
            try:
                return fn()
            except GitHubError as exc:
                if not exc.retry or attempt == RETRIES:
                    raise
            except (OSError, urllib.error.URLError) as exc:
                if attempt == RETRIES:
                    raise GitHubError("Couldn't reach GitHub. Check the internet connection and try again.",
                                      retry=True) from exc
            self._sleep(delay + random.uniform(0, delay / 2))
            delay *= 2
        raise AssertionError("unreachable")

    @staticmethod
    def _status_error(resp: Response, what: str) -> GitHubError:
        if resp.status in (403, 429) and (resp.headers.get("x-ratelimit-remaining") == "0" or resp.status == 429):
            reset = resp.headers.get("x-ratelimit-reset", "")
            minutes = max(1, int((int(reset) - time.time()) // 60) + 1) if reset.isdigit() else 60
            return GitHubError(f"GitHub is limiting requests from this PC. Try again in {minutes} minutes.")
        if resp.status == 404:
            return GitHubError(f"GitHub has no {what} there. Check the link; private repositories can't be added.")
        if resp.status >= 500:
            return GitHubError(f"GitHub had a problem ({resp.status}). Try again in a moment.", retry=True)
        return GitHubError(f"GitHub refused the request ({resp.status}).")

    # ---- JSON ---------------------------------------------------------------------------------------------------------

    def get_json(self, url: str, what: str) -> Any:
        def once() -> Any:
            resp = self._open(url, "application/vnd.github+json")
            try:
                if resp.status != 200:
                    raise self._status_error(resp, what)
                raw = resp.body.read(MAX_JSON_BYTES + 1) if resp.body else b""
            finally:
                resp.close()
            if len(raw) > MAX_JSON_BYTES:
                raise GitHubError(f"GitHub's answer about the {what} was too large.")
            try:
                return json.loads(raw)
            except ValueError as exc:
                raise GitHubError(f"GitHub's answer about the {what} wasn't readable.", retry=True) from exc

        return self._with_retries(once)

    def get_bytes(self, url: str, what: str, limit: int = MAX_JSON_BYTES) -> bytes:
        def once() -> bytes:
            resp = self._open(url, "application/octet-stream")
            try:
                if resp.status != 200:
                    raise self._status_error(resp, what)
                raw = resp.body.read(limit + 1) if resp.body else b""
            finally:
                resp.close()
            if len(raw) > limit:
                raise GitHubError(f"The {what} is too large.")
            return raw

        return self._with_retries(once)

    # ---- releases -----------------------------------------------------------------------------------------------------

    def release(self, repo: str, tag: str = "") -> dict[str, Any]:
        """The latest published release, or the one with ``tag``. Drafts and pre-releases are never "latest"."""
        if not _REPO.match(repo) or (tag and not _TAG.match(tag)):
            raise GitHubError("That isn't a valid repository or tag.")
        path = f"/repos/{repo}/releases/tags/{urllib.parse.quote(tag, safe='')}" if tag else f"/repos/{repo}/releases/latest"
        doc = self.get_json(API + path, "release")
        if not isinstance(doc, dict) or not isinstance(doc.get("tag_name"), str):
            raise GitHubError("GitHub's release answer was missing its tag.")
        if doc.get("draft"):
            raise GitHubError("That release is a draft.")
        assets = []
        for a in doc.get("assets") or []:
            if not isinstance(a, dict) or not isinstance(a.get("name"), str):
                continue
            url = a.get("browser_download_url")
            digest = a.get("digest") if isinstance(a.get("digest"), str) else ""
            assets.append({"name": a["name"], "url": url if isinstance(url, str) else "",
                           "size": a.get("size") if isinstance(a.get("size"), int) else None,
                           "sha256": digest[7:].lower() if digest.startswith("sha256:") else ""})
        return {"tag": doc["tag_name"], "prerelease": bool(doc.get("prerelease")), "assets": assets,
                "publishedAt": doc.get("published_at") if isinstance(doc.get("published_at"), str) else ""}

    def download(self, url: str, dest: Path, limit: int, progress: Callable[[int], None] = lambda n: None) -> str:
        """Streams ``url`` into ``dest`` (created new), hashing as it goes. Returns the SHA-256. A partial file is
        removed on any failure."""
        started = self._clock()

        def once() -> str:
            resp = self._open(url, "application/octet-stream")
            digest, size = hashlib.sha256(), 0
            try:
                if resp.status != 200:
                    raise self._status_error(resp, "release file")
                length = resp.headers.get("content-length", "")
                if length.isdigit() and int(length) > limit:
                    raise GitHubError(f"The release file is larger than {limit // (1024 * 1024)} MB.")
                with open(dest, "wb") as sink:
                    for block in _blocks(resp.body):
                        size += len(block)
                        if size > limit:
                            raise GitHubError(f"The release file is larger than {limit // (1024 * 1024)} MB.")
                        if self._clock() - started > TOTAL_S:
                            raise GitHubError("The download took longer than 10 minutes.", retry=False)
                        digest.update(block)
                        sink.write(block)
                        progress(size)
                if length.isdigit() and size != int(length):
                    raise GitHubError("The download was cut off.", retry=True)
                return digest.hexdigest()
            except BaseException:
                dest.unlink(missing_ok=True)
                raise
            finally:
                resp.close()

        return self._with_retries(once)


def _blocks(body: BinaryIO | None, size: int = 1 << 16) -> Iterator[bytes]:
    if body is None:
        return
    while True:
        block = body.read(size)
        if not block:
            return
        yield block
