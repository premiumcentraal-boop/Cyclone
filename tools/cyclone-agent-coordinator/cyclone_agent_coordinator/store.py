"""Atomic local persistence for team state, event journals, and mailboxes."""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from contextlib import contextmanager
from dataclasses import asdict, replace
from pathlib import Path
from typing import Any, Iterator

from .errors import ConflictError, NotFoundError, ValidationError
from .models import MailboxMessage, TeamEvent, TeamRecord
from .validation import require_identifier


class FileTeamStore:
    """Stores each team below a caller-selected local runtime root."""

    def __init__(self, root: Path | str) -> None:
        self.root = Path(root)
        self._lock = threading.RLock()

    def list_team_ids(self) -> list[str]:
        if not self.root.exists():
            return []
        return sorted(
            path.name
            for path in self.root.iterdir()
            if path.is_dir() and (path / "team.json").is_file()
        )

    def create(self, team: TeamRecord) -> None:
        with self._lock:
            team_dir = self._team_dir(team.team_id)
            if (team_dir / "team.json").exists():
                raise ConflictError(f"Team already exists: {team.team_id}")
            team_dir.mkdir(parents=True, exist_ok=False)
            (team_dir / "mailboxes").mkdir()
            self._write_json_atomic(team_dir / "team.json", team.to_dict())

    def load(self, team_id: str) -> TeamRecord:
        with self._lock:
            path = self._team_dir(team_id) / "team.json"
            if not path.is_file():
                raise NotFoundError(f"Team not found: {team_id}")
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                return TeamRecord.from_dict(data)
            except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                raise ValidationError(f"Team state is invalid: {team_id}: {error}") from error

    def save(self, team: TeamRecord, expected_revision: int) -> None:
        with self._lock:
            team_dir = self._team_dir(team.team_id)
            with self._exclusive_file_lock(team_dir / ".team.lock"):
                current = self.load(team.team_id)
                if current.revision != expected_revision:
                    raise ConflictError(
                        f"Team revision changed: expected {expected_revision}, found {current.revision}"
                    )
                self._write_json_atomic(team_dir / "team.json", team.to_dict())

    def append_event(self, event: TeamEvent) -> TeamEvent:
        path = self._team_dir(event.team_id) / "events.jsonl"
        with self._lock, self._exclusive_file_lock(path.with_suffix(".lock")):
            sequence = self._next_sequence_unlocked(path)
            stored = replace(event, sequence=sequence)
            self._append_json_line_unlocked(path, asdict(stored))
            return stored

    def read_events(self, team_id: str, after_sequence: int = 0) -> list[dict[str, Any]]:
        path = self._team_dir(team_id) / "events.jsonl"
        with self._lock, self._exclusive_file_lock(path.with_suffix(".lock")):
            return self._read_json_lines_unlocked(path, after_sequence)

    def append_message(self, message: MailboxMessage) -> MailboxMessage:
        recipient = require_identifier(message.recipient_id, "recipient_id")
        path = self._team_dir(message.team_id) / "mailboxes" / f"{recipient}.jsonl"
        with self._lock, self._exclusive_file_lock(path.with_suffix(".lock")):
            sequence = self._next_sequence_unlocked(path)
            stored = replace(message, sequence=sequence)
            self._append_json_line_unlocked(path, asdict(stored))
            return stored

    def read_mailbox(
        self,
        team_id: str,
        recipient_id: str,
        after_sequence: int = 0,
    ) -> list[dict[str, Any]]:
        recipient = require_identifier(recipient_id, "recipient_id")
        path = self._team_dir(team_id) / "mailboxes" / f"{recipient}.jsonl"
        with self._lock, self._exclusive_file_lock(path.with_suffix(".lock")):
            return self._read_json_lines_unlocked(path, after_sequence)

    def _team_dir(self, team_id: str) -> Path:
        require_identifier(team_id, "team_id")
        candidate = self.root / team_id
        root_resolved = self.root.resolve()
        candidate_resolved = candidate.resolve()
        if os.path.commonpath((str(root_resolved), str(candidate_resolved))) != str(root_resolved):
            raise ValidationError("Team path escapes the state root")
        return candidate

    @staticmethod
    def _append_json_line_unlocked(path: Path, data: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(data, sort_keys=True, separators=(",", ":")) + "\n")
            stream.flush()
            os.fsync(stream.fileno())

    @staticmethod
    def _read_json_lines_unlocked(path: Path, after_sequence: int) -> list[dict[str, Any]]:
        if after_sequence < 0:
            raise ValidationError("after_sequence must be non-negative")
        if not path.exists():
            return []
        records: list[dict[str, Any]] = []
        try:
            for line in path.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                record = json.loads(line)
                if int(record.get("sequence", 0)) > after_sequence:
                    records.append(record)
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            raise ValidationError(f"Journal is invalid: {path.name}: {error}") from error
        return sorted(records, key=lambda record: int(record["sequence"]))

    def _next_sequence_unlocked(self, path: Path) -> int:
        records = self._read_json_lines_unlocked(path, 0)
        return max((int(record.get("sequence", 0)) for record in records), default=0) + 1

    @contextmanager
    def _exclusive_file_lock(self, path: Path) -> Iterator[None]:
        path.parent.mkdir(parents=True, exist_ok=True)
        deadline = time.monotonic() + 10
        descriptor: int | None = None
        while descriptor is None:
            try:
                descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(descriptor, str(os.getpid()).encode("ascii"))
            except FileExistsError:
                try:
                    stale = time.time() - path.stat().st_mtime > 30
                    if stale:
                        path.unlink()
                        continue
                except FileNotFoundError:
                    continue
                if time.monotonic() >= deadline:
                    raise ConflictError(f"Timed out waiting for state lock: {path.name}")
                time.sleep(0.01)
        try:
            yield
        finally:
            os.close(descriptor)
            try:
                path.unlink()
            except FileNotFoundError:
                pass

    @staticmethod
    def _write_json_atomic(path: Path, data: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix=".team-", suffix=".tmp", dir=path.parent)
        temporary_path = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
                json.dump(data, stream, indent=2, sort_keys=True)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_path, path)
        finally:
            if temporary_path.exists():
                temporary_path.unlink()
