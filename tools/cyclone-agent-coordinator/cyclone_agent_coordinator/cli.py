"""Command-line interface for the Cyclone development orchestrator."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Sequence

from .coordinator import CycloneAgentCoordinator, team_summary
from .errors import CoordinatorError, ValidationError
from .models import CompletionBundle
from .store import FileTeamStore


DEFAULT_STATE_ROOT = Path(".cyclone") / "agent-runs"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cyclone-agent", description="Cyclone development team coordinator")
    parser.add_argument(
        "--state-root",
        type=Path,
        default=Path(os.environ.get("CYCLONE_AGENT_STATE_DIR", DEFAULT_STATE_ROOT)),
        help="local durable state directory (default: .cyclone/agent-runs)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    status = commands.add_parser("status", help="show one team or all teams")
    status.add_argument("--team")

    team = commands.add_parser("team", help="team lifecycle")
    team_commands = team.add_subparsers(dest="team_command", required=True)
    create = team_commands.add_parser("create")
    create.add_argument("--team-id", required=True)
    create.add_argument("--name", required=True)
    create.add_argument("--captain", required=True)
    create.add_argument("--base-sha", required=True)
    member_add = team_commands.add_parser("member-add")
    member_add.add_argument("--team", required=True)
    member_add.add_argument("--actor", required=True)
    member_add.add_argument("--member", required=True)
    member_add.add_argument("--base-sha", required=True)
    member_remove = team_commands.add_parser("member-remove")
    member_remove.add_argument("--team", required=True)
    member_remove.add_argument("--actor", required=True)
    member_remove.add_argument("--member", required=True)
    member_remove.add_argument("--base-sha", required=True)

    task = commands.add_parser("task", help="task DAG and attempts")
    task_commands = task.add_subparsers(dest="task_command", required=True)
    task_list = task_commands.add_parser("list")
    task_list.add_argument("--team", required=True)

    add = task_commands.add_parser("add")
    add.add_argument("--team", required=True)
    add.add_argument("--actor", required=True)
    add.add_argument("--task-id", required=True)
    add.add_argument("--owner-lane", required=True)
    add.add_argument("--owned-path", action="append", required=True)
    add.add_argument("--forbidden-path", action="append", default=[])
    add.add_argument("--depends-on", action="append", default=[])
    add.add_argument("--parent")
    add.add_argument("--base-sha", required=True)

    claim = task_commands.add_parser("claim")
    _attempt_target_arguments(claim)
    claim.add_argument("--agent", required=True)
    claim.add_argument("--lease-seconds", type=int, default=900)

    start = task_commands.add_parser("start")
    _active_attempt_arguments(start)

    renew = task_commands.add_parser("renew")
    _active_attempt_arguments(renew)
    renew.add_argument("--lease-seconds", type=int, default=900)

    complete = task_commands.add_parser("complete", help="submit a validated bundle for review")
    _active_attempt_arguments(complete)
    complete.add_argument("--bundle", type=Path, required=True)

    approve = task_commands.add_parser("approve", help="captain accepts REVIEW as DONE")
    _captain_target_arguments(approve)

    retry = task_commands.add_parser("retry")
    _captain_target_arguments(retry)

    cancel = task_commands.add_parser("cancel")
    _captain_target_arguments(cancel)
    cancel.add_argument("--reason", required=True)

    paths = task_commands.add_parser("validate-paths")
    paths.add_argument("--team", required=True)
    paths.add_argument("--task", required=True)
    paths.add_argument("path", nargs="+")

    handoff = commands.add_parser("handoff", help="handoff evidence validation")
    handoff_commands = handoff.add_subparsers(dest="handoff_command", required=True)
    validate = handoff_commands.add_parser("validate")
    validate.add_argument("--team", required=True)
    validate.add_argument("--task", required=True)
    validate.add_argument("--bundle", type=Path, required=True)

    mailbox = commands.add_parser("mailbox", help="durable agent mailbox")
    mailbox_commands = mailbox.add_subparsers(dest="mailbox_command", required=True)
    send = mailbox_commands.add_parser("send")
    send.add_argument("--team", required=True)
    send.add_argument("--sender", required=True)
    send.add_argument("--recipient", required=True)
    send.add_argument("--task")
    send.add_argument("--body", required=True)
    mail_list = mailbox_commands.add_parser("list")
    mail_list.add_argument("--team", required=True)
    mail_list.add_argument("--recipient", required=True)
    mail_list.add_argument("--after", type=int, default=0)

    events = commands.add_parser("events", help="read the durable event journal")
    events.add_argument("--team", required=True)
    events.add_argument("--after", type=int, default=0)
    return parser


def _attempt_target_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--team", required=True)
    parser.add_argument("--task", required=True)
    parser.add_argument("--base-sha", required=True)


def _active_attempt_arguments(parser: argparse.ArgumentParser) -> None:
    _attempt_target_arguments(parser)
    parser.add_argument("--attempt", required=True)


def _captain_target_arguments(parser: argparse.ArgumentParser) -> None:
    _attempt_target_arguments(parser)
    parser.add_argument("--actor", required=True)


def _load_bundle(path: Path) -> CompletionBundle:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValidationError(f"Cannot read completion bundle: {error}") from error
    if not isinstance(data, dict):
        raise ValidationError("Completion bundle must be a JSON object")
    try:
        return CompletionBundle.from_dict(data)
    except (TypeError, ValueError) as error:
        raise ValidationError(f"Completion bundle is invalid: {error}") from error


def _emit(value: Any) -> None:
    if hasattr(value, "to_dict"):
        value = value.to_dict()
    print(json.dumps(value, indent=2, sort_keys=True))


def run(args: argparse.Namespace, coordinator: CycloneAgentCoordinator) -> Any:
    if args.command == "status":
        if args.team:
            return team_summary(coordinator.get_team(args.team))
        return [team_summary(team) for team in coordinator.list_teams()]

    if args.command == "team" and args.team_command == "create":
        return team_summary(
            coordinator.create_team(args.team_id, args.name, args.captain, args.base_sha)
        )
    if args.command == "team" and args.team_command == "member-add":
        return team_summary(
            coordinator.add_member(
                args.team,
                actor_id=args.actor,
                member_id=args.member,
                base_sha=args.base_sha,
            )
        )
    if args.command == "team" and args.team_command == "member-remove":
        return team_summary(
            coordinator.remove_member(
                args.team,
                actor_id=args.actor,
                member_id=args.member,
                base_sha=args.base_sha,
            )
        )

    if args.command == "task":
        if args.task_command == "list":
            return team_summary(coordinator.get_team(args.team))["tasks"]
        if args.task_command == "add":
            return coordinator.add_task(
                args.team,
                actor_id=args.actor,
                task_id=args.task_id,
                owner_lane=args.owner_lane,
                owned_paths=args.owned_path,
                forbidden_paths=args.forbidden_path,
                dependencies=args.depends_on,
                parent_task=args.parent,
                base_sha=args.base_sha,
            )
        if args.task_command == "claim":
            return coordinator.claim_task(
                args.team,
                args.task,
                agent_id=args.agent,
                base_sha=args.base_sha,
                lease_seconds=args.lease_seconds,
            )
        if args.task_command == "start":
            return coordinator.start_task(
                args.team, args.task, attempt_id=args.attempt, base_sha=args.base_sha
            )
        if args.task_command == "renew":
            return coordinator.renew_lease(
                args.team,
                args.task,
                attempt_id=args.attempt,
                base_sha=args.base_sha,
                lease_seconds=args.lease_seconds,
            )
        if args.task_command == "complete":
            return coordinator.complete_task(
                args.team,
                args.task,
                attempt_id=args.attempt,
                base_sha=args.base_sha,
                bundle=_load_bundle(args.bundle),
            )
        if args.task_command == "approve":
            return coordinator.approve_task(
                args.team, args.task, actor_id=args.actor, base_sha=args.base_sha
            )
        if args.task_command == "retry":
            return coordinator.retry_task(
                args.team, args.task, actor_id=args.actor, base_sha=args.base_sha
            )
        if args.task_command == "cancel":
            return coordinator.cancel_task(
                args.team,
                args.task,
                actor_id=args.actor,
                base_sha=args.base_sha,
                reason=args.reason,
            )
        if args.task_command == "validate-paths":
            coordinator.validate_changed_paths(args.team, args.task, args.path)
            return {"ok": True, "paths": sorted(args.path)}

    if args.command == "handoff" and args.handoff_command == "validate":
        coordinator.validate_handoff(args.team, args.task, _load_bundle(args.bundle))
        return {"ok": True, "team_id": args.team, "task_id": args.task}

    if args.command == "mailbox":
        if args.mailbox_command == "send":
            return coordinator.send_message(
                args.team,
                sender_id=args.sender,
                recipient_id=args.recipient,
                task_id=args.task,
                body=args.body,
            ).__dict__
        if args.mailbox_command == "list":
            return coordinator.read_mailbox(args.team, args.recipient, args.after)

    if args.command == "events":
        return coordinator.read_events(args.team, args.after)
    raise ValidationError("Unknown command")


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    coordinator = CycloneAgentCoordinator(FileTeamStore(args.state_root))
    try:
        _emit(run(args, coordinator))
        return 0
    except (CoordinatorError, OSError) as error:
        code = getattr(error, "code", "IO_ERROR")
        print(json.dumps({"ok": False, "error": code, "message": str(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
