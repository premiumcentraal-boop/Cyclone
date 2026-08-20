# Cyclone Development Agent Teams

Cyclone Agent Teams is a local development coordinator for coding agents working on this
repository. It is not an Android runtime mission planner, does not spawn phone actions and has no
GitHub credentials or push behavior.

The coordinator makes the existing multi-agent protocol machine-checkable:

- every team and task carries the exact frozen 40-character base SHA;
- tasks form a durable dependency DAG;
- ownership and forbidden paths are validated as repository-relative rules;
- claims create expiring attempt IDs, and stale attempts cannot update a newer attempt;
- artifacts, tests and the complete integration handoff are validated before review;
- state, events and direct mailboxes survive process restart.

## Quick start

Run from the repository root:

```powershell
python scripts/agent/team/cyclone-agent.py team create `
  --team-id infrastructure-v3 `
  --name "Cyclone Infrastructure V3" `
  --captain integration `
  --base-sha <exact-40-character-sha>

python scripts/agent/team/cyclone-agent.py task add `
  --team infrastructure-v3 `
  --actor integration `
  --task-id capability-registry `
  --owner-lane platform `
  --owned-path "apps/mobile/app/src/main/java/com/cyclone/mobile/platform/capability/**" `
  --forbidden-path "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt" `
  --base-sha <same-exact-sha>

python scripts/agent/team/cyclone-agent.py team member-add `
  --team infrastructure-v3 `
  --actor integration `
  --member capability-agent `
  --base-sha <same-exact-sha>

python scripts/agent/team/cyclone-agent.py task claim `
  --team infrastructure-v3 `
  --task capability-registry `
  --agent capability-agent `
  --base-sha <same-exact-sha>
```

The installed package also exposes `cyclone-agent`. The repository wrapper requires no install.
Use `--state-root` before the command to select a different local state directory.

## Task lifecycle

```text
dependency incomplete                         lease expires
        ↓                                          ↓
     BLOCKED ──dependencies done──→ READY → CLAIMED → RUNNING → REVIEW → DONE
                                         │         │         │
                                         └─────────┴─────────┴→ FAILED
                                                           captain retry
                                                                ↓
                                                     BLOCKED or READY

Any non-terminal task may be captain-cancelled according to the transition policy.
```

`task complete` means the worker has submitted a validated completion bundle and moves the task to
`REVIEW`. Only the team captain can run `task approve`, which changes `REVIEW` to `DONE` and
unblocks dependent tasks. This keeps a worker from self-approving its own integration evidence.
Claims and mailbox identities must belong to the team's durable member list; only the captain may
add or remove members, and a member holding an active attempt cannot be removed.

## Required completion bundle

`task complete` and `handoff validate` consume one JSON object:

```json
{
  "artifacts": [
    {
      "path": "owned/path/file.py",
      "sha256": "<64 lowercase hex characters>",
      "description": "What this artifact implements"
    }
  ],
  "test_evidence": [
    {
      "command": "python -m unittest discover -s tests -v",
      "result": "PASS",
      "summary": "All focused tests passed"
    }
  ],
  "handoff": {
    "branch": "agent/example",
    "base_sha": "<exact frozen SHA>",
    "head_sha": "<exact head SHA>",
    "commits": [{"sha": "<exact head SHA>", "message": "feat: example"}],
    "owned_scope_respected": true,
    "files_changed": ["owned/path/file.py"],
    "contract_changes": "None",
    "tests_run": "Focused tests passed",
    "ci_state": "Not run locally",
    "physical_device_state": "Not applicable",
    "security_privacy_notes": "No credentials persisted",
    "known_limitations": "No integration wiring",
    "integration_instructions": "Cherry-pick the exact head SHA"
  }
}
```

Artifact paths must exactly equal `handoff.files_changed`, every file must be owned and not
forbidden, at least one test must pass, no test may fail, and the final commit must equal
`head_sha`. An invalid bundle cannot move a task out of `RUNNING`.

## Durable state

The default runtime root is `.cyclone/agent-runs/`:

```text
.cyclone/agent-runs/<team-id>/
├── team.json
├── events.jsonl
└── mailboxes/
    └── <recipient-id>.jsonl
```

`team.json` is written through a flushed temporary file and atomic replace. It is the state truth.
Events and mailboxes are flushed append-only journals with monotonic per-file sequence numbers.
Short-lived exclusive lock files and optimistic team revisions prevent separate coordinator
processes from silently overwriting state; abandoned locks become recoverable after 30 seconds.
On load, expired `CLAIMED` or `RUNNING` leases return to `READY` or `BLOCKED`; the old attempt ID is
cleared and recorded in a recovery event.

The root is intentionally configurable for isolated worktrees and tests. Do not commit runtime
state. Agent 15 should apply the ignore proposal in `INTEGRATION_PROPOSAL.md`.

## Security and privacy

- The coordinator never reads or creates GitHub credentials and never commits, pushes or merges.
- Paths cannot be absolute, escape through `..`, or use broad globs other than terminal `/**`.
- A worker cannot widen its owned paths, change the base SHA or approve its own result.
- Journal details omit artifact contents and test output. Store concise non-sensitive summaries;
  never put tokens, passwords, signing material or user data in tasks, events, messages or handoffs.
- Filesystem state is local development metadata, not an authority for Android phone actions.

## Reference provenance

The durable team, task dependency, mailbox and restart concepts were studied from
[`NanmiCoder/dsh-agent-teams`](https://github.com/NanmiCoder/dsh-agent-teams), which is MIT licensed.
No TypeScript, UI assets, Cordis integration, DSH runtime or branding was copied. This package is a
Cyclone-native standard-library Python implementation for repository engineering.
