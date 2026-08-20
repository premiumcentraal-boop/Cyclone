from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from cyclone_agent_coordinator.cli import main


BASE_SHA = "a" * 40


class CliTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.state_root = Path(self.temporary.name) / "state"

    def run_cli(self, *arguments: str) -> tuple[int, dict | list, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = main(["--state-root", str(self.state_root), *arguments])
        output = json.loads(stdout.getvalue()) if stdout.getvalue().strip() else {}
        return result, output, stderr.getvalue()

    def test_create_add_list_and_claim_commands(self) -> None:
        code, created, _ = self.run_cli(
            "team",
            "create",
            "--team-id",
            "v3",
            "--name",
            "V3",
            "--captain",
            "captain",
            "--base-sha",
            BASE_SHA,
        )
        self.assertEqual(0, code)
        self.assertEqual("v3", created["team_id"])
        code, members, _ = self.run_cli(
            "team",
            "member-add",
            "--team",
            "v3",
            "--actor",
            "captain",
            "--member",
            "worker",
            "--base-sha",
            BASE_SHA,
        )
        self.assertEqual(0, code)
        self.assertEqual(["captain", "worker"], members["members"])

        code, task, _ = self.run_cli(
            "task",
            "add",
            "--team",
            "v3",
            "--actor",
            "captain",
            "--task-id",
            "capability",
            "--owner-lane",
            "platform",
            "--owned-path",
            "tools/example/**",
            "--base-sha",
            BASE_SHA,
        )
        self.assertEqual(0, code)
        self.assertEqual("READY", task["status"])

        code, claimed, _ = self.run_cli(
            "task",
            "claim",
            "--team",
            "v3",
            "--task",
            "capability",
            "--agent",
            "worker",
            "--base-sha",
            BASE_SHA,
        )
        self.assertEqual(0, code)
        self.assertEqual("CLAIMED", claimed["status"])
        self.assertTrue(claimed["attempt_id"])

        code, tasks, _ = self.run_cli("task", "list", "--team", "v3")
        self.assertEqual(0, code)
        self.assertEqual(["capability"], [item["task_id"] for item in tasks])

    def test_cli_returns_structured_error_for_wrong_sha(self) -> None:
        self.run_cli(
            "team",
            "create",
            "--team-id",
            "v3",
            "--name",
            "V3",
            "--captain",
            "captain",
            "--base-sha",
            BASE_SHA,
        )
        code, _, stderr = self.run_cli(
            "task",
            "add",
            "--team",
            "v3",
            "--actor",
            "captain",
            "--task-id",
            "bad",
            "--owner-lane",
            "platform",
            "--owned-path",
            "tools/example/**",
            "--base-sha",
            "d" * 40,
        )
        self.assertEqual(2, code)
        error = json.loads(stderr)
        self.assertEqual("VALIDATION_ERROR", error["error"])

    def test_handoff_validate_command_checks_owned_evidence_bundle(self) -> None:
        self.run_cli(
            "team",
            "create",
            "--team-id",
            "v3",
            "--name",
            "V3",
            "--captain",
            "captain",
            "--base-sha",
            BASE_SHA,
        )
        self.run_cli(
            "task",
            "add",
            "--team",
            "v3",
            "--actor",
            "captain",
            "--task-id",
            "handoff",
            "--owner-lane",
            "platform",
            "--owned-path",
            "tools/example/**",
            "--base-sha",
            BASE_SHA,
        )
        head_sha = "b" * 40
        bundle_path = Path(self.temporary.name) / "handoff.json"
        bundle_path.write_text(
            json.dumps(
                {
                    "artifacts": [
                        {
                            "path": "tools/example/result.py",
                            "sha256": "c" * 64,
                            "description": "Result",
                        }
                    ],
                    "test_evidence": [
                        {"command": "python -m unittest", "result": "PASS", "summary": "OK"}
                    ],
                    "handoff": {
                        "branch": "agent/example",
                        "base_sha": BASE_SHA,
                        "head_sha": head_sha,
                        "commits": [{"sha": head_sha, "message": "feat: result"}],
                        "owned_scope_respected": True,
                        "files_changed": ["tools/example/result.py"],
                        "contract_changes": "None",
                        "tests_run": "Passed",
                        "ci_state": "Not run",
                        "physical_device_state": "Not applicable",
                        "security_privacy_notes": "No secrets",
                        "known_limitations": "None",
                        "integration_instructions": "Cherry-pick head",
                    },
                }
            ),
            encoding="utf-8",
        )

        code, result, _ = self.run_cli(
            "handoff",
            "validate",
            "--team",
            "v3",
            "--task",
            "handoff",
            "--bundle",
            str(bundle_path),
        )
        self.assertEqual(0, code)
        self.assertTrue(result["ok"])


if __name__ == "__main__":
    unittest.main()
