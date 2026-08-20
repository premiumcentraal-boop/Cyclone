# Agent 15 Integration Proposal

Agent 4 does not own shared ignore rules or GitHub workflows. Integration should consider these
small coordinated changes after cherry-picking this feature.

## Ignore local runtime state

Add this root `.gitignore` entry:

```gitignore
# Cyclone development Agent Teams runtime state
.cyclone/agent-runs/
```

The coordinator itself does not create state until a team is created. Tests always use an isolated
temporary directory, so this feature branch leaves the repository clean.

## CI gate

Add a standard-library Python test step to the appropriate engineering/contract workflow:

```powershell
python -m unittest discover -s tools/cyclone-agent-coordinator/tests -v
```

Run it with `tools/cyclone-agent-coordinator` available on `PYTHONPATH`, or set that directory as
the command's working directory. No dependency installation, secrets, Android SDK or network is
required.

## Agent context discovery

A future shared context option may report active team/task state by invoking:

```powershell
python scripts/agent/team/cyclone-agent.py status
```

Do not parse private implementation internals into a second state store. `team.json` and the CLI
output are the durable truth. Context output must omit mailbox bodies and artifact contents.

## GitHub boundary

No workflow should grant this coordinator a token or automatic push authority. GitHub Actions may
validate task schemas, handoff bundles and ownership declarations, but staging, commits, pushes,
PRs and releases remain separately authorized operations.
