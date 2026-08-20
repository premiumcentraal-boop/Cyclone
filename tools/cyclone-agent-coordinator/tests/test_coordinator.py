from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from cyclone_agent_coordinator.coordinator import CycloneAgentCoordinator
from cyclone_agent_coordinator.errors import (
    AuthorizationError,
    ConflictError,
    DependencyBlockedError,
    StaleAttemptError,
    TransitionError,
    ValidationError,
)
from cyclone_agent_coordinator.models import (
    ArtifactRecord,
    CommitEvidence,
    CompletionBundle,
    HandoffRecord,
    TaskRecord,
    TaskStatus,
    TeamEvent,
    TeamRecord,
    TestEvidence,
    TestResult,
)
from cyclone_agent_coordinator.store import FileTeamStore
from cyclone_agent_coordinator.validation import validate_task_graph


BASE_SHA = "a" * 40
HEAD_SHA = "b" * 40
ARTIFACT_SHA = "c" * 64


class MutableClock:
    def __init__(self, value: int = 100) -> None:
        self.value = value

    def __call__(self) -> int:
        return self.value


class IdFactory:
    def __init__(self) -> None:
        self.value = 0

    def __call__(self) -> str:
        self.value += 1
        return f"id-{self.value}"


def completion_bundle(path: str = "tools/example/result.py") -> CompletionBundle:
    return CompletionBundle(
        artifacts=[ArtifactRecord(path, ARTIFACT_SHA, "Implemented result")],
        test_evidence=[TestEvidence("python -m unittest", TestResult.PASS, "All tests passed")],
        handoff=HandoffRecord(
            branch="agent/example",
            base_sha=BASE_SHA,
            head_sha=HEAD_SHA,
            commits=[CommitEvidence(HEAD_SHA, "feat: implement example")],
            owned_scope_respected=True,
            files_changed=[path],
            contract_changes="None",
            tests_run="Unit tests passed",
            ci_state="Not run locally",
            physical_device_state="Not applicable",
            security_privacy_notes="No credentials persisted",
            known_limitations="No live GitHub integration",
            integration_instructions="Cherry-pick the head commit",
        ),
    )


class CoordinatorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.clock = MutableClock()
        self.ids = IdFactory()
        self.state_root = Path(self.temporary.name) / "agent-runs"
        self.coordinator = CycloneAgentCoordinator(
            FileTeamStore(self.state_root), clock=self.clock, id_factory=self.ids
        )
        self.coordinator.create_team("v3", "Infrastructure V3", "captain", BASE_SHA)
        for member_id in ("agent-one", "agent-two", "worker"):
            self.coordinator.add_member(
                "v3", actor_id="captain", member_id=member_id, base_sha=BASE_SHA
            )

    def add_task(
        self,
        task_id: str,
        *,
        dependencies: tuple[str, ...] = (),
        owned_paths: tuple[str, ...] = ("tools/example/**",),
        forbidden_paths: tuple[str, ...] = (),
    ) -> TaskRecord:
        return self.coordinator.add_task(
            "v3",
            actor_id="captain",
            task_id=task_id,
            owner_lane="engineering",
            owned_paths=owned_paths,
            forbidden_paths=forbidden_paths,
            dependencies=dependencies,
            base_sha=BASE_SHA,
        )

    def test_dependency_blocked_task_cannot_be_claimed_and_done_dependency_unblocks_it(self) -> None:
        self.add_task("foundation")
        blocked = self.add_task("feature", dependencies=("foundation",))
        self.assertEqual(TaskStatus.BLOCKED, blocked.status)

        with self.assertRaises(DependencyBlockedError):
            self.coordinator.claim_task(
                "v3", "feature", agent_id="worker", base_sha=BASE_SHA
            )

        claim = self.coordinator.claim_task(
            "v3", "foundation", agent_id="worker", base_sha=BASE_SHA
        )
        self.coordinator.start_task(
            "v3", "foundation", attempt_id=claim.attempt_id or "", base_sha=BASE_SHA
        )
        self.coordinator.complete_task(
            "v3",
            "foundation",
            attempt_id=claim.attempt_id or "",
            base_sha=BASE_SHA,
            bundle=completion_bundle(),
        )
        self.assertEqual(
            TaskStatus.REVIEW, self.coordinator.get_team("v3").tasks["foundation"].status
        )
        self.coordinator.approve_task(
            "v3", "foundation", actor_id="captain", base_sha=BASE_SHA
        )

        team = self.coordinator.get_team("v3")
        self.assertEqual(TaskStatus.DONE, team.tasks["foundation"].status)
        self.assertEqual(TaskStatus.READY, team.tasks["feature"].status)

    def test_stale_attempt_cannot_overwrite_new_attempt_after_recovery(self) -> None:
        self.add_task("worker")
        first = self.coordinator.claim_task(
            "v3", "worker", agent_id="agent-one", base_sha=BASE_SHA, lease_seconds=5
        )
        first_attempt = first.attempt_id or ""
        self.coordinator.start_task("v3", "worker", attempt_id=first_attempt, base_sha=BASE_SHA)
        self.clock.value = 106

        recovered = self.coordinator.get_team("v3").tasks["worker"]
        self.assertEqual(TaskStatus.READY, recovered.status)
        self.assertIsNone(recovered.attempt_id)

        second = self.coordinator.claim_task(
            "v3", "worker", agent_id="agent-two", base_sha=BASE_SHA, lease_seconds=20
        )
        second_attempt = second.attempt_id or ""
        self.assertNotEqual(first_attempt, second_attempt)
        self.coordinator.start_task("v3", "worker", attempt_id=second_attempt, base_sha=BASE_SHA)

        with self.assertRaises(StaleAttemptError):
            self.coordinator.complete_task(
                "v3",
                "worker",
                attempt_id=first_attempt,
                base_sha=BASE_SHA,
                bundle=completion_bundle(),
            )
        self.assertEqual(
            second_attempt, self.coordinator.get_team("v3").tasks["worker"].attempt_id
        )

    def test_path_ownership_is_machine_checked(self) -> None:
        self.add_task(
            "owned",
            owned_paths=("tools/example/**",),
            forbidden_paths=("tools/example/shared.py",),
        )
        self.coordinator.validate_changed_paths("v3", "owned", ["tools/example/worker.py"])

        with self.assertRaisesRegex(ValidationError, "outside owned paths"):
            self.coordinator.validate_changed_paths("v3", "owned", ["apps/mobile/Main.kt"])
        with self.assertRaisesRegex(ValidationError, "forbidden"):
            self.coordinator.validate_changed_paths(
                "v3", "owned", ["tools/example/shared.py"]
            )
        with self.assertRaisesRegex(ValidationError, "repository-relative"):
            self.coordinator.validate_changed_paths("v3", "owned", ["C:\\secret.txt"])

    def test_exact_sha_is_mandatory_for_team_and_task_mutations(self) -> None:
        with self.assertRaisesRegex(ValidationError, "40-character"):
            CycloneAgentCoordinator(FileTeamStore(Path(self.temporary.name) / "other")).create_team(
                "bad", "Bad", "captain", "main"
            )
        self.add_task("sha")
        with self.assertRaisesRegex(ValidationError, "exact frozen SHA"):
            self.coordinator.claim_task(
                "v3", "sha", agent_id="worker", base_sha="d" * 40
            )

    def test_captain_authority_cannot_be_self_assigned_by_worker(self) -> None:
        with self.assertRaises(AuthorizationError):
            self.coordinator.add_task(
                "v3",
                actor_id="worker",
                task_id="unauthorized",
                owner_lane="engineering",
                owned_paths=["tools/example/**"],
                forbidden_paths=[],
                base_sha=BASE_SHA,
            )

    def test_only_durable_members_claim_and_active_member_cannot_be_removed(self) -> None:
        self.add_task("member-task")
        with self.assertRaises(AuthorizationError):
            self.coordinator.claim_task(
                "v3", "member-task", agent_id="outsider", base_sha=BASE_SHA
            )
        self.coordinator.claim_task(
            "v3", "member-task", agent_id="worker", base_sha=BASE_SHA
        )
        with self.assertRaises(ConflictError):
            self.coordinator.remove_member(
                "v3", actor_id="captain", member_id="worker", base_sha=BASE_SHA
            )

    def test_invalid_handoff_fails_before_task_state_changes(self) -> None:
        self.add_task("handoff")
        claim = self.coordinator.claim_task(
            "v3", "handoff", agent_id="worker", base_sha=BASE_SHA
        )
        attempt = claim.attempt_id or ""
        self.coordinator.start_task("v3", "handoff", attempt_id=attempt, base_sha=BASE_SHA)
        invalid = completion_bundle("outside/result.py")

        with self.assertRaisesRegex(ValidationError, "Ownership validation"):
            self.coordinator.complete_task(
                "v3",
                "handoff",
                attempt_id=attempt,
                base_sha=BASE_SHA,
                bundle=invalid,
            )
        self.assertEqual(
            TaskStatus.RUNNING, self.coordinator.get_team("v3").tasks["handoff"].status
        )

    def test_state_mailbox_and_events_survive_coordinator_restart(self) -> None:
        self.add_task("restart")
        claim = self.coordinator.claim_task(
            "v3", "restart", agent_id="worker", base_sha=BASE_SHA
        )
        attempt = claim.attempt_id or ""
        self.coordinator.start_task("v3", "restart", attempt_id=attempt, base_sha=BASE_SHA)
        self.coordinator.send_message(
            "v3",
            sender_id="captain",
            recipient_id="worker",
            task_id="restart",
            body="Please report test evidence.",
        )

        restarted = CycloneAgentCoordinator(
            FileTeamStore(self.state_root), clock=self.clock, id_factory=self.ids
        )
        task = restarted.get_team("v3").tasks["restart"]
        self.assertEqual(TaskStatus.RUNNING, task.status)
        self.assertEqual(attempt, task.attempt_id)
        self.assertEqual("Please report test evidence.", restarted.read_mailbox("v3", "worker")[0]["body"])
        events = restarted.read_events("v3")
        self.assertEqual(sorted(event["sequence"] for event in events), [event["sequence"] for event in events])
        self.assertIn("message.sent", [event["event_type"] for event in events])

    def test_transition_order_is_explicit_and_deterministic(self) -> None:
        self.add_task("ordered")
        with self.assertRaises(TransitionError):
            self.coordinator.approve_task(
                "v3", "ordered", actor_id="captain", base_sha=BASE_SHA
            )
        claim = self.coordinator.claim_task(
            "v3", "ordered", agent_id="worker", base_sha=BASE_SHA
        )
        with self.assertRaises(TransitionError):
            self.coordinator.complete_task(
                "v3",
                "ordered",
                attempt_id=claim.attempt_id or "",
                base_sha=BASE_SHA,
                bundle=completion_bundle(),
            )

    def test_dependency_cycle_in_persisted_state_is_rejected(self) -> None:
        task_a = TaskRecord(
            team_id="v3",
            task_id="a",
            parent_task=None,
            owner_lane="lane",
            owned_paths=["a/**"],
            forbidden_paths=[],
            dependencies=["b"],
            base_sha=BASE_SHA,
        )
        task_b = TaskRecord(
            team_id="v3",
            task_id="b",
            parent_task=None,
            owner_lane="lane",
            owned_paths=["b/**"],
            forbidden_paths=[],
            dependencies=["a"],
            base_sha=BASE_SHA,
        )
        team = TeamRecord(
            "v3", "Cycle", "captain", BASE_SHA, {"a": task_a, "b": task_b}, ["captain"]
        )
        with self.assertRaisesRegex(ValidationError, "cycle"):
            validate_task_graph(team)

    def test_cross_instance_revision_conflict_rejects_lost_update(self) -> None:
        first_store = FileTeamStore(self.state_root)
        second_store = FileTeamStore(self.state_root)
        first = first_store.load("v3")
        second = second_store.load("v3")
        expected_revision = first.revision
        first.name = "First writer"
        first.revision += 1
        first_store.save(first, expected_revision=expected_revision)
        second.name = "Stale writer"
        second.revision += 1

        with self.assertRaises(ConflictError):
            second_store.save(second, expected_revision=expected_revision)
        self.assertEqual("First writer", first_store.load("v3").name)

    def test_cross_instance_journal_sequences_remain_monotonic(self) -> None:
        first_store = FileTeamStore(self.state_root)
        second_store = FileTeamStore(self.state_root)
        first = first_store.append_event(
            TeamEvent(0, "one", "test.one", "v3", None, "captain", self.clock(), {})
        )
        second = second_store.append_event(
            TeamEvent(0, "two", "test.two", "v3", None, "captain", self.clock(), {})
        )

        self.assertEqual(first.sequence + 1, second.sequence)
        sequences = [event["sequence"] for event in first_store.read_events("v3")]
        self.assertEqual(sorted(sequences), sequences)


if __name__ == "__main__":
    unittest.main()
