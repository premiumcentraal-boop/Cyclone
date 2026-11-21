"""A real gateway with a seeded Command Center, and Glass opened in Chromium on it.

Nothing here touches the owner's Cyclone: the runtime folder is a new temporary directory, the gateway listens on a free
loopback port with a random bearer, and the data is synthetic (plan 76 test-data policy).
"""
from __future__ import annotations

import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

ROOT = Path(__file__).resolve().parents[3]
GLASS_DIST = ROOT / "apps/glass/dist"
CHROMIUM = Path(os.environ.get("GLASS_BENCH_CHROMIUM", "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome"))
WORDS = ("plan", "review", "export", "weekly", "budget", "launch", "draft", "archive", "summary", "harbour", "orchard")


@dataclass(frozen=True)
class Fixture:
    """Ids of the seeded things the benchmark opens."""
    big_page: str      # 1,000 paragraph blocks (today's page cap)
    small_page: str    # 50 blocks
    table_page: str    # one table block over the big table
    table_rows: int
    search_title: str  # a unique title quick find must find
    search_body: str   # a unique word inside one page's body


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def seed(runtime: Path, *, rows: int = 10_000, pages: int = 300) -> Fixture:
    """Write a Command Center file where the gateway will open it. Uses the stores' own APIs."""
    import random

    from cyclone_device_gateway.command.center import CommandCenter

    class NoPhones:  # the benchmark never starts a task, so no phone is called
        def __getattr__(self, name: str) -> Any:
            raise RuntimeError(f"no phone in the benchmark ({name})")

    rng = random.Random(76)
    center = CommandCenter(runtime / "command" / "command.db", NoPhones(), lambda: [])
    try:
        def page_with(title: str, count: int, words: str = "") -> str:
            page = center.pages.create({"title": title})
            blocks = [{"type": "p", "text": [{"t": " ".join(rng.choice(WORDS) for _ in range(14)) + (f" {words}" if i == 3 else "")}]}
                      for i in range(count)]
            return center.pages.update(page["id"], {"version": page["version"], "blocks": blocks})["id"]

        for i in range(pages):
            page_with(f"{rng.choice(WORDS).title()} notes {i:04d}", 8)
        big = page_with("Benchmark: a long page", 1000)
        small = page_with("Benchmark: a normal page", 50, words="quasarlight")
        table = center.tables.create({"title": "Benchmark table"})
        title = next(p["id"] for p in center.tables.get(table["id"])["properties"] if p["type"] == "title")
        added = center.tables.add_property(table["id"], {"name": "Score", "type": "number"})
        score = next(p["id"] for p in added["properties"] if p["name"] == "Score")
        for i in range(rows):
            center.tables.create_row(table["id"], {"cells": {title: f"{rng.choice(WORDS)} row {i:05d}", score: rng.randint(0, 999)}})
        holder = center.pages.create({"title": "Benchmark: a big table"})
        center.pages.update(holder["id"], {"version": holder["version"], "blocks": [{"type": "table", "tableId": table["id"]}]})
        return Fixture(big, small, holder["id"], rows, "Benchmark: a normal page", "quasarlight")
    finally:
        center.stop()


@dataclass
class Gateway:
    url: str
    token: str
    process: subprocess.Popen

    def call(self, method: str, path: str, body: Any = None) -> Any:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=data, method=method,
                                         headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read() or b"null")

    def launch_url(self) -> str:
        return self.url + self.call("POST", "/v1/glass/launch-code", {})["path"]


SERVE = (
    "import uvicorn; from cyclone_device_gateway.config import Settings; from cyclone_device_gateway.cli import build_serve_app;"
    "s = Settings.from_env(); uvicorn.run(build_serve_app(s), host='127.0.0.1', port=s.port, log_level='warning')"
)


@contextmanager
def gateway(rows: int = 10_000, pages: int = 300) -> Iterator[tuple[Gateway, Fixture]]:
    if not (GLASS_DIST / "index.html").exists():
        raise RuntimeError(f"Build Glass first: cd apps/glass && npm run build (no {GLASS_DIST / 'index.html'}).")
    with tempfile.TemporaryDirectory(prefix="glass-bench-") as work:
        runtime = Path(work) / "runtime"
        fixture = seed(runtime, rows=rows, pages=pages)
        port, token = free_port(), secrets.token_urlsafe(24)
        env = {**os.environ, "CYCLONE_DEVICE_GATEWAY_TOKEN": token, "CYCLONE_ANDROID_BRIDGE_TOKEN": secrets.token_urlsafe(24),
               "CYCLONE_DEVICE_GATEWAY_RUNTIME": str(runtime), "CYCLONE_DEVICE_GATEWAY_PORT": str(port),
               "CYCLONE_GLASS_DIST": str(GLASS_DIST), "CYCLONE_DEVICE_GATEWAY_URL": f"http://127.0.0.1:{port}"}
        log = open(Path(work) / "gateway.log", "w")
        process = subprocess.Popen([sys.executable, "-c", SERVE], env=env, stdout=log, stderr=subprocess.STDOUT, cwd=work)
        gw = Gateway(f"http://127.0.0.1:{port}", token, process)
        try:
            deadline = time.monotonic() + 60
            while True:
                try:
                    gw.call("POST", "/v1/glass/launch-code", {})
                    break
                except Exception:  # noqa: BLE001 - still starting
                    if process.poll() is not None or time.monotonic() > deadline:
                        log.flush()
                        raise RuntimeError("The gateway did not start:\n" + (Path(work) / "gateway.log").read_text()[-3000:])
                    time.sleep(0.3)
            yield gw, fixture
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
            log.close()


@contextmanager
def browser(headless: bool = True):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        instance = p.chromium.launch(executable_path=str(CHROMIUM) if CHROMIUM.exists() else None, headless=headless)
        try:
            context = instance.new_context(viewport={"width": 1440, "height": 900}, device_scale_factor=1)
            yield context
        finally:
            instance.close()
