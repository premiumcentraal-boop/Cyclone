"""The results folder the two Claude sessions share: every run's report, its trials and one findings ledger.

Layout (default `./testbench-results`, pushed to the `testbench/results` branch so a cloud session can read it):

    runs.jsonl                     one line per experiment: what ran, on which build, how it went
    findings.jsonl                 the ledger: one line per finding, rewritten in place as findings change
    experiments/<id>/report.md     the run report a person reads
    experiments/<id>/trials.jsonl  the trials, redacted

Everything written here is redacted first: no e-mail addresses, phone numbers, long numbers, tokens or secrets.
The Lab already keeps Wi-Fi names and account names out of its trials; this is the second net.
"""
from __future__ import annotations

import json
import re
import time
from pathlib import Path
from typing import Any, Iterable

EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
#: A run of digits that could be a phone number. Ids (exp-20261005-…), dates and times are left alone: the match may not
#: touch a letter, dash or dot on either side, and it needs at least 9 digits.
PHONE = re.compile(r"(?<![\w.\-])\+?\d[\d ()\-]{7,}\d(?![\w.\-])")
DATE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
SECRET_KEY = re.compile(r"(?i)(token|password|passwd|secret|otp|api[_-]?key|bearer|cookie|credential|pin)")
#: Addresses the missions themselves use; they are test fixtures, not anyone's data.
FIXTURE_EMAILS = {"cyclone-lab@example.com"}


def redact_text(text: str) -> str:
    text = EMAIL.sub(lambda m: m.group(0) if m.group(0).lower() in FIXTURE_EMAILS else "[email]", text)
    def mask(m: re.Match[str]) -> str:
        found = m.group(0)
        if DATE.search(found) or sum(ch.isdigit() for ch in found) < 9:
            return found
        return "[number]"
    return PHONE.sub(mask, text)


def redact(value: Any) -> Any:
    """A deep copy with secrets dropped and personal strings masked."""
    if isinstance(value, dict):
        return {k: ("[redacted]" if SECRET_KEY.search(str(k)) else redact(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    if isinstance(value, str):
        return redact_text(value)
    return value


class Results:
    def __init__(self, root: Path):
        self.root = root

    # ---- files ------------------------------------------------------------------------------------------------------

    def ensure(self) -> None:
        (self.root / "experiments").mkdir(parents=True, exist_ok=True)

    def experiment_dir(self, exp_id: str) -> Path:
        if not re.match(r"^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$", exp_id):
            raise ValueError("not an experiment id")
        return self.root / "experiments" / exp_id

    def read_jsonl(self, name: str) -> list[dict[str, Any]]:
        path = self.root / name
        if not path.is_file():
            return []
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(line)
            except ValueError:
                continue
            if isinstance(row, dict):
                rows.append(row)
        return rows

    def write_jsonl(self, name: str, rows: Iterable[dict[str, Any]]) -> None:
        self.ensure()
        path = self.root / name
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text("".join(json.dumps(redact(r), ensure_ascii=False, sort_keys=True) + "\n" for r in rows), encoding="utf-8")
        tmp.replace(path)

    def append_jsonl(self, name: str, row: dict[str, Any]) -> None:
        self.ensure()
        with (self.root / name).open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(redact(row), ensure_ascii=False, sort_keys=True) + "\n")

    # ---- runs -------------------------------------------------------------------------------------------------------

    def runs(self) -> list[dict[str, Any]]:
        return self.read_jsonl("runs.jsonl")

    def record_run(self, summary: dict[str, Any]) -> None:
        rows = [r for r in self.runs() if r.get("experimentId") != summary.get("experimentId")]
        rows.append(summary)
        self.write_jsonl("runs.jsonl", rows)

    def save_experiment(self, exp_id: str, report_md: str, trials: list[dict[str, Any]]) -> Path:
        folder = self.experiment_dir(exp_id)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "report.md").write_text(redact_text(report_md), encoding="utf-8")
        (folder / "trials.jsonl").write_text(
            "".join(json.dumps(redact(t), ensure_ascii=False, sort_keys=True) + "\n" for t in trials), encoding="utf-8")
        return folder


def now_ms() -> int:
    return int(time.time() * 1000)
